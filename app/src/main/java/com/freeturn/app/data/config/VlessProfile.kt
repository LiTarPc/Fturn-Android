package com.freeturn.app.data.config

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** VLESS share parameters; only the dial endpoint is replaced by FreeTurn. */
data class VlessProfile(
    val uuid: String, val host: String, val port: Int, val name: String,
    val security: String = "none", val transport: String = "tcp",
    val serverName: String = "", val fingerprint: String = "",
    val alpn: List<String> = emptyList(), val insecure: Boolean = false,
    val flow: String = "", val publicKey: String = "", val shortId: String = "",
    val httpHost: String = "", val path: String = "/", val serviceName: String = "",
    val maxEarlyData: Int = 0, val earlyDataHeaderName: String = "",
    val packetEncoding: String = "xudp"
) {
    companion object {
        private val aliases = mapOf("servername" to "sni", "fingerprint" to "fp",
            "publickey" to "pbk", "shortid" to "sid", "allowinsecure" to "insecure",
            "service_name" to "servicename", "maxearlydata" to "ed", "earlydataheadername" to "eh")
        private val supported = setOf("encryption", "security", "type", "sni", "fp", "alpn",
            "insecure", "flow", "pbk", "sid", "spx", "host", "path", "servicename",
            "mode", "headertype", "ed", "eh", "packetencoding")
        private val fingerprints = setOf("chrome", "firefox", "edge", "safari", "360", "qq",
            "ios", "android", "random", "randomized")
        private val legacyChrome = setOf("chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
            "chrome_pq", "chrome_pq_psk")

        fun parse(raw: String): VlessProfile {
            require(raw.length <= 256 * 1024) { "Ссылка VLESS слишком большая" }
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
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Путь транспорта задаётся параметром path"
            }
            val params = mutableMapOf<String, String>()
            uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach {
                val decodedKey = decode(it.substringBefore('=')).lowercase(Locale.ROOT)
                val key = aliases[decodedKey] ?: decodedKey
                require(key in supported) { "Параметр VLESS не поддерживается: $key" }
                require(key !in params) { "Повторный параметр VLESS: $key" }
                val value = decode(it.substringAfter('=', ""))
                require(value.none { c -> c < ' ' || c == '\u007f' }) { "Недопустимые символы в параметре $key" }
                params[key] = value
            }
            fun value(key: String, default: String = "") = params[key]?.takeIf { it.isNotEmpty() } ?: default
            fun hasValue(vararg keys: String) = keys.any { !params[it].isNullOrEmpty() }
            require(value("encryption", "none") == "none") { "Поддерживается encryption=none" }
            val security = value("security", "none").lowercase(Locale.ROOT)
            require(security in setOf("none", "tls", "reality")) { "Неподдерживаемая защита VLESS" }
            val transport = value("type", "tcp").lowercase(Locale.ROOT).let { if (it == "raw") "tcp" else it }
            require(transport in setOf("tcp", "ws", "grpc")) { "Поддерживаются транспорты tcp, ws и grpc" }
            require(value("headertype", "none") == "none") { "TCP headerType поддерживается только none" }
            val flow = value("flow")
            require(flow.isEmpty() || flow == "xtls-rprx-vision") { "Неподдерживаемый flow VLESS" }
            require(flow.isEmpty() || (transport == "tcp" && security != "none")) {
                "Vision требует TCP с TLS или REALITY"
            }
            val insecure = when (value("insecure", "false").lowercase(Locale.ROOT)) {
                "false", "0" -> false
                "true", "1" -> true
                else -> throw IllegalArgumentException("insecure/allowInsecure: ожидается 0, 1, false или true")
            }
            require(!insecure || security == "tls") { "insecure применяется только к TLS" }
            require(security != "none" || !hasValue("sni", "fp", "alpn")) {
                "SNI, fingerprint и ALPN требуют TLS или REALITY"
            }
            require(security == "reality" || !hasValue("pbk", "sid", "spx")) {
                "pbk, sid и spx применяются только к REALITY"
            }
            val serverName = if (security == "none") "" else value("sni", uri.host.removeSurrounding("[", "]"))
            require(serverName.none { it.isWhitespace() || it in "/?#" }) { "Некорректное имя SNI" }
            val fingerprint = value("fp", if (security == "reality") "chrome" else "").lowercase(Locale.ROOT)
                .let { if (it in legacyChrome) "chrome" else it }
            require(fingerprint.isEmpty() || fingerprint in fingerprints) { "Неподдерживаемый TLS fingerprint" }
            val alpn = value("alpn").takeIf { it.isNotEmpty() }?.split(',')?.map { it.trim() } ?: emptyList()
            require(alpn.all { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 255 }) { "Некорректный ALPN" }
            val publicKey = if (security == "reality") {
                val bytes = runCatching { Base64.getUrlDecoder().decode(value("pbk").replace('+', '-').replace('/', '_')) }
                    .getOrNull()
                require(bytes != null && bytes.size == 32) { "REALITY требует pbk: публичный ключ длиной 32 байта" }
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            } else ""
            val shortId = value("sid").lowercase(Locale.ROOT)
            require(shortId.length <= 16 && shortId.length % 2 == 0 && shortId.all { it in "0123456789abcdef" }) {
                "REALITY sid: до 16 шестнадцатеричных символов, чётная длина"
            }
            require(transport == "ws" || !hasValue("host", "path", "ed", "eh")) {
                "host, path и early data применяются только к WebSocket"
            }
            require(transport == "grpc" || !hasValue("servicename", "mode")) {
                "serviceName и mode применяются только к gRPC"
            }
            require(value("mode", "gun") == "gun") { "gRPC mode=multi не поддерживается; используйте gun" }
            val httpHost = if (transport == "ws") value("host", uri.rawAuthority.substringAfter('@')) else ""
            require(httpHost.none { it.isWhitespace() || it in "/?#," }) { "Некорректный WebSocket Host" }
            var path = value("path", "/")
            var earlyData = params["ed"]
            // Xray links often put ?ed=N inside the URL-encoded WebSocket path.
            if (transport == "ws" && '?' in path) {
                val query = path.substringAfter('?').split('&')
                val embedded = query.filter { decode(it.substringBefore('=')) == "ed" }
                require(embedded.size <= 1 && !(embedded.isNotEmpty() && earlyData != null)) {
                    "Повторный параметр early data"
                }
                if (embedded.isNotEmpty()) {
                    earlyData = decode(embedded.single().substringAfter('=', ""))
                    val rest = query.filterNot { it in embedded }.joinToString("&")
                    path = path.substringBefore('?') + if (rest.isEmpty()) "" else "?$rest"
                }
            }
            require(transport != "ws" || (path.startsWith('/') && '#' !in path)) { "WebSocket path должен начинаться с /" }
            val maxEarlyData = if (earlyData.isNullOrEmpty()) 0 else earlyData.toIntOrNull()
            require(maxEarlyData != null && maxEarlyData in 0..65535) { "WebSocket ed: число от 0 до 65535" }
            val earlyDataHeader = value("eh", if (maxEarlyData > 0) "Sec-WebSocket-Protocol" else "")
            require(earlyDataHeader.isEmpty() || Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(earlyDataHeader)) {
                "Некорректное имя заголовка early data"
            }
            val packetEncoding = value("packetencoding", "xudp").lowercase(Locale.ROOT)
            require(packetEncoding in setOf("xudp", "packetaddr", "none")) { "Неподдерживаемый packetEncoding" }
            return VlessProfile(id, uri.host, uri.port, decode(uri.rawFragment.orEmpty()), security, transport,
                serverName, fingerprint, alpn, insecure, flow, publicKey, shortId, httpHost, path,
                value("servicename"), maxEarlyData, earlyDataHeader, if (packetEncoding == "none") "" else packetEncoding)
        }

        private fun decode(value: String): String = runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrElse { throw IllegalArgumentException("Некорректное URL-кодирование VLESS") }
    }

    /** Keep authentication, TLS and HTTP identity while dialing the local FreeTurn listener. */
    fun singBoxConfig(localEndpoint: String, bypassRuleSets: JSONArray = JSONArray()): String {
        val outbound = JSONObject().put("type", "vless").put("tag", "proxy")
            .put("server", "127.0.0.1").put("server_port", SingBoxConfig.localPort(localEndpoint))
            .put("uuid", uuid).put("packet_encoding", packetEncoding)
        if (flow.isNotEmpty()) outbound.put("flow", flow)
        if (security != "none") {
            val tls = JSONObject().put("enabled", true).put("server_name", serverName).put("insecure", insecure)
            if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn))
            if (fingerprint.isNotEmpty()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            if (security == "reality") tls.put("reality", JSONObject().put("enabled", true)
                .put("public_key", publicKey).put("short_id", shortId))
            outbound.put("tls", tls)
        }
        when (transport) {
            "ws" -> {
                val ws = JSONObject().put("type", "ws").put("path", path)
                    .put("headers", JSONObject().put("Host", httpHost))
                if (maxEarlyData > 0) ws.put("max_early_data", maxEarlyData).put("early_data_header_name", earlyDataHeaderName)
                outbound.put("transport", ws)
            }
            "grpc" -> outbound.put("transport", JSONObject().put("type", "grpc").put("service_name", serviceName))
        }
        return SingBoxConfig.build(outbound, bypassRuleSets)
    }
}
