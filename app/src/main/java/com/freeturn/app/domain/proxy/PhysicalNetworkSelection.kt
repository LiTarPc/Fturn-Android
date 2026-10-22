package com.freeturn.app.domain.proxy

/** Select the underlay as a whole: never borrow DNS from a VPN or standby link. */
object PhysicalNetworkSelection {
    data class Candidate<T>(
        val id: T,
        val interfaceName: String,
        val dnsServers: List<String>,
        val transportRank: Int,
        val hasInternet: Boolean,
        val isVpn: Boolean,
        val validated: Boolean,
    )

    fun <T> select(active: T?, candidates: List<Candidate<T>>, excluded: T? = null): Candidate<T>? {
        val eligible = candidates.filter { it.id != excluded && it.hasInternet && !it.isVpn }
        return eligible.firstOrNull { it.id == active } ?: eligible.minWithOrNull(
            compareBy<Candidate<T>>({ !it.validated }, { it.transportRank },
                { it.interfaceName }, { it.id.toString() })
        )
    }
}
