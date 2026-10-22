package com.freeturn.app.service
import com.freeturn.app.domain.proxy.ProxyServiceState

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Следит за сменой ФИЗИЧЕСКОЙ сети (Wi-Fi <-> LTE и т.п.) и дёргает [onHandover].
 * VPN-интерфейсы отфильтрованы: иначе старт WireGuard выглядел бы как смена сети
 * и уводил прокси в бесконечный рестарт.
 */
class NetworkHandoverMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHandover: () -> Unit,
) {
    companion object {
        // Игнорируем сетевые события первые секунды после регистрации - иначе
        // initial onAvailable/onCapabilitiesChanged триггерят ложный рестарт.
        private const val WARMUP_MS = 3_000L
    }

    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var debounceJob: Job? = null
    @Volatile private var lastKey: String? = null

    fun register() {
        val cm = cm
        val registeredAt = SystemClock.elapsedRealtime()
        lastKey = physicalNetworkKey(cm)

        fun schedule(reason: String) {
            if (SystemClock.elapsedRealtime() - registeredAt < WARMUP_MS) return
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(2_000)
                val oldKey = lastKey
                val newKey = physicalNetworkKey(cm)
                // Ключ тот же - ожидаемый no-op. onCapabilitiesChanged сыплет
                // десятки раз/мин (сигнал, link speed, валидация инета), не логаем.
                if (oldKey == newKey) return@launch
                lastKey = newKey
                if (newKey == null) {
                    ProxyServiceState.addLog("Сеть: физическая сеть недоступна ($reason)")
                    return@launch
                }
                onHandover()
            }
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    ProxyServiceState.addLog("Сеть: VPN-событие проигнорировано")
                    return
                }
                schedule("available")
            }

            override fun onLost(network: Network) {
                schedule("lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return
                }
                schedule("capabilities")
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    fun unregister() {
        callback?.let { cb ->
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        callback = null
    }

    /**
     * DNS активной сети (оператор/Wi-Fi) для флага `-dns-servers` ядра.
     * Пусто, если сеть недоступна или у linkProperties нет DNS (норма на эмуляторе).
     */
    fun activeDnsServers(): String = try {
        PhysicalNetworks.select(cm)?.dnsServers.orEmpty().joinToString(",")
    } catch (_: Exception) {
        ""
    }

    /**
     * Ключ ОДНОЙ приоритетной физсети (транспорт + iface). Берём приоритетную, а не
     * весь allNetworks: при активном Wi-Fi cellular флапает в фоне, набор прыгал бы ->
     * ложная "смена сети". link-адреса не в ключе - ротация IPv6/DHCP идёт на той же
     * сети; реальный хендовер меняет транспорт/iface.
     */
    private fun physicalNetworkKey(cm: ConnectivityManager): String? =
        PhysicalNetworks.select(cm)?.let { "${it.transportRank}|${it.interfaceName}" }
}
