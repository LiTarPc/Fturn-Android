package com.freeturn.app.data.config

import org.json.JSONArray
import org.json.JSONObject

/** A rootless SOCKS listener inside the same routing engine as the VPN. */
data class HotspotConfig(val enabled: Boolean = false, val port: Int = 1080, val password: String = "") {
    companion object { const val USER = "fturn" }
    fun validate() {
        require(port in 1024..65535) { "SOCKS5 port must be 1024..65535" }
        require(password.toByteArray(Charsets.UTF_8).size in 8..255) { "SOCKS5 password must be 8..255 UTF-8 bytes" }
    }
    fun configure(json: String): String {
        if (!enabled) return json
        validate()
        return JSONObject(json).apply {
            getJSONArray("inbounds").put(JSONObject().put("type", "socks").put("tag", "hotspot-in")
                .put("listen", "0.0.0.0").put("listen_port", port)
                .put("users", JSONArray().put(JSONObject().put("username", USER).put("password", password))))
            // SOCKS clients may send a hostname. Resolve inside the VPN before
            // CIDR matching so hotspot traffic follows the same bypass policy.
            val route = getJSONObject("route")
            val previous = route.getJSONArray("rules")
            val rules = JSONArray().put(JSONObject().put("inbound", JSONArray().put("hotspot-in"))
                .put("action", "resolve").put("strategy", "ipv4_only"))
            for (i in 0 until previous.length()) rules.put(previous.get(i))
            route.put("rules", rules)
        }.toString()
    }
}
