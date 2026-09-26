package com.freeturn.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.CoreConfig
import com.freeturn.app.data.DnsList
import com.freeturn.app.domain.CaptchaSession
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.MAX_PROXY_RESTARTS
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.domain.proxy.WireGuardTunnelManager
import com.freeturn.core.mobile.EventSink
import com.freeturn.core.mobile.Mobile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Owns the in-process gomobile core for the lifetime of the foreground service. */
class CoreProcessController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val notifier: ProxyNotifier,
    private val carrierDns: () -> String,
    private val onStopRequested: () -> Unit,
) {
    private val wireGuard = WireGuardTunnelManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private val nativeLock = Any()
    private val userStopped = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private val startInFlight = AtomicBoolean(false)
    private val nativeStarted = AtomicBoolean(false)
    private val quotaReconnectScheduled = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    private var eventSink: EventSink? = null // JNI does not keep the Java object alive.

    val isRunning: Boolean get() = sessionActive.get()
    val isUserStopped: Boolean get() = userStopped.get()

    fun start() {
        if (!sessionActive.compareAndSet(false, true)) return
        userStopped.set(false)
        restartCount.set(0)
        scope.launch { runSession() }
    }

    fun onNetworkHandover() {
        if (userStopped.get() || !nativeStarted.get()) return
        ProxyServiceState.addLog("����� ���� � ��������������� ����")
        notifier.setStatus(context.getString(R.string.notif_proxy_network_change))
        scope.launch {
            try {
                val cfg = prefs.clientConfigFlow.first()
                if (cfg.useCarrierDns && cfg.customDns.isBlank()) {
                    Mobile.setDNSServers(DnsList.normalize(carrierDns()))
                }
                Mobile.reconnect()
            } catch (e: Exception) {
                ProxyServiceState.addLog("������ ���������������: ${e.message}")
            }
        }
    }

    fun beginShutdown() {
        userStopped.set(true)
        sessionActive.set(false)
        handler.removeCallbacksAndMessages(null)
    }

    fun destroyProcessAndTunnel() {
        Thread {
            try {
                synchronized(nativeLock) {
                    runCatching { Mobile.stop() }
                    runCatching { Mobile.setEventSink(null) }
                    nativeStarted.set(false)
                    eventSink = null
                }
                runBlocking { wireGuard.stop() }
            } catch (e: Exception) {
                ProxyServiceState.addLog("������ ��������� ����: ${e.message}")
            } finally {
                ProxyServiceState.markTeardownComplete()
            }
        }.start()
    }

    private suspend fun runSession() {
        if (userStopped.get() || !startInFlight.compareAndSet(false, true)) return
        var connected = false
        var startupFailed = false
        var wireGuardStarted = false
        try {
            val cfg = prefs.clientConfigFlow.first()
            val srv = prefs.serverOptsFlow.first()
            val privacy = prefs.privacyModeFlow.first()
            ProxyServiceState.setLogsEnabled(cfg.logsEnabled)
            if (cfg.bond) ProxyServiceState.addLog("����� Bond ���������� � ���� v4.1.2")
            val config = CoreConfig.client(cfg, srv, carrierDns(), prefs.ownClientId())
            ProxyServiceState.addLog("���� AAR v${Mobile.version()}; ����� ${if (cfg.tcpForward) "TCP" else "UDP"}" +
                if (privacy) "" else "; ������ ${cfg.serverAddress}")
            Mobile.setStateDir(context.filesDir.absolutePath)
            eventSink = createEventSink()
            Mobile.setEventSink(eventSink)
            val validation = Mobile.validateConfig(config)
            require(validation.isBlank()) { validation }
            synchronized(nativeLock) {
                if (!userStopped.get()) {
                    Mobile.start(config)
                    nativeStarted.set(true)
                }
            }
            if (userStopped.get() || !nativeStarted.get()) return

            while (!userStopped.get()) {
                val snapshot = Mobile.getState()
                publishStats(snapshot.streams, snapshot.total)
                when (snapshot.state) {
                    Mobile.StateConnected -> if (!connected) {
                        if (cfg.wireGuardActive) {
                            delay(2_000L)
                            if (userStopped.get()) break
                            if (Mobile.getState().state != Mobile.StateConnected) continue
                            wireGuard.startAfterProxyReady(cfg)
                            wireGuardStarted = true
                        }
                        connected = true
                        ProxyServiceState.setStartupResult(StartupResult.Success)
                        ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                        notifier.setStatus(
                            context.getString(if (wireGuardStarted) R.string.tunnel_active else R.string.proxy_active),
                            active = true,
                        )
                    }
                    Mobile.StateError -> error(snapshot.errMsg.ifBlank { "���� ����������� � �������" })
                    Mobile.StateIdle -> error("���� ��������� ������")
                }
                delay(500L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!userStopped.get()) {
                val message = e.message ?: e.javaClass.simpleName
                ProxyServiceState.addLog("������ ����: $message")
                if (!connected) {
                    ProxyServiceState.setStartupResult(StartupResult.Failed(message))
                    notifier.setStatus(context.getString(R.string.notif_proxy_connect_error))
                    startupFailed = true
                }
            }
        } catch (e: LinkageError) {
            if (!userStopped.get()) {
                val message = e.message ?: "�� ������� ��������� ���������� ����"
                ProxyServiceState.addLog(message)
                ProxyServiceState.setStartupResult(StartupResult.Failed(message))
                startupFailed = true
            }
        } finally {
            withContext(NonCancellable) {
                synchronized(nativeLock) {
                    if (nativeStarted.getAndSet(false)) runCatching { Mobile.stop() }
                    runCatching { Mobile.setEventSink(null) }
                    eventSink = null
                }
                if (wireGuardStarted && !userStopped.get()) wireGuard.stop()
            }
            ProxyServiceState.setCaptchaSession(null)
            notifier.cancelCaptcha()
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            startInFlight.set(false)
            when {
                userStopped.get() -> {
                    sessionActive.set(false)
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                startupFailed -> {
                    sessionActive.set(false)
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    private fun createEventSink(): EventSink = object : EventSink {
        override fun onState(state: String, streams: Long, total: Long, errMsg: String) {
            if (!userStopped.get()) publishStats(streams, total)
        }

        override fun onCaptcha(url: String) {
            if (userStopped.get()) return
            if (url.isBlank()) {
                ProxyServiceState.setCaptchaSession(null)
                notifier.cancelCaptcha()
            } else {
                ProxyServiceState.setCaptchaSession(CaptchaSession(url, SystemClock.elapsedRealtime()))
                notifier.showCaptcha()
            }
        }

        override fun onLog(level: String, msg: String, unixMillis: Long) {
            if (userStopped.get()) return
            ProxyServiceState.addLog("[$level] $msg")
            if (msg.contains("quota", ignoreCase = true) &&
                quotaReconnectScheduled.compareAndSet(false, true)) {
                handler.postDelayed({
                    quotaReconnectScheduled.set(false)
                    if (!userStopped.get() && nativeStarted.get()) Mobile.reconnect()
                }, 2_000L)
            }
        }
    }

    private fun publishStats(streams: Long, total: Long) {
        ProxyServiceState.setConnectionStats(
            ConnectionStats(streams.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        )
        notifier.refreshStats()
    }

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_PROXY_RESTARTS) {
            sessionActive.set(false)
            ProxyServiceState.addLog("Watchdog: �������� ����� ������� ($MAX_PROXY_RESTARTS)")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            onStopRequested()
            return
        }
        val delayMs = minOf(1_000L * count, 30_000L) + Random.nextLong(0, 500)
        ProxyServiceState.addLog("Watchdog: ���������� ����� ${delayMs} �� ($count/$MAX_PROXY_RESTARTS)")
        notifier.setStatus(context.getString(R.string.notif_proxy_reconnecting, count, MAX_PROXY_RESTARTS))
        handler.postDelayed({ if (!userStopped.get()) scope.launch { runSession() } }, delayMs)
    }
}
