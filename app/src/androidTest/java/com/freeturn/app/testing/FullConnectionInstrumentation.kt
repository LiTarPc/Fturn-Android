package com.freeturn.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.freeturn.app.MainActivity
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.*
import com.freeturn.app.data.server.*
import com.freeturn.app.data.share.FreeturnLink
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.LocalProxyManager
import com.freeturn.app.domain.proxy.ProxyServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File

/** Explicitly invoked integration runner. Credentials are read from private app files, never APK assets. */
class FullConnectionInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    private fun status(text: String) = sendStatus(0, Bundle().apply { putString("stream", text + "\n") })
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    override fun onStart() {
        val ctx = targetContext
        val label = arguments.getString("profile", "RAW")
        val reports = JSONArray()
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = AppPreferences(ctx)
        var manager: LocalProxyManager? = null
        var initialActive: String? = null
        var initialHotspot: HotspotConfig? = null
        var starting: Job? = null
        try { runBlocking {
            check(io.nekohasekai.libbox.Libbox.version() == "1.14.4")
            if (label.endsWith("-srs")) {
                val nativeHTTP = io.nekohasekai.libbox.Libbox.newHTTPClient()
                try {
                    val request = nativeHTTP.newRequest()
                    request.setURL("https://example.com/")
                    check(request.execute().content.value.contains("Example Domain")) { "Native Android TLS trust failed" }
                    status("PASS native Android TLS certificate verification")
                } finally { nativeHTTP.close() }
            }
            val input = JSONObject(File(ctx.filesDir, "full-check-private.json").readText())
            val profiles = input.getJSONArray("profiles")
            val item = (0 until profiles.length()).map { profiles.getJSONObject(it) }.single { it.getString("label") == label }
            val source = if (item.has("serverJson")) ServerJson.decodeListStrict(JSONArray().put(item.getJSONObject("serverJson")).toString()).single()
                else server(FreeturnLink.parse(item.getString("shareLink")).getOrThrow(), label)
            val original = prefs.serversSnapshot.first()
            initialActive = original.activeId
            initialHotspot = prefs.hotspotConfig()
            prefs.setHotspotConfig(11080, input.getString("hotspotPassword"))
            prefs.setHotspotProxyEnabled(true)
            prefs.setPrivacyMode(true)
            prefs.setSuppressUpdatePrompt(true)
            prefs.setSuppressTgPrompt(true)
            prefs.setBatteryPromptShown()
            prefs.setBrowserCaptcha(true)
            val id = "qa-sb1144-" + label.lowercase()
            prefs.deleteServer(id)
            val suppliedRules = when {
                label.endsWith("-srs") -> BypassRuleSet.defaults() + BypassRuleSet.fromBytes("category-ru.srs", File(ctx.filesDir, "full-check-category-ru.srs").readBytes())
                label.endsWith("-control") -> listOf(BypassRuleSet.fromBytes("controlled.zone", (input.getJSONObject("control").getString("host") + "/32").toByteArray()))
                else -> source.client.bypassRuleSets
            }
            val base = source.copy(id = id, name = "QA " + label, client = source.client.copy(bypassRuleSets = suppliedRules,
                splitTunnelMode = SplitTunnelMode.EXCLUDE, splitTunnelUseDefaults = false, splitTunnelApps = ""))
            prefs.addServer(base, activate = true)
            check(ServerJson.decodeListStrict(ServerJson.encodeList(listOf(base))).single() == base) { "Profile persistence roundtrip" }
            status("PASS $label import and persistence")
            val activity = startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            manager = GlobalContext.get().get<LocalProxyManager>()
            val cycles = arguments.getString("cycles", "2").toInt().coerceIn(1, 3)
            repeat(cycles) { cycle ->
                val bypass = arguments.getString("bypass")?.toBooleanStrictOrNull() ?: (cycle > 0)
                prefs.updateServer(id) { it.copy(client = it.client.copy(bypassRulesEnabled = bypass)) }
                prefs.setActiveServerId(id)
                val cfg = prefs.clientConfigFlow.first()
                val report = JSONObject().put("profile", label).put("cycle", cycle+1).put("bypass", bypass)
                reports.put(report)
                status("START $label cycle=${cycle+1} bypass=$bypass")
                val start = android.os.SystemClock.elapsedRealtime()
                starting = scope.launch { manager!!.startProxy(cfg) }
                var lastProgress = 0L
                while (android.os.SystemClock.elapsedRealtime()-start < 180_000) {
                    val elapsed = android.os.SystemClock.elapsedRealtime()-start
                    if (ProxyServiceState.tunnelActive.value && ProxyServiceState.startupResult.value is StartupResult.Success) break
                    if (ProxyServiceState.startupResult.value is StartupResult.Failed) error("Core startup failed; see sanitized diagnostics")
                    if (elapsed-lastProgress >= 15_000) {
                        val stats = ProxyServiceState.connectionStats.value
                        status("WAIT $label seconds=${elapsed/1000} active=${stats.active} captcha=${ProxyServiceState.captchaUi.value.phase}")
                        lastProgress = elapsed
                    }
                    delay(250)
                }
                check(ProxyServiceState.tunnelActive.value) { "No active VPN within 180 seconds" }
                report.put("startupMillis", android.os.SystemClock.elapsedRealtime()-start)
                status("PASS $label connected cycle=${cycle+1}")
                val notices = ctx.getSystemService(NotificationManager::class.java).activeNotifications
                report.put("singleVpnNotification", notices.count { it.id == 1 } == 1)
                val before = ProxyServiceState.trafficSnapshot.value
                for (mode in listOf("tun", "socks")) {
                    if (label.endsWith("-control") && mode == "tun") continue // Loopback never enters Android TUN.
                    val command = if (mode == "socks") "/system/bin/env FTURN_SOCKS_PASSWORD=${input.getString("hotspotPassword")} /data/local/tmp/fturn-sb1144-network-probe -scope socks -socks 127.0.0.1:11080"
                        else "/data/local/tmp/fturn-sb1144-network-probe -scope tun"
                    status("RUN $label $mode cycle=${cycle+1}")
                    val extra = if (label.endsWith("-control")) {
                        val control = input.getJSONObject("control")
                        " -lan-check http://${control.getString("host")}:${control.getInt("port")}/ -marker ${control.getString("marker")}" + if (bypass) " -want-lan" else ""
                    } else if (arguments.getString("quick") == "true") " -egress-only" else ""
                    val output = shell(command + extra)
                    val network = JSONObject(output.substring(output.indexOf('{')).trim())
                    report.put(mode, network)
                    val checks = network.getJSONArray("checks")
                    for (i in 0 until checks.length()) {
                        val check = checks.getJSONObject(i)
                        status("${if(check.getBoolean("ok")) "PASS" else "FAIL"} $label $mode ${check.getString("name")}")
                    }
                }
                runOnMainSync { activity.moveTaskToBack(true) }
                delay(5000)
                val after = ProxyServiceState.trafficSnapshot.value
                report.put("trafficRxAdvanced", after.rxBytes > before.rxBytes).put("trafficTxAdvanced", after.txBytes > before.txBytes)
                report.put("activeInBackground", ProxyServiceState.tunnelActive.value && ProxyServiceState.isRunning.value)
                val ready = File(ctx.filesDir, "full-check-ready")
                val proceed = File(ctx.filesDir, "full-check-continue")
                proceed.delete()
                ready.writeText("$label:${cycle+1}")
                status("READY_EXTERNAL_SOCKS $label cycle=${cycle+1}")
                val deadline = android.os.SystemClock.elapsedRealtime()+180_000
                while (!proceed.exists() && android.os.SystemClock.elapsedRealtime()<deadline) delay(250)
                proceed.delete(); ready.delete()
                manager!!.stopProxy()
                starting?.cancelAndJoin()
                report.put("stopped", ProxyServiceState.awaitTeardown() && !ProxyServiceState.tunnelActive.value)
                status("PASS $label stopped cycle=${cycle+1}")
                File(ctx.filesDir, "full-check-report-$label.json").writeText(reports.toString(2))
            }
            code = Activity.RESULT_OK
        } } catch (e: Throwable) {
            status("FAIL $label ${e.javaClass.simpleName}: ${e.message}")
            result.putString("error", e.javaClass.simpleName)
        } finally {
            runBlocking {
                runCatching { manager?.stopProxy() }
                starting?.cancelAndJoin()
                runCatching { ProxyServiceState.awaitTeardown() }
                initialHotspot?.let { old -> runCatching { prefs.setHotspotConfig(old.port, old.password); prefs.setHotspotProxyEnabled(old.enabled) } }
                runCatching { prefs.setActiveServerId(initialActive) }
            }
            scope.cancel()
            File(ctx.filesDir, "full-check-report-$label.json").writeText(reports.toString(2))
            File(ctx.filesDir, "full-check-diagnostics-$label.log").writeText(ProxyServiceState.logs.value.joinToString("\n") { it.text })
            status("FINISHED $label")
        }
        finish(code, result)
    }
    private fun server(link: FreeturnLink, label: String) = Server(name = label,
        client = ClientConfig(serverAddress = link.peer, vkLink = link.links, provider = link.provider,
            tcpForward = link.mode == "tcp" || link.sbUri.isNotBlank(), bond = link.bond, useUdp = link.transport == "udp",
            threads = link.n.takeIf { it > 0 } ?: ClientConfig.DEFAULT_THREADS,
            streamsPerCred = link.streamsPerCred.takeIf { it > 0 } ?: ClientConfig.DEFAULT_STREAMS_PER_CRED,
            rawMtu = link.rawMtu, rawUplink = link.rawUplink, bypassRulesEnabled = !link.rawVpn,
            tunnelTransport = if (link.rawVpn) TunnelTransport.RAW else if (link.sbUri.isNotBlank()) TunnelTransport.VLESS else if (link.wgConf.isNotBlank()) TunnelTransport.WIREGUARD else TunnelTransport.NONE,
            vlessUri = link.sbUri, localPort = link.listen.ifBlank { ClientConfig.DEFAULT_LOCAL_PORT },
            wireGuardConfig = link.wgConf, clientId = link.clientId.trim()),
        opts = ServerOpts(obfProfile = link.obfProfile.ifBlank { ObfProfile.NONE }, obfKey = link.obfKey))
}
