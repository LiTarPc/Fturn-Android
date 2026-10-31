package com.freeturn.app.service

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.freeturn.app.domain.captcha.CaptchaIpcClient
import com.freeturn.app.domain.captcha.CaptchaProtocol
import com.freeturn.app.domain.captcha.CaptchaUiState
import com.freeturn.app.domain.proxy.ProxyServiceState
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI retains only service ownership and safe metadata; never an Activity or secret page URL. */
object CaptchaPresentation {
    val state = ProxyServiceState.captchaUi
    internal var owner: CaptchaCoordinator? = null
    internal fun publish(source: CaptchaCoordinator, value: CaptchaUiState) {
        if (owner === source) ProxyServiceState.setCaptchaUi(value)
    }
    fun showManual() { owner?.showManual() }
    fun dismissManual() { owner?.dismissManual() }
    fun requestManual() { owner?.requestManual() }
    fun resume(generation: String = state.value.generation) { owner?.resume(generation) }
    fun manualView(generation: String, requestId: String): WebView? = owner?.manualView(generation, requestId)
    fun detach(view: WebView) { (view.parent as? ViewGroup)?.removeView(view) }
}

/** Service-owned browser survives Activity recreation. Every asynchronous result is generation-bound. */
class CaptchaCoordinator(context: Context, private val onState: (CaptchaUiState) -> Unit, private val pagePort: Int? = 8765) {
    private val context = context.applicationContext
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private class Session(val generation: String, val token: String, val browser: Boolean, pagePort: Int?) {
        val client = CaptchaIpcClient(generation, token, pagePort)
        val revision = AtomicLong()
        @Volatile var structured = false
        fun environment() = mapOf("FREETURN_RAW_CAPTCHA_TOKEN" to token,
            "FREETURN_RAW_CAPTCHA_GENERATION" to generation,
            "FREETURN_RAW_BROWSER_CAPTCHA" to if (browser) "1" else "0")
        override fun toString() = "CaptchaProcessSession"
    }
    @Volatile private var session: Session? = null
    private var challenge: CaptchaProtocol.Challenge? = null
    private var descriptor: CaptchaIpcClient.Descriptor? = null
    private var view: WebView? = null
    private var expiry: Job? = null
    private var fetch: Job? = null
    private var ipcPort = 0
    private var ui = CaptchaUiState()
    private var availableRequest = ""
    private val reportedFailures = mutableSetOf<String>()

    init { CaptchaPresentation.owner = this }

    fun begin(browser: Boolean): Map<String, String> {
        val random = SecureRandom()
        fun hex(size: Int) = ByteArray(size).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val next = Session(hex(16), hex(32), browser, pagePort)
        session?.client?.close()
        session = next
        com.freeturn.app.domain.diagnostics.DiagnosticLogs.registerSecrets(listOf(next.token))
        main.launch {
            if (session !== next) return@launch
            clearPage(); ipcPort = 0; availableRequest = ""; reportedFailures.clear()
            publish(CaptchaUiState(generation = next.generation))
        }
        return next.environment()
    }

    fun isStructured(): Boolean = session?.structured == true

    /** Returns true even for malformed events, so control payloads cannot reach a log sink. */
    fun acceptLine(line: String): Boolean {
        if (!line.startsWith("FTURN_EVENT")) return false
        val current = session ?: return true
        when (val parsed = CaptchaProtocol.parse(line, current.generation, System.currentTimeMillis())) {
            is CaptchaProtocol.Result.Accepted -> {
                current.structured = true
                // Authorization of another credential group must not invalidate an
                // active page fetch or replace a manual challenge/user wait.
                if (parsed.event is CaptchaProtocol.Auth && parsed.event.phase == CaptchaProtocol.AuthPhase.AUTHORIZED) {
                    main.launch {
                        if (session === current && ui.phase == CaptchaUiState.Phase.RESUMING) apply(current, parsed.event, current.revision.get())
                    }
                    return true
                }
                val revision = current.revision.incrementAndGet()
                main.launch {
                    if (session === current && current.revision.get() == revision) apply(current, parsed.event, revision)
                }
            }
            is CaptchaProtocol.Result.Rejected -> ProxyServiceState.addLog("[AUTH] event=metadata_rejected reason=${parsed.reason}")
            CaptchaProtocol.Result.NotEvent -> ProxyServiceState.addLog("[AUTH] event=metadata_rejected reason=prefix")
        }
        return true
    }

