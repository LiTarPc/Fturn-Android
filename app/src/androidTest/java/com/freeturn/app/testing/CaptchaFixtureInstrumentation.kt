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
            runCatching { main { if (::service.isInitialized) service.coordinator.end() } }
            connection?.let { runCatching { targetContext.unbindService(it) } }
            runCatching { targetContext.stopService(Intent(targetContext, CaptchaFixtureService::class.java)) }
        }
        finish(resultCode, result)
    }

    private inner class Fixture : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        @Volatile var generation = ""
        @Volatile private var token = ""
        @Volatile var requestId = UUID.randomUUID().toString().replace("-", "")
        @Volatile private var mode = "browser"
        @Volatile private var expiry = System.currentTimeMillis() + 60_000
        val heartbeats = AtomicInteger()
        val unavailable = AtomicInteger()
        val resumes = AtomicInteger()
        init { pool.execute { while (!server.isClosed) runCatching { val socket = server.accept(); pool.execute { handle(socket) } } } }
        fun begin() {
            main {
                val env = service.coordinator.begin(true)
                generation = env.getValue("FREETURN_RAW_CAPTCHA_GENERATION")
                token = env.getValue("FREETURN_RAW_CAPTCHA_TOKEN")
                requestId = UUID.randomUUID().toString().replace("-", "")
            }
        }
        fun event(mode: String, phase: String = "pending") = CaptchaProtocol.PREFIX + JSONObject()
            .put("v", 1).put("type", "captcha").put("generation", generation).put("ipc_port", server.localPort)
            .put("request_id", requestId).put("mode", mode).put("phase", phase).put("expires_at_ms", expiry)
        fun pending(next: String, ttl: Long = 60_000) {
            mode = next; expiry = System.currentTimeMillis() + ttl
            service.coordinator.acceptLine(event(mode))
        }
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
                    .put("mode", mode).put("expires_at_ms", expiry).put("url", "http://127.0.0.1:${server.localPort}/fixture-private-page").toString()
                path == "/v1/captcha/$requestId/host" -> {
                    val data = JSONObject(String(body))
                    if (data.optString("status") in setOf("unavailable", "manual_requested") && data.optString("reason") != "challenge_expired") {
                        if (data.optString("status") == "unavailable") unavailable.incrementAndGet()
                        mode = "manual"; after = { service.coordinator.acceptLine(event("manual")) }
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
                    "<html><body>Local browser lifecycle fixture<script>setInterval(()=>fetch('/heartbeat',{method:'POST'}),200);fetch('/heartbeat',{method:'POST'});</script></body></html>"
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
