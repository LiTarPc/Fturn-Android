package com.freeturn.app.data.config

import org.json.JSONArray
import org.json.JSONObject

/** Experimental FOU/ipproto=4 receiver. One fixed peer on a dedicated USB network. */
data class RouterTunnelConfig(
    val enabled: Boolean = false,
    val listen: String = "",
    val peer: String = "",
    val port: Int = 40080,
    val peerPort: Int = 40080,
    val sourceCidr: String = "192.168.1.0/24",
    val mtu: Int = 1350,
    val automatic: Boolean = false,
    val pairingKey: String = ""
) {
    fun validate() {
        if (automatic) {
            require(pairingKey.matches(Regex("[0-9a-fA-F]{64}"))) { "Код привязки должен содержать 64 шестнадцатеричных символа" }
            require(port != CONTROL_PORT && peerPort != CONTROL_PORT) { "Порт 40081 используется для автоматической привязки" }
        } else require(privateIPv4(listen) && privateIPv4(peer) && listen != peer) { "Укажите разные частные IPv4-адреса телефона и роутера в USB-сети" }
        require(port in 1024..65535 && peerPort in 1024..65535) { "UDP-порты должны быть в диапазоне 1024–65535" }
        require(mtu in 576..1400) { "MTU должен быть в диапазоне 576–1400" }
        val parts = sourceCidr.split('/')
        val bits = parts.getOrNull(1)?.toIntOrNull()
        val address = ipv4(parts[0])
        require(parts.size == 2 && bits != null && bits in 8..32 && address != null) { "Укажите подсеть LAN в формате IPv4/маска" }
        val hostMask = (1L shl (32 - bits)) - 1
        require((address and hostMask) == 0L && privateIPv4(parts[0]) && privateNumber(address or hostMask)) { "Подсеть LAN должна быть частной и начинаться с адреса сети" }
    }

    fun configure(json: String): String {
        if (!enabled) return json
        validate()
        return JSONObject(json).apply {
            getJSONArray("inbounds").put(JSONObject().put("type", "fturn-fou").put("tag", "router-in")
                .put("listen", listen).put("listen_port", port).put("peer", peer).put("peer_port", peerPort)
                .put("automatic", automatic).put("pairing_key", if (automatic) pairingKey else "").put("control_port", CONTROL_PORT)
                .put("source_prefixes", JSONArray().put(sourceCidr)).put("mtu", mtu))
        }.toString()
    }

    fun encode(): String = JSONObject().put("enabled", enabled).put("listen", listen).put("peer", peer)
        .put("port", port).put("peerPort", peerPort).put("sourceCidr", sourceCidr).put("mtu", mtu).put("automatic", automatic).put("pairingKey", pairingKey).toString()

    companion object {
        const val CONTROL_PORT = 40081
        fun newPairingKey(): String = java.security.SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decode(raw: String?): RouterTunnelConfig {
            if (raw.isNullOrBlank()) return RouterTunnelConfig()
            // Malformed saved data must never turn on an unintended network listener.
            return runCatching {
                val o = JSONObject(raw)
                RouterTunnelConfig(o.optBoolean("enabled", false), o.optString("listen"), o.optString("peer"),
                    o.optInt("port", 40080), o.optInt("peerPort", 40080), o.optString("sourceCidr", "192.168.1.0/24"), o.optInt("mtu", 1350), o.optBoolean("automatic", false), o.optString("pairingKey"))
                    .also { if (it.enabled) it.validate() }
            }.getOrElse { RouterTunnelConfig() }
        }
        private fun ipv4(value: String): Long? {
            val fields = value.split('.')
            if (fields.size != 4) return null
            var result = 0L
            for (field in fields) {
                if (field.isEmpty() || field.length > 3 || field.any { it !in '0'..'9' } || field.length > 1 && field[0] == '0') return null
                val n = field.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                result = (result shl 8) or n.toLong()
            }
            return result
        }
        private fun privateNumber(n: Long) = n ushr 24 == 10L || n ushr 20 == 0xac1L || n ushr 16 == 0xc0a8L
        private fun privateIPv4(value: String) = ipv4(value)?.let(::privateNumber) == true
    }
}
