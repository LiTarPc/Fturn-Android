package com.freeturn.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.freeturn.app.domain.captcha.AuthorizationProgressWatchdog
import com.freeturn.app.domain.captcha.CaptchaProtocol
import com.freeturn.app.domain.captcha.CaptchaUiState
import com.freeturn.app.service.CaptchaPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Offline regression: silent child stdout must not keep RESUMING alive indefinitely. No VPN or VK requests. */
class AuthResumeFixtureInstrumentation : Instrumentation() {
    private var smokeRaw = false
    private lateinit var service: CaptchaFixtureService
    private fun await(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(20) }
        error(message)
    }
    private fun status(name: String) = sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); smokeRaw = arguments?.getString("smokeRAW") == "true"; start() }
    override fun onStart() {
        if (smokeRaw) { rawStartupSmoke(); return }
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var guard: AuthorizationProgressWatchdog? = null
        var child: Process? = null
        val reader = Executors.newSingleThreadExecutor()
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as CaptchaFixtureService.LocalBinder).service; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(targetContext, CaptchaFixtureService::class.java)
        try {
            runOnMainSync { targetContext.startForegroundService(intent); targetContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            check(bound.await(5, TimeUnit.SECONDS))
            var generation = ""
            runOnMainSync { generation = service.coordinator.begin(false, protocol = 2).getValue("FREETURN_RAW_CAPTCHA_GENERATION") }
            fun auth(phase: String) = CaptchaProtocol.PREFIX + JSONObject().put("v", 2).put("type", "auth").put("generation", generation).put("ipc_port", 12345).put("phase", phase)
            val process = ProcessBuilder("/system/bin/sleep", "20").redirectErrorStream(true).start(); child = process
            val eof = reader.submit<Boolean> { process.inputStream.bufferedReader().use { it.readLine() == null } }
            val restarts = AtomicInteger()
            guard = AuthorizationProgressWatchdog(scope, CaptchaPresentation.state, generation, { process.isAlive }, { false }, { error("No channel exists") }, { restarts.incrementAndGet(); process.destroyForcibly() }, timeoutMillis = 250)
            service.coordinator.acceptLine(auth("resuming"))
            await("silent core was not stopped") { !process.isAlive }
            check(eof.get(3, TimeUnit.SECONDS));check(restarts.get() == 1)
            status("resume_deadline_unblocks_silent_child_stdout")
            guard.close(); guard = null
            runOnMainSync { service.coordinator.end() }
            runOnMainSync { generation = service.coordinator.begin(false, protocol = 2).getValue("FREETURN_RAW_CAPTCHA_GENERATION") }
            service.coordinator.acceptLine(auth("resuming"))
            await("RESUMING not published") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.RESUMING }
            service.coordinator.connectionAvailable("stale-generation");Thread.sleep(100)
            check(CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.RESUMING)
            service.coordinator.connectionAvailable(generation)
            await("ready channel retained stale banner") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.IDLE }
            status("ready_channel_clears_banner_and_old_generation_is_ignored")
            service.coordinator.acceptLine(auth("waiting_user"))
            await("WAITING_USER not published") { CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.WAITING_USER }
            service.coordinator.connectionAvailable(generation);Thread.sleep(100)
            check(CaptchaPresentation.state.value.phase == CaptchaUiState.Phase.WAITING_USER)
            status("existing_channel_cannot_dismiss_explicit_user_wait")
            val generationCopy = generation
            guard = AuthorizationProgressWatchdog(scope, CaptchaPresentation.state, generationCopy, { true }, { false }, {}, { restarts.incrementAndGet() }, timeoutMillis = 200)
            Thread.sleep(350);check(restarts.get() == 1)
            status("explicit_user_wait_is_not_automatically_retried")
            result.putInt("tests_failed", 0);code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putInt("tests_failed", 1);result.putString("failure_type", e.javaClass.simpleName)
            result.putString("stream", "FAIL authorization recovery: ${e.javaClass.simpleName}\n")
        } finally {
            guard?.close();scope.cancel();child?.destroyForcibly();reader.shutdownNow()
            if (::service.isInitialized) runOnMainSync { service.coordinator.end() }
            runCatching { targetContext.unbindService(connection) };targetContext.stopService(intent)
        }
        finish(code, result)
    }
    private fun rawStartupSmoke() {
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        kotlinx.coroutines.runBlocking {
            val prefs = com.freeturn.app.data.AppPreferences(targetContext)
            val router = prefs.routerTunnelConfig()
            val hotspotEnabled = prefs.hotspotProxyEnabledFlow.firstValue()
            val manager = org.koin.core.context.GlobalContext.get().get<com.freeturn.app.domain.proxy.LocalProxyManager>()
            try {
                check(!com.freeturn.app.domain.proxy.ProxyServiceState.isRunning.value)
                val cfg = prefs.clientConfigFlow.firstValue()
                check(cfg.rawVpnActive)
                // A manual receiver cannot bind a USB address while this phone is connected only for ADB.
                prefs.setRouterTunnelConfig(router.copy(enabled = false))
                prefs.setHotspotProxyEnabled(true)
                kotlinx.coroutines.withTimeout(60_000) { manager.startProxy(cfg) }
                check(com.freeturn.app.domain.proxy.ProxyServiceState.tunnelActive.value)
                check(com.freeturn.app.domain.proxy.ProxyServiceState.startupResult.value is com.freeturn.app.domain.StartupResult.Success)
                status("real_RAW_startup_after_authorization_recovery_change")
                val hotspot = prefs.hotspotConfig()
                val socket = java.net.Socket("127.0.0.1", hotspot.port).apply { soTimeout = 10_000 }
                val input = socket.getInputStream(); val output = socket.getOutputStream()
                fun read(n: Int): ByteArray = ByteArray(n).also { b -> var at=0;while(at<n) {val count=input.read(b,at,n-at);check(count>0);at+=count} }
                output.write(byteArrayOf(5,1,2));check(read(2).contentEquals(byteArrayOf(5,2)))
                val user = "fturn".toByteArray();val password = hotspot.password.toByteArray()
                output.write(byteArrayOf(1,user.size.toByte())+user+byteArrayOf(password.size.toByte())+password);check(read(2).contentEquals(byteArrayOf(1,0)))
                val host = "example.com".toByteArray()
                output.write(byteArrayOf(5,1,0,3,host.size.toByte())+host+byteArrayOf(1,(-69).toByte()))
                val reply = read(4);check(reply[1].toInt()==0)
                val size = when(reply[3].toInt()) {1->4;4->16;3->read(1)[0].toInt() and 255;else->error("SOCKS response")};read(size+2)
                val tls = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(socket,"example.com",443,true) as javax.net.ssl.SSLSocket
                tls.use {
                    it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    it.startHandshake();it.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".toByteArray())
                    val response = it.getInputStream().bufferedReader().readText()
                    check(response.startsWith("HTTP/1.1 200") && response.contains("Example Domain"))
                }
                status("real_RAW_HTTPS_with_certificate_verification")
                result.putInt("tests_failed",0);code=Activity.RESULT_OK
            } catch(e: Throwable) {
                result.putInt("tests_failed",1);result.putString("failure_type",e.javaClass.simpleName)
            } finally {
                manager.stopProxy()
                prefs.setRouterTunnelConfig(router)
                prefs.setHotspotProxyEnabled(hotspotEnabled)
            }
        }
        finish(code,result)
    }
    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

}
