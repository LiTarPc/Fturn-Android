package com.freeturn.app.data.config

import org.json.JSONArray
import org.json.JSONObject

/** Both protocols share one Android TUN, DNS policy and rule engine. */
object SingBoxConfig {
    fun fromClient(cfg: ClientConfig, ruleSets: JSONArray = JSONArray()): String {
        require(!cfg.isRawMode) { "VPN не поддерживает raw-команду" }
        return when (cfg.tunnelTransport) {
            TunnelTransport.VLESS -> VlessProfile.parse(cfg.vlessUri).singBoxConfig(cfg.localPort, ruleSets)
            TunnelTransport.WIREGUARD -> WireGuardProfile.parse(cfg.wireGuardConfig).singBoxConfig(cfg.localPort, ruleSets)
            else -> error("Не выбран протокол VPN")
        }
    }

    fun localPort(endpoint: String): Int {
        require(HostPort.isValid(endpoint) && endpoint.substringBeforeLast(':') == "127.0.0.1") {
            "VPN требует локальный адрес FreeTurn 127.0.0.1:порт"
        }
        return endpoint.substringAfterLast(':').toInt()
    }

    fun build(proxy: JSONObject, ruleSets: JSONArray, wireGuard: Boolean = false,
              allowedIps: List<String> = emptyList(), dnsServers: List<String> = listOf("1.1.1.1"),
              bootstrapDns: Boolean = false): String {
        val outbounds = JSONArray()
        if (!wireGuard) outbounds.put(proxy)
        val rules = JSONArray().put(JSONObject().put("port", 53).put("action", "hijack-dns"))
            .put(JSONObject().put("ip_version", 6).put("action", "reject"))
        val partial = wireGuard && "0.0.0.0/0" !in allowedIps
        val route = JSONObject().put("auto_detect_interface", true)
            .put("final", if (partial) "direct" else "proxy").put("rules", rules)
        if (ruleSets.length() > 0 || partial || bootstrapDns) {
            outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        }
        if (ruleSets.length() > 0) {
            rules.put(JSONObject().put("action", "sniff"))
            val tags = JSONArray()
            for (i in 0 until ruleSets.length()) tags.put(ruleSets.getJSONObject(i).getString("tag"))
            rules.put(JSONObject().put("rule_set", tags).put("action", "route").put("outbound", "direct"))
            route.put("rule_set", ruleSets)
        }
        if (partial) rules.put(JSONObject().put("ip_cidr", JSONArray(allowedIps))
            .put("action", "route").put("outbound", "proxy"))
        val servers = JSONArray()
        dnsServers.forEachIndexed { index, address ->
            servers.put(JSONObject().put("type", "tcp").put("tag", if (index == 0) "remote" else "remote-$index")
                .put("server", address).put("detour", "proxy"))
        }
        if (bootstrapDns) servers.put(JSONObject().put("type", "tcp").put("tag", "bootstrap")
            .put("server", "1.1.1.1").put("detour", "direct"))
        val result = JSONObject().put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", servers).put("final", "remote")
                .put("strategy", "ipv4_only").put("reverse_mapping", ruleSets.length() > 0))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                .put("mtu", ClientConfig.WG_MTU).put("auto_route", true).put("stack", "gvisor")))
            .put("outbounds", outbounds).put("route", route)
        if (wireGuard) result.put("endpoints", JSONArray().put(proxy))
        return result.toString()
    }
}
