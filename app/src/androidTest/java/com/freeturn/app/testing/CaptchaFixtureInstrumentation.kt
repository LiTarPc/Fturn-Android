package com.freeturn.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebView
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.captcha.CaptchaProtocol
import com.freeturn.app.domain.captcha.CaptchaUiState
import com.freeturn.app.domain.diagnostics.DiagnosticLogs
import com.freeturn.app.service.CaptchaPresentation
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Exercises ordinary localhost HTML, not an external CAPTCHA or a live account. */
@androidx.annotation.RequiresApi(29)
class CaptchaFixtureInstrumentation : Instrumentation() {
    private lateinit var service: CaptchaFixtureService
    private var connection: ServiceConnection? = null
    private var stage = "setup"
    private val passed = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun check(value: Boolean, reason: String) { if (!value) throw AssertionError(reason) }
    private fun await(reason: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(50) }
        throw AssertionError(reason)
    }
    private fun main(action: () -> Unit) = runOnMainSync(action)
    private fun record(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") }) }
    override fun onStart() {
        val result = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        try {
            val context = targetContext
            runBlocking {
                AppPreferences(context).apply {
                    setTgSubscribeShown(); setSuppressTgPrompt(true)
                    setSuppressUpdatePrompt(true); setBatteryPromptShown()
                }
            }
            val started = CountDownLatch(1)
            val intent = Intent(context, CaptchaFixtureService::class.java)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as CaptchaFixtureService.LocalBinder).service; started.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            connection = conn
            main { context.startForegroundService(intent); context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            check(started.await(10, TimeUnit.SECONDS), "fixture service did not bind")
            stage = "background_lifecycle"
            Fixture().use { fixture ->
                fixture.begin()
                fixture.pending("browser")
                await("offscreen browser did not execute ordinary JavaScript") { fixture.heartbeats.get() >= 3 }
                check(CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.BROWSER, "browser state missing")
                record("service_browser_without_activity")
                // No challenge URL or authentication value is put into an Activity intent.
                val activity = startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(context.packageName,
                    "com.freeturn.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val before = fixture.heartbeats.get()
                main { activity.recreate() }
                await("browser stopped during Activity recreation") { fixture.heartbeats.get() >= before + 3 }
                record("activity_recreation_preserves_browser")
                // Instrumentation keeps no reference to the replacement Activity.
                val backgroundBefore = fixture.heartbeats.get()
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await("browser stopped after Activity entered background") { fixture.heartbeats.get() >= backgroundBefore + 3 }
                Thread.sleep(45_000)
                val later = fixture.heartbeats.get()
                await("browser stalled during sustained background execution") { fixture.heartbeats.get() >= later + 2 }
                record("activity_background_preserves_browser_45_seconds")
                val sameId = fixture.requestId
                service.coordinator.requestManual()
                await("manual transition did not preserve the active challenge") {
                    CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.MANUAL
                }
                check(CaptchaPresentation.state.value.requestId == sameId, "manual transition replaced the challenge")
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                await("manual notification missing") { notificationManager.activeNotifications.any { it.id == 2 } }
                record("same_challenge_manual_fallback_and_notification")
                main { service.coordinator.end() }
                await("STOP retained challenge state") { !CaptchaPresentation.state.value.isWaiting }
                await("STOP retained the manual notification") { notificationManager.activeNotifications.none { it.id == 2 } }
                record("stop_releases_browser_and_notification")
            }
            stage = "renderer_failure"
            Fixture().use { fixture ->
                fixture.begin(); fixture.pending("browser")
                await("renderer fixture did not load") { fixture.heartbeats.get() >= 2 }
                var terminated = false
                main {
                    val field = service.coordinator.javaClass.getDeclaredField("view").apply { isAccessible = true }
                    val web = field.get(service.coordinator) as WebView
                    terminated = web.webViewRenderProcess?.terminate() == true
                }
                check(terminated, "renderer process was not available")
                await("renderer death did not request manual fallback") {
                    fixture.unavailable.get() > 0 && CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.MANUAL
                }
                val before = fixture.heartbeats.get()
                await("renderer was not recreated for manual mode") { fixture.heartbeats.get() >= before + 2 }
                record("renderer_failure_preserves_process_and_falls_back")
                main { service.coordinator.end() }
            }
            stage = "expiry_resume_stale_generation"
            Fixture().use { fixture ->
                fixture.begin(); val old = fixture.event("browser")
                main { service.coordinator.end() }
                fixture.begin()
                service.coordinator.acceptLine(old)
                Thread.sleep(200)
                check(!CaptchaPresentation.state.value.isWaiting, "old generation changed new session")
                fixture.pending("manual", 1200)
                await("expired challenge remained active", 8_000) { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.WAITING_USER }
                Thread.sleep(350)
                check(fixture.resumes.get() == 0, "user wait automatically retried authorization")
                service.coordinator.resume(fixture.generation)
                service.coordinator.resume(fixture.generation)
                await("explicit resume did not reach authenticated IPC") { fixture.resumes.get() == 1 }
                await("successful authorization without another challenge retained RESUMING") {
                    CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.IDLE
                }
                record("expiry_stale_generation_and_resume_without_captcha")
                main { service.coordinator.end() }
            }
            stage = "screen_off"
            Fixture().use { fixture ->
                fixture.begin(); fixture.pending("browser")
                await("screen-off fixture did not load") { fixture.heartbeats.get() >= 2 }
                val before = fixture.heartbeats.get()
                try {
                    uiAutomation.executeShellCommand("input keyevent 223").close()
                    await("display did not turn off") { !targetContext.getSystemService(android.os.PowerManager::class.java).isInteractive }
                    await("ordinary browser JavaScript stopped with the screen off") { fixture.heartbeats.get() >= before + 3 }
                    record("screen_off_foreground_browser_continues")
                } finally { uiAutomation.executeShellCommand("input keyevent 224").close() }
                main { service.coordinator.end() }
            }
            stage = "v2_capture_lifecycle"
            Fixture(2, "pow_capture").use { fixture ->
                fixture.begin(); fixture.pending("browser", 10_000)
                await("v2 capture page did not execute JavaScript") { fixture.heartbeats.get() >= 2 }
                check(CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.BROWSER, "capture opened a manual dialog")
                val completed = fixture.event("browser", "captured")
                service.coordinator.acceptLine(completed)
                await("capture was treated as completed authorization") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.RESUMING }
                record("v2_capture_result_keeps_authorization_pending")
                fixture.purpose = "solve"
                fixture.requestId = UUID.randomUUID().toString().replace("-", "")
                fixture.pending("manual", 20_000)
                await("separate manual solve was not shown") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.MANUAL }
                service.coordinator.acceptLine(completed)
                Thread.sleep(250)
                check(CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.MANUAL, "old capture result cleared the new manual solve")
                record("v2_old_capture_cannot_clear_new_manual_solve")
                main { service.coordinator.end() }
            }
            stage = "v2_capture_expiry"
            Fixture(2, "pow_capture").use { fixture ->
                fixture.begin(); fixture.pending("browser", 1200)
                await("expired capture was not returned to core authorization") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.RESUMING }
                check(!CaptchaPresentation.state.value.manualOpen, "expired capture became a manual dialog")
                record("v2_capture_expiry_does_not_expose_capture_page")
                main { service.coordinator.end() }
            }
            stage = "production_pow_observers"
            for (core in listOf("turn", "raw")) {
                Fixture(2, "pow_capture", "$core-inject.js").use { fixture ->
                    fixture.begin(); fixture.pending("browser", 12_000)
                    await("production $core observer did not deliver valid browser proof", 14_000) { fixture.proofs.get() == 1 }
                    await("production observer treated proof as completed authorization") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.RESUMING }
                    check(!CaptchaPresentation.state.value.manualOpen, "production proof observer opened a manual dialog")
                    record("${core}_production_injector_captures_real_webview_sha256")
                    main { service.coordinator.end() }
                }
            }
            stage = "native_webview_pow"
            val nativeFixture = java.io.File(targetContext.applicationInfo.nativeLibraryDir, "libpowfixture.so")
            if (nativeFixture.isFile) runNativePowFixture(nativeFixture)
            else result.putInt("native_fixture_skipped", 1)
            stage = "v2_stale_protocol"
            Fixture(2, "pow_capture").use { fixture ->
                fixture.begin(); fixture.pending("browser", 10_000)
                await("stale-protocol fixture did not load") { fixture.heartbeats.get() >= 2 }
                val stale = JSONObject(fixture.event("browser").removePrefix(CaptchaProtocol.PREFIX))
                    .put("v", 1).put("generation", "stale-process")
                service.coordinator.acceptLine(CaptchaProtocol.PREFIX + stale)
                Thread.sleep(250)
                check(!service.coordinator.hasIntegrationError() && CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.BROWSER,
                    "old-generation protocol mismatch broke the active process")
                record("old_generation_v1_cannot_break_current_v2")
                main { service.coordinator.end() }
            }
            stage = "v2_incompatible_protocol"
            for (invalidPurpose in listOf(false, true)) {
                Fixture(2, "pow_capture").use { fixture ->
                    fixture.begin()
                    val event = JSONObject(fixture.event("browser").removePrefix(CaptchaProtocol.PREFIX))
                    if (invalidPurpose) event.put("purpose", "unknown") else event.put("v", 1)
                    service.coordinator.acceptLine(CaptchaProtocol.PREFIX + event)
                    await("incompatible metadata did not produce a visible terminal error") {
                        CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.INTEGRATION_ERROR
                    }
                    fixture.pending("browser", 10_000)
                    Thread.sleep(250)
                    check(fixture.heartbeats.get() == 0 && fixture.resumes.get() == 0, "failed protocol resumed or loaded a page")
                    check(service.coordinator.hasIntegrationError(), "failure did not reach process controller")
                    main { service.coordinator.end() }
                    check(CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.INTEGRATION_ERROR, "shutdown hid integration failure")
                    record(if (invalidPurpose) "v2_invalid_purpose_stops_without_retry" else "v2_host_rejects_v1_without_retry")
                }
            }
            stage = "vpn_library_smoke"
            check(io.nekohasekai.libbox.Libbox.version() == "1.12.0", "unexpected native VPN library version")
            io.nekohasekai.libbox.Libbox.checkConfig("""{"log":{"disabled":true},"outbounds":[{"type":"direct"}]}""")
            record("patched_vpn_library_loads_and_validates_config_without_starting_vpn")
            stage = "diagnostic_privacy"
            val archive = runBlocking { DiagnosticLogs.exportArchive() }
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    check(!text.contains("fixture-private-page"), "challenge page leaked to diagnostics")
                }
            }
            record("challenge_url_absent_from_export")
            result.putInt("tests_failed", 0)
            result.putString("stream", "Verified ${passed.size} browser lifecycle checks\n")
            resultCode = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putInt("tests_failed", 1); result.putString("failure_stage", stage)
            result.putString("failure_type", e.javaClass.simpleName)
            result.putString("stream", "FAIL $stage: ${e.javaClass.simpleName}\n")

        } finally {
            runCatching { main { if (::service.isInitialized) {
                service.coordinator.begin(browser = false, protocol = 2)
                service.coordinator.end()
            } } }
            connection?.let { runCatching { targetContext.unbindService(it) } }
            runCatching { targetContext.stopService(Intent(targetContext, CaptchaFixtureService::class.java)) }
        }
        finish(resultCode, result)
    }

    private fun runNativePowFixture(executable: java.io.File) {
        var environment: Map<String, String> = emptyMap()
        main { environment = service.coordinator.begin(browser = false, protocol = 2, advanced = true) }
        val builder = ProcessBuilder(executable.absolutePath, "-test.run", "^TestBrowserPowWebViewFixture$", "-test.v", "-test.timeout=30s")
            .directory(targetContext.cacheDir).redirectErrorStream(true)
        builder.environment().putAll(environment)
        builder.environment()["FREETURN_RAW_WEBVIEW_FIXTURE"] = "1"
        builder.environment()["TMPDIR"] = targetContext.cacheDir.absolutePath
        val process = builder.start()
        var validated = false
        var captured = false
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.startsWith(CaptchaProtocol.PREFIX)) {
                    val metadata = JSONObject(line.removePrefix(CaptchaProtocol.PREFIX))
                    if (metadata.optString("purpose") == "pow_capture" && metadata.optString("phase") == "captured") captured = true
                    service.coordinator.acceptLine(line)
                } else if (line == "FTURN_FIXTURE browser_pow=validated authorization=not_assessed") validated = true
            } }
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 && captured && validated, "native browser PoW fixture failed")
            await("captured browser proof did not leave authorization pending") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.RESUMING }
            record("native_go_webview_pow_capture_and_validation")
        } finally {
            process.destroy()
            main { service.coordinator.end() }
        }
    }

    private inner class Fixture(private val version: Int = 1, var purpose: String = "solve", private val observerAsset: String? = null) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        @Volatile var generation = ""
        @Volatile private var token = ""
        @Volatile var requestId = UUID.randomUUID().toString().replace("-", "")
        @Volatile private var mode = "browser"
        @Volatile private var expiry = System.currentTimeMillis() + 60_000
        private val proofPath = "/local-captcha-pow/" + UUID.randomUUID().toString().replace("-", "")
        val proofs = AtomicInteger()
        val heartbeats = AtomicInteger()
        val unavailable = AtomicInteger()
        val resumes = AtomicInteger()
        init { pool.execute { while (!server.isClosed) runCatching { val socket = server.accept(); pool.execute { handle(socket) } } } }
        fun begin() {
            main {
                val env = service.coordinator.begin(true, protocol = version, advanced = version == 2)
                generation = env.getValue("FREETURN_RAW_CAPTCHA_GENERATION")
                token = env.getValue("FREETURN_RAW_CAPTCHA_TOKEN")
                requestId = UUID.randomUUID().toString().replace("-", "")
            }
        }
        fun event(mode: String, phase: String = "pending") = CaptchaProtocol.PREFIX + JSONObject()
            .put("v", version).put("type", "captcha").put("generation", generation).put("ipc_port", server.localPort)
            .put("request_id", requestId).put("mode", mode).put("phase", phase).put("expires_at_ms", expiry)
            .apply { if (version == 2) put("purpose", purpose) }
        fun pending(next: String, ttl: Long = 60_000) {
            mode = next; expiry = System.currentTimeMillis() + ttl
            service.coordinator.acceptLine(event(mode))
        }
        private fun proofPage(): String {
            val observer = targetContext.assets.open(requireNotNull(observerAsset)).bufferedReader().use { it.readText() }
            val origin = "http://127.0.0.1:${server.localPort}"
            val config = JSONObject().put("local", origin).put("upstream", origin).put("capturePow", true).put("powPath", proofPath)
            return """<!doctype html><html><head><script>window.__ftpCaptcha=$config;$observer</script><script>
                (async()=>{const input='offline-injector-fixture';let nonce=0,hash='';
                do{const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(input+nonce));
                hash=Array.from(new Uint8Array(digest),v=>v.toString(16).padStart(2,'0')).join('');
                if(hash.startsWith('0'))break;nonce++;}while(nonce<100000);
                window.captchaPowResult='v2.'+btoa(JSON.stringify({hash,nonce,duration_ms:1,telemetry:{ua:{ok:true,result:{userAgent:navigator.userAgent}}}}));})();
                </script></head><body>Offline production observer fixture</body></html>""".trimIndent()
        }
        private fun validProof(body: String): Boolean = runCatching {
            val envelope = java.net.URLDecoder.decode(body.removePrefix("pow="), "UTF-8").substringAfter('.')
            val proof = JSONObject(String(android.util.Base64.decode(envelope, android.util.Base64.DEFAULT), Charsets.UTF_8))
            val nonce = proof.getLong("nonce")
            if (nonce !in 0..100000) return false
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(("offline-injector-fixture" + nonce).toByteArray())
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            digest.startsWith("0") && digest == proof.getString("hash") && proof.has("telemetry")
        }.getOrDefault(false)
        private fun handle(socket: java.net.Socket) = socket.use {
            it.soTimeout = 3000
            val reader = it.getInputStream().bufferedReader()
            val first = reader.readLine() ?: return@use
            val path = first.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return@use
                if (line.isEmpty()) break
                if (':' in line) headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val size = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
            val body = CharArray(size)
            var count = 0
            while (count < size) { val n = reader.read(body, count, size - count); if (n < 0) break; count += n }
            var after: (() -> Unit)? = null
            var status = 200
            var contentType = "application/json"
            val response = when {
                path.startsWith("/v1/") && (headers["authorization"] != "Bearer $token" || headers["x-fturn-generation"] != generation) -> {
                    status = 401; "{}"
                }
                path == "/v1/captcha/$requestId" -> JSONObject().put("generation", generation).put("request_id", requestId)
                    .put("mode", mode).put("expires_at_ms", expiry).put("url", "http://127.0.0.1:${server.localPort}/fixture-private-page")
                    .apply { if (version == 2) put("purpose", purpose) }.toString()
                path == "/v1/captcha/$requestId/host" -> {
                    val data = JSONObject(String(body))
                    if (data.optString("status") in setOf("unavailable", "manual_requested") && data.optString("reason") != "challenge_expired") {
                        if (data.optString("status") == "unavailable") unavailable.incrementAndGet()
                        if (purpose == "pow_capture") after = { service.coordinator.acceptLine(event("browser", "unavailable")) }
                        else { mode = "manual"; after = { service.coordinator.acceptLine(event("manual")) } }
                    }
                    status = 204; ""
                }
                path == "/v1/auth/resume" -> {
                    resumes.incrementAndGet(); status = 204
                    after = { service.coordinator.acceptLine(CaptchaProtocol.PREFIX + JSONObject().put("v",1).put("type","auth")
                        .put("generation",generation).put("ipc_port",server.localPort).put("phase","resuming"))
                        Thread.sleep(200)
                        service.coordinator.acceptLine(CaptchaProtocol.PREFIX + JSONObject().put("v",1).put("type","auth")
                            .put("generation",generation).put("ipc_port",server.localPort).put("phase","authorized"))
                    }
                    ""
                }
                path == "/fixture-private-page" -> {
                    contentType = "text/html"
                    if (observerAsset != null) proofPage()
                    else "<html><body>Local browser lifecycle fixture<script>setInterval(()=>fetch('/heartbeat',{method:'POST'}),200);fetch('/heartbeat',{method:'POST'});</script></body></html>"
                }
                path == proofPath && observerAsset != null -> {
                    if (validProof(String(body))) {
                        proofs.incrementAndGet(); status = 204
                        after = { service.coordinator.acceptLine(event("browser", "captured")) }
                    } else status = 400
                    ""
                }
                path == "/heartbeat" -> { heartbeats.incrementAndGet(); status = 204; "" }
                else -> { status = 404; "{}" }
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            it.getOutputStream().apply {
                write("HTTP/1.1 $status OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
            after?.invoke()
        }
        override fun close() { server.close(); pool.shutdownNow() }
    }
}
