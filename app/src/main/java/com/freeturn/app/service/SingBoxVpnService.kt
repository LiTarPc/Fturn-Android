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
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections

/** The only owner of the VPN TUN descriptor and native BoxService. */
class SingBoxVpnService : VpnService(), PlatformInterface {
    inner class LocalBinder : Binder() { val service get() = this@SingBoxVpnService }
    private val binder = LocalBinder()
    private var box: BoxService? = null
    private var tun: ParcelFileDescriptor? = null
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
    fun startTunnel(cfg: ClientConfig) {
        check(prepare(this) == null) { "Требуется разрешение Android VPN" }
        stopTunnel()
        config = cfg
        logRedactor = VpnLogRedactor(cfg)
        try {
            Libbox.setup(SetupOptions().apply {
                basePath = filesDir.absolutePath
                workingPath = filesDir.resolve("sing-box").absolutePath
                tempPath = cacheDir.resolve("sing-box").absolutePath
                fixAndroidStack = true
            })
            val sets = if (cfg.bypassRulesEnabled) com.freeturn.app.data.BypassRuleStore(this).materialize(cfg.bypassRuleSets) else org.json.JSONArray()
            val json = SingBoxConfig.fromClient(cfg, sets)
            box = Libbox.newService(json, this)
            box!!.start()
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
        val previous = box
        box = null
        try { previous?.close() } finally {
            tun?.close()
            tun = null
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            callback = null
            config = null
            ProxyServiceState.setTunnelActive(false)
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
        builder.addDnsServer(options.getDNSServerAddress().value)
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
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        tun = builder.establish() ?: error("Android не создал VPN-интерфейс")
        return tun!!.fd
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { check(protect(fd)) { "Не удалось защитить сокет VPN" } }
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun writeLog(message: String?) {
        if (message.isNullOrBlank()) return
        ProxyServiceState.addLog("sing-box: ${logRedactor?.redact(message) ?: message}")
    }
    override fun useProcFS() = false
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int,
        destinationAddress: String?, destinationPort: Int): Int = -1
    override fun packageNameByUid(uid: Int) = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    override fun uidByPackageName(packageName: String) = packageManager.getApplicationInfo(packageName, 0).uid
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    override fun systemCertificates(): StringIterator = Strings(emptyList())
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: Notification?) = Unit

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        fun physicalNetwork(lost: Network? = null): Network? = connectivity.allNetworks
            .filter { it != lost }
            .filter {
                val caps = connectivity.getNetworkCapabilities(it)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.minByOrNull {
                val caps = connectivity.getNetworkCapabilities(it)
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 0
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                    else -> 2
                }
            }
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
