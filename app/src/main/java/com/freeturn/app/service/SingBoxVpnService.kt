package com.freeturn.app.service

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.SingBoxConfig
import com.freeturn.app.data.config.VpnLogRedactor
import com.freeturn.app.data.config.splitTunnelSelection
import com.freeturn.app.data.isPackageInstalled
import com.freeturn.app.data.installedInternetPackages
import com.freeturn.app.domain.proxy.ProxyServiceState
import io.nekohasekai.libbox.*
import org.koin.android.ext.android.inject
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections

/** The only owner of the VPN TUN descriptor and native BoxService. */
class SingBoxVpnService : VpnService(), PlatformInterface {
    inner class LocalBinder : Binder() { val service get() = this@SingBoxVpnService }
    private val binder = LocalBinder()
    private var box: CommandServer? = null
    private var logClient: CommandClient? = null
    private var ruleDownloadEndpoint: com.freeturn.app.data.RuleDownloadTunnel.Endpoint? = null
    private var tun: ParcelFileDescriptor? = null
    private val prefs: com.freeturn.app.data.AppPreferences by inject()
    private var config: ClientConfig? = null
    private var logRedactor: VpnLogRedactor? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)

    override fun onCreate() {
        super.onCreate()
        val notifier = ProxyNotifier(this)
        notifier.createChannels()
        notifier.prepareConnecting()
        startForeground(ProxyNotifier.NOTIF_ID_FG, notifier.build())
    }

    override fun onBind(intent: Intent): android.os.IBinder? = if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    @Synchronized
    fun startTunnel(cfg: ClientConfig, rawBackend: com.freeturn.app.data.RuleDownloadTunnel.Endpoint? = null) {
        check(prepare(this) == null) { "Требуется разрешение Android VPN" }
        stopTunnel()
        config = cfg
        logRedactor = VpnLogRedactor(cfg)
        try {
            val hotspot = kotlinx.coroutines.runBlocking { prefs.hotspotConfig() }
            if (cfg.rawVpnActive) {
                com.freeturn.app.data.config.RawVpnConfig.validate(cfg)
                requireNotNull(rawBackend) { "RAW backend is not prepared" }
            }
            Libbox.setup(SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = filesDir.resolve("sing-box").absolutePath
                tempPath = cacheDir.resolve("sing-box").absolutePath
                fixAndroidStack = true
            })
            val sets = if (cfg.bypassRulesEnabled) com.freeturn.app.data.BypassRuleStore(this).materialize(cfg.bypassRuleSets) else org.json.JSONArray()
            val download = com.freeturn.app.data.RuleDownloadTunnel.prepare(setOf(hotspot.port, rawBackend?.port ?: 0))
            ruleDownloadEndpoint = download
            val json = hotspot.configure(com.freeturn.app.data.RuleDownloadTunnel.configure(
                SingBoxConfig.fromClient(cfg, sets, rawBackend), download))
            box = Libbox.newCommandServer(object : CommandServerHandler {
                override fun serviceStop() { stopService(Intent(this@SingBoxVpnService, ProxyService::class.java)) }
                override fun serviceReload() { throw UnsupportedOperationException("Reconnect to reload the VPN") }
                override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
                override fun setSystemProxyEnabled(enabled: Boolean) { throw UnsupportedOperationException("System proxy is unavailable") }
                override fun triggerNativeCrash() { throw UnsupportedOperationException("Native crash trigger is disabled") }
                override fun writeDebugMessage(message: String?) { writeLog(message) }
                override fun connectSSHAgent(): Int = throw UnsupportedOperationException("SSH agent is unavailable")
            }, this)
            box!!.start()
            box!!.startOrReloadService(json, OverrideOptions())
            logClient = Libbox.newCommandClient(object : CommandClientHandler {
                override fun connected() = Unit
                override fun disconnected(message: String?) { writeLog(message) }
                override fun setDefaultLogLevel(level: Int) = Unit
                override fun clearLogs() = Unit
                override fun writeLogs(messages: LogIterator?) {
                    while (messages?.hasNext() == true) writeLog(messages.next().message)
                }
                override fun writeStatus(message: StatusMessage?) = Unit
                override fun writeGroups(message: OutboundGroupIterator?) = Unit
                override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
                override fun initializeClashMode(modes: StringIterator?, currentMode: String?) = Unit
                override fun updateClashMode(newMode: String?) = Unit
                override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
            }, CommandClientOptions().apply { addCommand(Libbox.CommandLog) })
            logClient!!.connect()
            com.freeturn.app.data.RuleDownloadTunnel.publish(download)
            if (hotspot.enabled) ProxyServiceState.addLog("SOCKS5 Hotspot: 0.0.0.0:${hotspot.port}, TCP/UDP, логин ${com.freeturn.app.data.config.HotspotConfig.USER}")
            ProxyServiceState.setTunnelActive(true)
            ProxyServiceState.addLog("sing-box: ${cfg.tunnelTransport} VPN запущен через локальный FreeTurn (IPv4)")
        } catch (e: Exception) {
            val safe = logRedactor?.redact(e.message.orEmpty()) ?: "Ошибка запуска VPN"
            stopTunnel()
            throw IllegalStateException(safe)
        }
    }

    @Synchronized
    fun stopTunnel() {
        com.freeturn.app.data.RuleDownloadTunnel.clear(ruleDownloadEndpoint)
        ruleDownloadEndpoint = null
        val previous = box
        box = null
        val previousLogs = logClient
        logClient = null
        try {
            try { previousLogs?.disconnect() } finally {
                // Closing the command server alone leaves the tunnel and cache DB open in 1.14.
                try { previous?.closeService() } finally { previous?.close() }
            }
        } finally {
            try { tun?.close() } finally {
                tun = null
                callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
                callback = null
                config = null
                ProxyServiceState.setTunnelActive(false)
            }
        }
    }

    override fun onRevoke() {
        stopService(Intent(this, ProxyService::class.java))
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    override fun openTun(options: TunOptions): Int {
        val cfg = checkNotNull(config)
        val builder = Builder().setSession("Fturn ${cfg.tunnelTransport}").setMtu(options.getMTU())
        fun addresses(iterator: RoutePrefixIterator) {
            while (iterator.hasNext()) iterator.next().let { builder.addAddress(it.address(), it.prefix()) }
        }
        addresses(options.inet4Address)
        addresses(options.inet6Address)
        builder.addRoute("0.0.0.0", 0).addRoute("::", 0)
        val dnsServers = options.getDNSServerAddress()
        while (dnsServers.hasNext()) builder.addDnsServer(dnsServers.next())
        configureApplications(builder, cfg)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        tun = builder.establish() ?: error("Android не создал VPN-интерфейс")
        return tun!!.fd
    }

    private fun configureApplications(builder: Builder, cfg: ClientConfig) {
        val installed = if (cfg.splitTunnelMode == SplitTunnelMode.EXCLUDE &&
            cfg.splitTunnelUseDefaults && cfg.splitTunnelApps.isBlank()) installedInternetPackages() else emptySet()
        val packages = splitTunnelSelection(cfg.splitTunnelMode, cfg.splitTunnelApps, installed, cfg.splitTunnelUseDefaults)
            .filter { it != packageName && isPackageInstalled(it) }
        if (cfg.splitTunnelMode == SplitTunnelMode.INCLUDE) {
            require(packages.isNotEmpty()) { "Выберите хотя бы одно установленное приложение для VPN" }
            packages.forEach(builder::addAllowedApplication)
        } else {
            builder.addDisallowedApplication(packageName)
            if (cfg.splitTunnelMode == SplitTunnelMode.EXCLUDE) packages.forEach(builder::addDisallowedApplication)
        }
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { check(protect(fd)) { "Не удалось защитить сокет VPN" } }
    override fun localDNSTransport(): LocalDNSTransport? = null
    private fun writeLog(message: String?) {
        if (message.isNullOrBlank()) return
        ProxyServiceState.addLog("sing-box: ${logRedactor?.redact(message) ?: message}")
    }
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int): ConnectionOwner {
        if (Build.VERSION.SDK_INT >= 29) {
            val source = java.net.InetSocketAddress(sourceAddress, sourcePort)
            val destination = java.net.InetSocketAddress(destinationAddress, destinationPort)
            val uid = connectivity.getConnectionOwnerUid(ipProtocol, source, destination)
            if (uid >= 0) return ConnectionOwner().apply {
                userId = uid
                setAndroidPackageNames(Strings(packageManager.getPackagesForUid(uid)?.toList().orEmpty()))
            }
        }
        throw UnsupportedOperationException("Connection owner is unavailable")
    }
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    // sing-box 1.14 reads AndroidCAStore through its own JNI certificate provider.
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification?) = Unit
    override fun cancelNotification(identifier: String?, typeID: Int) = Unit
    // Fturn does not expose LAN neighbor rules, platform shells, bridges or Tailscale.
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun registerMyInterface(name: String?) = Unit
    override fun usePlatformShell() = false
    override fun checkPlatformShell(): Unit = throw UnsupportedOperationException("Platform shell is unavailable")
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession =
        throw UnsupportedOperationException("Platform shell is unavailable")
    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("Platform users are unavailable")
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("SFTP is unavailable")
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("SSH is unavailable")
    override fun tailscaleHostname() = ""
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = throw UnsupportedOperationException("Platform bridge is unavailable")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        fun physicalNetwork(lost: Network? = null): Network? =
            PhysicalNetworks.select(connectivity, lost)?.id
        fun update(network: Network?) {
            val link = network?.let(connectivity::getLinkProperties)
            val name = link?.interfaceName
            val index = name?.let { JavaNetworkInterface.getByName(it)?.index } ?: -1
            val caps = network?.let(connectivity::getNetworkCapabilities)
            listener.updateDefaultInterface(name.orEmpty(), index,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false, false)
            setUnderlyingNetworks(network?.let { arrayOf(it) })
        }
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(physicalNetwork())
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = update(physicalNetwork())
            override fun onLost(network: Network) = update(physicalNetwork(network))
        }.also {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), it)
        }
        update(physicalNetwork())
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = Collections.list(JavaNetworkInterface.getNetworkInterfaces()).map { item ->
            NetworkInterface().apply {
                name = item.name
                index = item.index
                setMTU(item.mtu)
                addresses = Strings(item.interfaceAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.networkPrefixLength}" })
                flags = (if (item.isUp) 1 else 0) or (if (item.isLoopback) 8 else 0) or
                    (if (item.isPointToPoint) 16 else 0) or (if (item.supportsMulticast()) 4096 else 0)
                type = Libbox.InterfaceTypeOther
                setDNSServer(Strings(emptyList()))
            }
        }.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = interfaces.hasNext()
            override fun next() = interfaces.next()
        }
    }

    private class Strings(values: List<String>) : StringIterator {
        private val iterator = values.iterator()
        private var remaining = values.size
        override fun hasNext() = iterator.hasNext()
        override fun next() = iterator.next().also { remaining-- }
        override fun len() = remaining
    }
}