    private fun publish(value: CaptchaUiState) {
        if (CaptchaPresentation.owner !== this) return
        ui = value
        CaptchaPresentation.publish(this, value)
        onState(value)
    }

    private fun apply(current: Session, event: CaptchaProtocol.Event, revision: Long) {
        ipcPort = event.ipcPort
        when (event) {
            is CaptchaProtocol.Auth -> {
                if (event.phase == CaptchaProtocol.AuthPhase.AUTHORIZED) {
                    if (ui.phase == CaptchaUiState.Phase.RESUMING) {
                        clearPage(); publish(CaptchaUiState(generation = current.generation))
                    }
                    return
                }
                clearPage()
                publish(CaptchaUiState(
                    phase = if (event.phase == CaptchaProtocol.AuthPhase.WAITING_USER)
                        CaptchaUiState.Phase.WAITING_USER else CaptchaUiState.Phase.RESUMING,
                    generation = current.generation))
                ProxyServiceState.addLog("[AUTH] event=authorization phase=${event.phase.name.lowercase()}")
            }
            is CaptchaProtocol.Challenge -> {
                ProxyServiceState.addLog("[AUTH] event=challenge mode=${event.mode.name.lowercase()} phase=${event.phase.name.lowercase()}")
                if (event.phase != CaptchaProtocol.Phase.PENDING) {
                    if (challenge?.requestId == event.requestId) {
                        clearPage()
                        publish(CaptchaUiState(generation = current.generation))
                    }
                    return
                }
                val previous = challenge
                challenge = event
                val manual = event.mode == CaptchaProtocol.Mode.MANUAL
                val keepDismissed = previous?.requestId == event.requestId && ui.phase == CaptchaUiState.Phase.MANUAL && !ui.manualOpen
                publish(CaptchaUiState(if (manual) CaptchaUiState.Phase.MANUAL else CaptchaUiState.Phase.BROWSER,
                    current.generation, event.requestId, manualOpen = manual && !keepDismissed))
                expiry?.cancel()
                expiry = main.launch {
                    delay((event.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                    if (session === current && challenge?.requestId == event.requestId) {
                        report(current, event, "unavailable", "challenge_expired")
                        clearPage()
                        publish(CaptchaUiState(CaptchaUiState.Phase.WAITING_USER, current.generation))
                    }
                }
                if (previous?.requestId == event.requestId && view != null) return
                fetchPage(current, event, revision)
            }
        }
    }

    private fun fetchPage(current: Session, event: CaptchaProtocol.Challenge, revision: Long) {
        fetch?.cancel()
        fetch = main.launch {
            val result = withContext(Dispatchers.IO) { runCatching { current.client.fetch(event) } }
            if (session !== current || current.revision.get() != revision || challenge?.requestId != event.requestId) return@launch
            result.fold(onSuccess = { page ->
                com.freeturn.app.domain.diagnostics.DiagnosticLogs.registerSecrets(listOf(page.url))
                descriptor = page
                if (!current.browser && event.mode == CaptchaProtocol.Mode.BROWSER) report(current, event, "manual_requested", "user_requested")
                else createPage(current, page)
            }, onFailure = { hostFailed(current, event, "host_unavailable") })
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun createPage(current: Session, page: CaptchaIpcClient.Descriptor) {
        destroyView()
        try {
            val web = WebView(context)
            view = web
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            val origin = java.net.URI(page.url).let { "${it.scheme}://${it.host}:${it.port}" }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !CaptchaIpcClient.sameOrigin(page.url, request.url.toString())
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.startsWith("data:") || url.startsWith("blob:$origin/")) return null
                    return if (CaptchaIpcClient.sameOrigin(page.url, url)) null else
                        WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && this@CaptchaCoordinator.view === view) hostFailed(current, page.event, "load_failed")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400 && this@CaptchaCoordinator.view === view) hostFailed(current, page.event, "load_failed")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (this@CaptchaCoordinator.view === view) {
                        hostFailed(current, page.event, "renderer_gone")
                        destroyView()
                    }
                    return true
                }
            }
            val metrics = context.resources.displayMetrics
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            web.onResume()
            web.loadUrl(page.url)
            publish(ui.copy(hostVersion = ui.hostVersion + 1))
            if (availableRequest != page.event.requestId) {
                availableRequest = page.event.requestId
                report(current, page.event, "available", "")
            }
        } catch (_: Exception) { destroyView(); hostFailed(current, page.event, "host_unavailable") }
    }

    private fun hostFailed(current: Session, event: CaptchaProtocol.Challenge, reason: String) {
        if (session !== current || challenge?.requestId != event.requestId) return
        ProxyServiceState.addLog("[AUTH] event=browser_host_unavailable reason=$reason")
        if (challenge?.mode == CaptchaProtocol.Mode.BROWSER) report(current, event, "unavailable", reason)
        else publish(ui.copy(manualOpen = false, retryFailed = true))
    }

    private fun report(current: Session, event: CaptchaProtocol.Challenge, status: String, reason: String) {
        val key = "${event.requestId}:$status:$reason"
        if (!reportedFailures.add(key)) return
        main.launch {
            val result = withContext(Dispatchers.IO) { runCatching { current.client.report(event, status, reason) } }
            if (session === current && challenge?.requestId == event.requestId && result.isFailure) {
                reportedFailures.remove(key)
                publish(ui.copy(retryFailed = true))
                ProxyServiceState.addLog("[AUTH] event=host_report_failed")
            }
        }
    }

    fun requestManual() = main.launch {
        val current = session ?: return@launch
        val event = challenge ?: return@launch
        report(current, event, "manual_requested", "user_requested")
    }
    fun showManual() = main.launch {
        if (ui.phase != CaptchaUiState.Phase.MANUAL) return@launch
        val current = session ?: return@launch
        if (view == null) {
            val page = descriptor
            if (page != null) createPage(current, page)
            else if (fetch?.isActive != true) challenge?.let { fetchPage(current, it, current.revision.get()) }
        }
        publish(ui.copy(manualOpen = true, retryFailed = false))
    }
    fun dismissManual() = main.launch { publish(ui.copy(manualOpen = false)) }
    fun manualView(generation: String, requestId: String): WebView? =
        if (session?.generation == generation && challenge?.requestId == requestId && ui.phase == CaptchaUiState.Phase.MANUAL)
            view?.also { it.onResume() } else null

    fun resume(generation: String) = main.launch {
        val current = session ?: return@launch
        if (current.generation != generation || ui.phase != CaptchaUiState.Phase.WAITING_USER || ipcPort == 0) return@launch
        val port = ipcPort
        publish(CaptchaUiState(CaptchaUiState.Phase.RESUMING, current.generation))
        val revision = current.revision.get()
        val result = withContext(Dispatchers.IO) { runCatching { current.client.resume(port) } }
        if (session === current && current.revision.get() == revision && result.isFailure) {
            publish(CaptchaUiState(CaptchaUiState.Phase.WAITING_USER, current.generation, retryFailed = true))
            ProxyServiceState.addLog("[AUTH] event=resume_failed")
        }
    }

    private fun destroyView() {
        val old = view; view = null
        if (old != null) {
            CaptchaPresentation.detach(old)
            runCatching { old.stopLoading(); old.onPause(); old.destroy() }
        }
    }
    private fun clearPage() {
        fetch?.cancel(); fetch = null; expiry?.cancel(); expiry = null
        challenge = null; descriptor = null; destroyView()
    }
    fun end() {
        val old = session; session = null; old?.client?.close()
        main.launch { if (session == null) { clearPage(); publish(CaptchaUiState()) } }
    }
    fun close() {
        end()
        main.launch {
            if (CaptchaPresentation.owner === this@CaptchaCoordinator) CaptchaPresentation.owner = null
            main.cancel()
        }
    }
}
