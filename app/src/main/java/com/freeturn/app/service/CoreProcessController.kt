package com.freeturn.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.DnsList
import com.freeturn.app.data.config.DnsMode
import com.freeturn.app.domain.proxy.DnsFallback
import com.freeturn.app.data.CoreArgs
import com.freeturn.app.domain.CaptchaSession
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.proxy.CoreConnectionTracker
import com.freeturn.app.domain.proxy.CoreLogEvent
import com.freeturn.app.domain.proxy.CoreLogParser
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.MAX_PROXY_RESTARTS
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.domain.proxy.SingBoxTunnelManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CoreProcessController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val notifier: ProxyNotifier,
    private val carrierDns: () -> String,
    private val onStopRequested: () -> Unit,
) {
    companion object {
        // Даём TURN-туннелю "устаканиться" перед поднятием WireGuard поверх него.
        private const val VPN_START_DELAY_MS = 2_000L
    }

    private val singBox = SingBoxTunnelManager(context)
    private val handler = Handler(Looper.getMainLooper())

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    // Single-flight: двойной старт (tile+UI/watchdog) затёр бы первый процесс -> зомби.
    private val startInFlight = AtomicBoolean(false)
    @Volatile private var sessionJob: Job? = null

    val isRunning: Boolean get() = startInFlight.get() || process.get() != null
    val isUserStopped: Boolean get() = userStopped.get()

    fun start() {
        notifier.resetDnsAlerts()
        userStopped.set(false)
        restartCount.set(0)
        launchProcessSession()
    }

    fun onNetworkHandover() {
        if (userStopped.get() || process.get() == null) return
        notifier.resetDnsAlerts()
        ProxyServiceState.addLog("Смена сети - переподключение")
        notifier.setStatus(context.getString(R.string.notif_proxy_network_change))
        restartCount.set(0)
        process.get()?.destroyCompat()
    }

    fun beginShutdown() {
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
    }

    fun destroyProcessAndTunnel() {
        process.get()?.destroyCompat()
        Thread {
            try {
                runBlocking {
                    // Joining also waits for a ProcessBuilder start that was already in flight.
                    try { sessionJob?.cancelAndJoin() } finally { singBox.stop() }
                }
            } finally {
                ProxyServiceState.markTeardownComplete()
            }
        }.start()
    }

    private fun launchProcessSession() {
        if (userStopped.get() || !startInFlight.compareAndSet(false, true)) return
        // Publish the job before it can run or shutdown can miss it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!ProxyServiceState.awaitTeardown()) {
                    val message = "Предыдущее VPN-подключение ещё останавливается"
                    ProxyServiceState.addLog(message)
                    ProxyServiceState.setStartupResult(StartupResult.Failed(message))
                    onStopRequested()
                    return@launch
                }
                runProcessSession()
            } finally {
                startInFlight.set(false)
            }
        }
        sessionJob = job
        job.start()
    }

    private suspend fun runProcessSession() {
        if (userStopped.get()) return
        ProxyServiceState.setStartupResult(null)

        val cfg = prefs.clientConfigFlow.first()
        if (cfg.tunnelTransport != com.freeturn.app.data.config.TunnelTransport.NONE) {
            val valid = runCatching {
                if (cfg.rawVpnActive) com.freeturn.app.data.config.RawVpnConfig.validate(cfg)
                else com.freeturn.app.data.config.SingBoxConfig.fromClient(cfg)
                check(android.net.VpnService.prepare(context) == null) { "Откройте приложение и разрешите VPN" }
            }
            if (valid.isFailure) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(valid.exceptionOrNull()?.message ?: "Некорректный VPN"))
                ProxyServiceState.setRunning(false)
                onStopRequested()
                return
            }
        }
        ProxyServiceState.setLogsEnabled(cfg.logsEnabled)
        val srv = prefs.serverOptsFlow.first()
        com.freeturn.app.domain.diagnostics.DiagnosticLogs.registerSecrets(buildList {
            add(srv.obfKey)
            add(prefs.hotspotConfigFlow.first().password)
            add(cfg.clientId)
            add(prefs.ownClientId())
            addAll(cfg.vkLink.split(','))
            add(cfg.vlessUri)
            runCatching { com.freeturn.app.data.config.VlessProfile.parse(cfg.vlessUri).uuid }
                .getOrNull()?.let(::add)
            Regex("(?im)^\\s*(?:PrivateKey|PresharedKey|PublicKey)\\s*=\\s*(\\S+)")
                .findAll(cfg.wireGuardConfig).forEach { add(it.groupValues[1]) }
        })
        if (cfg.logsEnabled) com.freeturn.app.domain.diagnostics.DiagnosticLogs.append(
            "[PROFILE] transport=${cfg.tunnelTransport} server=${cfg.serverAddress} " +
                "streams=${cfg.threads} per_credential=${cfg.streamsPerCred} " +
                "turn_transport=${if (cfg.useUdp) "udp" else "tcp"} " +
                "raw_uplink=${cfg.rawUplink} raw_mtu=${cfg.rawMtu} bypass=${cfg.bypassRulesEnabled}"
        )
        val privacy = prefs.privacyModeFlow.first()

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val executableFile = File(libDir, if (cfg.rawVpnActive) "libfturnraw.so" else "libfreeturn.so")
            .takeIf { it.isFile }

        if (executableFile == null) {
            ProxyServiceState.addLog(
                "Ядро ${if (cfg.rawVpnActive) "libfturnraw.so" else "libfreeturn.so"} не найдено в ${libDir.path}. " +
                "Положите бинарник в jniLibs/arm64-v8a/ (имя начинается с lib и оканчивается на .so)."
            )
            ProxyServiceState.setStartupResult(StartupResult.Failed("core binary not found"))
            ProxyServiceState.setRunning(false)
            onStopRequested()
            return
        }
        val executable = executableFile.absolutePath

        val carrierSelected = !cfg.isRawMode && cfg.useCarrierDns &&
            DnsList.normalize(cfg.customDns).isBlank() && cfg.dnsMode != DnsMode.DOH
        val selectedCarrierDns = if (carrierSelected) carrierDns().takeIf { it.isNotBlank() } else null
        val cmdArgs = mutableListOf<String>()

        if (cfg.rawVpnActive) {
            val rawArgs = runCatching {
                com.freeturn.app.data.config.RawVpnConfig.args(cfg, srv, prefs.ownClientId(),
                    selectedCarrierDns)
            }.getOrElse {
                ProxyServiceState.setStartupResult(StartupResult.Failed(it.message ?: "Invalid RAW profile"))
                ProxyServiceState.setRunning(false)
                onStopRequested()
                return
            }
            cmdArgs.add(executable)
            cmdArgs.addAll(rawArgs)
        } else if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            cmdArgs.addAll(CoreArgs.client(cfg, srv, selectedCarrierDns, prefs.ownClientId()))
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var vpnStarted = false
        var captchaSessionCounter = 0L

        val tracker = CoreConnectionTracker(
            udpTotal = if (cfg.rawVpnActive) cfg.threads * cfg.vkLink.split(',').size else if (cfg.isRawMode) 0 else if (cfg.threads > 0) cfg.threads else 1,
            tcpMode = cfg.coreTcpForward
        )

        fun publishStats() {
            ProxyServiceState.setConnectionStats(ConnectionStats(tracker.active, tracker.total))
            notifier.refreshStats()
        }
        publishStats()
        try {
            ProxyServiceState.addLog("Команда: ${CoreArgs.redactForLog(cmdArgs, privacy)}")

            // Reserve the bridge credentials without creating a TUN. The RAW core opens
            // its SOCKS listener first; Android traffic is admitted only after a stream is ready.
            val rawBackend = if (cfg.rawVpnActive) {
                com.freeturn.app.data.RuleDownloadTunnel.prepare(setOf(prefs.hotspotConfig().port))
            } else null
            val proc = withContext(Dispatchers.IO + NonCancellable) {
                // Легаси: сносим осиротевшие профили захвата (сам механизм захвата удалён).
                context.filesDir.listFiles { f -> f.name.startsWith("vk_profile") }?.forEach { it.delete() }
                val pb = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                // CWD подменяем на writeable dir для логов кэша tls-client и т.п.
                pb.directory(context.filesDir)
                if (cfg.rawVpnActive) {
                    val backend = checkNotNull(rawBackend)
                    pb.environment()["FREETURN_RAW_SOCKS_PORT"] = backend.port.toString()
                    pb.environment()["FREETURN_RAW_SOCKS_PASSWORD"] = backend.password
                }
                pb.start().also {
                    // Register before returning across a cancellable dispatcher boundary.
                    process.set(it)
                    if (userStopped.get()) it.destroyCompat()
                }
            }
            if (carrierSelected && !userStopped.get()) {
                if (selectedCarrierDns == null) {
                    ProxyServiceState.addLog(context.getString(R.string.notif_dns_carrier_missing))
                    notifier.showDnsFallback(DnsFallback.CARRIER_UNAVAILABLE)
                } else {
                    ProxyServiceState.addLog(context.getString(R.string.log_dns_carrier_selected, selectedCarrierDns))
                }
            }
            // Stop в окне старта: destroyProcessAndTunnel видел ещё null - убиваем сами.
            if (userStopped.get()) {
                proc.destroyCompat()
            }

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (true) {
                    line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        // При остановке процесса pipe закрывается, readLine() бросает IOException.
                        val msg = e.message.orEmpty()
                        val benign = userStopped.get() ||
                            msg.contains("interrupted by close", ignoreCase = true) ||
                            msg.contains("Stream closed", ignoreCase = true) ||
                            msg.contains("Bad file descriptor", ignoreCase = true)
                        if (!benign) {
                            ProxyServiceState.addLog("Чтение лога ядра прервано: ${e.message}")
                        }
                        null
                    }
                    if (line == null) break
                    val l = line
                    ProxyServiceState.addLog(l)

                    val events = CoreLogParser.parse(l)
                    var statsChanged = false
                    for (event in events) when (event) {
                        is CoreLogEvent.CaptchaUrl -> {
                            captchaSessionCounter += 1
                            ProxyServiceState.setCaptchaSession(
                                CaptchaSession(event.url, captchaSessionCounter)
                            )
                            notifier.showCaptcha()
                        }
                        CoreLogEvent.DnsFallbackToDoh -> {
                            if (!userStopped.get()) notifier.showDnsFallback(DnsFallback.DOH, carrierSelected)
                        }
                        CoreLogEvent.CaptchaResolved -> {
                            if (ProxyServiceState.captchaSession.value != null) {
                                ProxyServiceState.setCaptchaSession(null)
                                notifier.cancelCaptcha()
                            }
                        }
                        else -> if (tracker.apply(event)) statsChanged = true
                    }
                    if (statsChanged) publishStats()

                    if (!startupEmitted) {
                        val hasFatal = events.any { it is CoreLogEvent.FatalStartup }
                        val hasConnection = tracker.hasConnection
                        when {
                            hasFatal -> {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                notifier.setStatus(context.getString(R.string.notif_proxy_connect_error))
                                startupFailed = true
                                startupEmitted = true
                            }
                            hasConnection -> {
                                try {
                                    if (cfg.vpnActive && !cfg.rawVpnActive) {
                                        ProxyServiceState.addLog(
                                            "VPN: подъём через ${VPN_START_DELAY_MS} мс после старта TURN-туннеля"
                                        )
                                        delay(VPN_START_DELAY_MS)
                                        if (userStopped.get() || process.get() !== proc) {
                                            ProxyServiceState.addLog(
                                                "VPN: старт отменён, прокси останавливается"
                                            )
                                            break
                                        }
                                    }
                                    singBox.startAfterProxyReady(cfg, rawBackend)
                                    vpnStarted = cfg.vpnActive
                                    if (userStopped.get() || process.get() !== proc) break
                                    ProxyServiceState.setStartupResult(StartupResult.Success)
                                    ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                                    notifier.setStatus(
                                        if (vpnStarted) context.getString(R.string.tunnel_active)
                                        else context.getString(R.string.proxy_active),
                                        active = true
                                    )
                                } catch (e: Exception) {
                                    val message = e.message ?: e.javaClass.simpleName
                                    ProxyServiceState.addLog("VPN: ошибка запуска - $message")
                                    ProxyServiceState.setStartupResult(
                                        StartupResult.Failed("VPN не запустился: $message")
                                    )
                                    notifier.setStatus(context.getString(R.string.notif_proxy_vpn_error))
                                    startupFailed = true
                                    proc.destroyCompat()
                                }
                                startupEmitted = true
                            }
                        }
                    }

                    // TURN allocation retries belong to the core. A quota error from one
                    // session must not discard healthy sessions and authorization state.
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitForCompat(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog("Процесс остановлен (код $exitCode)")
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)"))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Исключения при пользовательской остановке - следствие закрытия пайпов.
            if (userStopped.get()) {
                startupFailed = false
            } else {
                ProxyServiceState.addLog("Ошибка: ${e.message}")
            }
        } finally {
            withContext(NonCancellable) {
                ProxyServiceState.setCaptchaSession(null)
                notifier.cancelCaptcha()
                try {
                    if (vpnStarted) singBox.stop()
                } finally {
                    ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
                    process.getAndSet(null)?.let { previous ->
                        previous.destroyCompat()
                        withContext(Dispatchers.IO) { previous.waitForCompat(5, TimeUnit.SECONDS) }
                    }
                }
                when {
                    userStopped.get() -> {
                        ProxyServiceState.setRunning(false)
                        onStopRequested()
                    }
                    startupFailed -> {
                        ProxyServiceState.addLog("Ошибка при запуске, watchdog не активирован")
                        ProxyServiceState.setRunning(false)
                        onStopRequested()
                    }
                    exitCode == 0 -> {
                        val uptime = System.currentTimeMillis() - startedAt
                        if (uptime < 5_000L) {
                            ProxyServiceState.addLog("Быстрый выход (${uptime} мс) - проверьте ссылку и настройки")
                        } else {
                            ProxyServiceState.addLog("Сессия завершена")
                        }
                        ProxyServiceState.setRunning(false)
                        onStopRequested()
                    }
                    else -> scheduleWatchdogRestart()
                }
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_PROXY_RESTARTS) {
            ProxyServiceState.addLog("Watchdog: превышен лимит попыток ($MAX_PROXY_RESTARTS), остановка")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            onStopRequested()
            return
        }
        val baseDelay = minOf(1_000L * count, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delayMs = baseDelay + jitter
        ProxyServiceState.addLog("Watchdog: перезапуск через ${delayMs} мс (попытка $count/$MAX_PROXY_RESTARTS)")
        notifier.setStatus(context.getString(R.string.notif_proxy_reconnecting, count, MAX_PROXY_RESTARTS))
        handler.postDelayed({
            if (!userStopped.get()) launchProcessSession()
        }, delayMs)
    }

}
