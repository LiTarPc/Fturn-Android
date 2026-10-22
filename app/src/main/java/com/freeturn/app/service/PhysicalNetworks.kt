package com.freeturn.app.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.freeturn.app.domain.proxy.PhysicalNetworkSelection

internal object PhysicalNetworks {
    @Suppress("DEPRECATION")
    fun select(cm: ConnectivityManager, excluded: Network? = null): PhysicalNetworkSelection.Candidate<Network>? {
        val active = cm.activeNetwork
        val networks = (cm.allNetworks.toList() + listOfNotNull(active)).distinct()
        val candidates = networks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val link = cm.getLinkProperties(network)
            val rank = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
                else -> 4
            }
            PhysicalNetworkSelection.Candidate(
                id = network, interfaceName = link?.interfaceName.orEmpty(),
                dnsServers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.filter { it.isNotBlank() }.distinct(),
                transportRank = rank,
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
        return PhysicalNetworkSelection.select(active, candidates, excluded)
    }
}
