package com.freeturn.app.data.config

data class ClientConfig(
    val serverAddress: String = "",
    val vkLink: String = "",
    val provider: String = Provider.VK,
    val threads: Int = DEFAULT_THREADS,
    val streamsPerCred: Int = DEFAULT_STREAMS_PER_CRED,
    val useUdp: Boolean = false,
    val manualCaptcha: Boolean = false,
    val localPort: String = DEFAULT_LOCAL_PORT,
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    val tcpForward: Boolean = false,
    val bond: Boolean = false,
    val debugMode: Boolean = false,
    val useCarrierDns: Boolean = true,
    val dnsMode: String = DnsMode.AUTO,
    val customDns: String = "",
    val syncServerSwitches: Boolean = true,
    val magicSwitch: Boolean = false,
    val magicTurn: String = "",
    val tunnelTransport: String = TunnelTransport.NONE,
    val wireGuardConfig: String = "",
    val vlessUri: String = "",
    val rawAddress: String = "10.77.0.2/24",
    val rawMtu: Int = 1280,
    val rawUplink: String = "packet",
    val bypassRulesEnabled: Boolean = true,
    val bypassRuleSets: List<BypassRuleSet> = BypassRuleSet.defaults(),
    val wireGuardTunnelName: String = TunnelTransport.DEFAULT_TUNNEL_NAME,
    val splitTunnelMode: String = SplitTunnelMode.EXCLUDE,
    /**
     * Package-имена для include/exclude (разделители: запятая/пробел/перенос строки).
     * Пустой в exclude-режиме = дефолтный список рос-сервисов (см. [splitTunnelSelection]).
     */
    val splitTunnelApps: String = "",
    val splitTunnelUseDefaults: Boolean = true,
    val logsEnabled: Boolean = true,
    val clientId: String = ""
) {
    val coreTcpForward: Boolean
        get() = when (tunnelTransport) {
            TunnelTransport.VLESS -> true
            TunnelTransport.WIREGUARD, TunnelTransport.RAW -> false
            else -> tcpForward
        }

    val vlessActive: Boolean
        get() = tunnelTransport == TunnelTransport.VLESS && vlessUri.isNotBlank()
    val vpnActive: Boolean
        get() = wireGuardActive || vlessActive || rawVpnActive
    val rawVpnActive: Boolean
        get() = tunnelTransport == TunnelTransport.RAW
    val wireGuardActive: Boolean
        get() = tunnelTransport == TunnelTransport.WIREGUARD && wireGuardConfig.isNotBlank()

    companion object {
        const val DEFAULT_LOCAL_PORT = "127.0.0.1:9000"
        const val DEFAULT_THREADS = 12
        const val DEFAULT_STREAMS_PER_CRED = 12
        // Не настройка, а константа транспорта: WG идёт поверх TURN (STUN-обёртка +
        // UDP + IP), дефолтные 1420 фрагментируются. 1280 - минимум IPv6, живёт везде.
        // Серверная сторона держит то же значение (control.sh, WG_MTU).
        const val WG_MTU = 1280
    }
}
