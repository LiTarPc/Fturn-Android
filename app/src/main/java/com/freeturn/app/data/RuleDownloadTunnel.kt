package com.freeturn.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/** The live VPN owns this authenticated loopback-only download channel. */
object RuleDownloadTunnel {
    data class Endpoint(val port: Int, val password: String)
    const val USER = "fturn-rules"
    const val TAG = "rule-download-in"
    @Volatile var endpoint: Endpoint? = null
        private set

    fun prepare(excludedPorts: Set<Int> = emptySet()): Endpoint {
        repeat(16) {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (port !in excludedPorts) return Endpoint(port, UUID.randomUUID().toString())
        }
        error("Could not reserve a local VPN port")
    }
    fun publish(value: Endpoint) { endpoint = value }
    fun clear(value: Endpoint?) { if (endpoint === value) endpoint = null }

    fun configure(json: String, value: Endpoint): String {
        require(value.port in 1..65535 && value.password.isNotBlank())
        val config = JSONObject(json)
        config.getJSONArray("inbounds").put(JSONObject().put("type", "http").put("tag", TAG)
            .put("listen", "127.0.0.1").put("listen_port", value.port)
            .put("users", JSONArray().put(JSONObject().put("username", USER).put("password", value.password))))
        val route = config.getJSONObject("route")
        val previous = route.getJSONArray("rules")
        // Downloads must use the tunnel even if a bypass list or partial WG route matches.
        val rules = JSONArray().put(JSONObject().put("inbound", JSONArray().put(TAG))
            .put("action", "route").put("outbound", "proxy"))
        for (i in 0 until previous.length()) rules.put(previous.get(i))
        route.put("rules", rules)
        return config.toString()
    }
}
