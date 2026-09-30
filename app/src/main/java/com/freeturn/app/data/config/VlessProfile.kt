package com.freeturn.app.data.config

import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The first supported share format is VLESS TCP without TLS, carried by FreeTurn. */
data class VlessProfile(val uuid: String, val host: String, val port: Int, val name: String) {
    companion object {
        fun parse(raw: String): VlessProfile {
            val uri = runCatching { URI(raw.trim()) }.getOrElse {
                throw IllegalArgumentException("Некорректная ссылка VLESS")
            }
            require(uri.scheme.equals("vless", true)) { "Ожидается ссылка vless://" }
            val id = uri.userInfo.orEmpty()
            require(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(id)) {
                "Некорректный UUID VLESS"
            }
            UUID.fromString(id)
            require(!uri.host.isNullOrBlank() && uri.port in 1..65535) { "Некорректный адрес VLESS" }
            require(uri.rawPath.isNullOrEmpty()) { "Путь VLESS не поддерживается" }
            val params = mutableMapOf<String, String>()
            uri.rawQuery?.split('&')?.forEach {
                val key = decode(it.substringBefore('='))
                require(key !in params) { "Повторный параметр VLESS: $key" }
                params[key] = decode(it.substringAfter('=', ""))
            }
            require(params.keys.all { it in setOf("encryption", "security", "type") }) {
                "Поддерживается только VLESS TCP без TLS и дополнительных параметров"
            }
            require(params.getOrDefault("encryption", "none") == "none") { "Шифрование VLESS не поддерживается" }
            require(params.getOrDefault("security", "none") == "none") { "TLS/REALITY пока не поддерживается" }
            require(params.getOrDefault("type", "tcp") == "tcp") { "Поддерживается только VLESS TCP" }
            return VlessProfile(id, uri.host, uri.port, decode(uri.rawFragment.orEmpty()))
        }

        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }

    /** Keep the imported URI intact; only the runtime endpoint is replaced. */
    fun singBoxConfig(localEndpoint: String, bypassRuleSets: JSONArray = JSONArray()): String {
        val outbound = JSONObject().put("type", "vless").put("tag", "proxy")
            .put("server", "127.0.0.1").put("server_port", SingBoxConfig.localPort(localEndpoint))
            .put("uuid", uuid).put("packet_encoding", "xudp")
        return SingBoxConfig.build(outbound, bypassRuleSets)
    }
}
