package com.freeturn.app.data.config

import com.freeturn.app.data.CoreArgs
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import com.freeturn.app.data.server.ServerOpts
import com.freeturn.app.data.share.FreeturnLink
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VlessProfileTest {
    private val base = "vless://11111111-2222-4333-8444-555555555555@vpn.example:443"
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private fun outbound(query: String): JSONObject = JSONObject(VlessProfile.parse("$base?$query").singBoxConfig("127.0.0.1:9000"))
        .getJSONArray("outbounds").getJSONObject(0)
    private fun invalid(query: String) {
        assertTrue(query, runCatching { VlessProfile.parse("$base?$query") }.isFailure)
    }

    @Test fun `TLS keeps original certificate identity while dialing FreeTurn`() {
        val out = outbound("security=tls&type=tcp&alpn=h2%2Chttp%2F1.1")
        assertEquals("127.0.0.1", out.getString("server"))
        assertEquals(9000, out.getInt("server_port"))
        val tls = out.getJSONObject("tls")
        assertEquals("vpn.example", tls.getString("server_name"))
        assertFalse(tls.getBoolean("insecure"))
        assertEquals(listOf("h2", "http/1.1"), tls.getJSONArray("alpn").let { List(it.length()) { i -> it.getString(i) } })
        assertFalse(tls.has("utls"))
        assertFalse(out.has("transport"))
    }

    @Test fun `explicit SNI fingerprint and certificate opt out survive mapping`() {
        val tls = outbound("security=tls&sni=front.example&fp=firefox&allowInsecure=1").getJSONObject("tls")
        assertEquals("front.example", tls.getString("server_name"))
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
        assertTrue(tls.getBoolean("insecure"))
    }

    @Test fun `REALITY Vision maps key short id and default uTLS`() {
        val out = outbound("security=reality&type=raw&sni=front.example&pbk=$key&sid=01AB23CD&flow=xtls-rprx-vision&spx=%2F")
        assertEquals("xtls-rprx-vision", out.getString("flow"))
        val tls = out.getJSONObject("tls")
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("front.example", tls.getString("server_name"))
        assertEquals(key, tls.getJSONObject("reality").getString("public_key"))
        assertEquals("01ab23cd", tls.getJSONObject("reality").getString("short_id"))
        assertFalse(tls.getBoolean("insecure"))
    }

    @Test fun `REALITY accepts empty server short id and normalizes padded keys`() {
        val tls = outbound("security=reality&publicKey=$key%3D&shortId=&fingerprint=chrome_psk")
            .getJSONObject("tls")
        assertEquals(key, tls.getJSONObject("reality").getString("public_key"))
        assertEquals("", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test fun `WebSocket preserves Host encoded path query and literal plus`() {
        val out = outbound("security=tls&type=ws&sni=front.example&host=cdn.example&path=%2Fsocket%2Bname%3Ftoken%3Da%252Bb")
        val ws = out.getJSONObject("transport")
        assertEquals("ws", ws.getString("type"))
        assertEquals("cdn.example", ws.getJSONObject("headers").getString("Host"))
        assertEquals("/socket+name?token=a%2Bb", ws.getString("path"))
        assertEquals("front.example", out.getJSONObject("tls").getString("server_name"))
    }

    @Test fun `WebSocket defaults use original authority instead of loopback`() {
        val out = outbound("type=ws")
        assertEquals("vpn.example:443", out.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        assertEquals("/", out.getJSONObject("transport").getString("path"))
        assertFalse(out.has("tls"))
    }

    @Test fun `WebSocket early data is extracted from nested path query`() {
        val ws = outbound("type=ws&path=%2Fsocket%3Fed%3D2048%26token%3Dx").getJSONObject("transport")
        assertEquals("/socket?token=x", ws.getString("path"))
        assertEquals(2048, ws.getInt("max_early_data"))
        assertEquals("Sec-WebSocket-Protocol", ws.getString("early_data_header_name"))
    }

    @Test fun `explicit WebSocket early data header is preserved`() {
        val ws = outbound("type=ws&ed=1024&eh=X-Early-Data").getJSONObject("transport")
        assertEquals(1024, ws.getInt("max_early_data"))
        assertEquals("X-Early-Data", ws.getString("early_data_header_name"))
    }

    @Test fun `gRPC maps serviceName TLS identity and ALPN`() {
        val out = outbound("type=grpc&security=tls&serviceName=tunnel%2Bservice&sni=grpc.example&alpn=h2&mode=gun")
        val grpc = out.getJSONObject("transport")
        assertEquals("grpc", grpc.getString("type"))
        assertEquals("tunnel+service", grpc.getString("service_name"))
        assertEquals("grpc.example", out.getJSONObject("tls").getString("server_name"))
        assertEquals("h2", out.getJSONObject("tls").getJSONArray("alpn").getString(0))
    }

    @Test fun `full sb URI is retained through share storage and backups`() {
        for (query in listOf("security=tls&type=ws&host=cdn.example&path=%2Fws&sni=front.example",
            "security=reality&pbk=$key&sid=01&flow=xtls-rprx-vision",
            "security=tls&type=grpc&serviceName=tunnel&sni=grpc.example")) {
            val uri = "$base?$query#Test%20profile"
            val share = FreeturnLink(provider = "vk", peer = "192.0.2.1:56411", sbUri = uri)
            assertEquals(uri, FreeturnLink.parse(share.encode()).getOrThrow().sbUri)
            val cfg = ClientConfig(tunnelTransport = TunnelTransport.VLESS, vlessUri = uri, tcpForward = false)
            assertEquals(uri, ServerJson.decodeList(ServerJson.encodeList(listOf(Server(name = "Test", client = cfg)))).single().client.vlessUri)
            val args = CoreArgs.client(cfg, ServerOpts())
            assertEquals("tcp", args[args.indexOf("-mode") + 1])
            assertEquals("Test profile", VlessProfile.parse(uri).name)
        }
    }

    @Test fun `malformed or incomplete REALITY fails before connection`() {
        for (query in listOf("security=reality", "security=reality&pbk=bad",
            "security=reality&pbk=$key&sid=abc", "security=reality&pbk=$key&sid=001122334455667788",
            "security=reality&pbk=$key&sid=zz", "security=reality&pbk=$key&insecure=true")) invalid(query)
    }

    @Test fun `invalid parameter combinations are rejected instead of ignored`() {
        for (query in listOf("security=none&sni=front.example", "security=none&fp=chrome",
            "security=tls&pbk=$key", "flow=xtls-rprx-vision", "security=tls&type=ws&flow=xtls-rprx-vision",
            "type=tcp&path=%2Fws", "type=tcp&serviceName=tunnel", "type=grpc&mode=multi",
            "type=ws&path=relative", "type=ws&host=bad%0D%0AHost%3Ax", "security=tls&fp=unknown",
            "security=tls&insecure=maybe", "security=tls&alpn=h2%2C", "type=xhttp", "headerType=http",
            "unknown=secret", "encryption=other", "type=ws&ed=-1", "type=ws&ed=65536",
            "type=ws&ed=100&path=%2Fws%3Fed%3D100", "type=ws&eh=bad%20header")) invalid(query)
    }

    @Test fun `duplicate aliases and parameters fail explicitly`() {
        invalid("security=tls&sni=a.example&serverName=b.example")
        invalid("type=tcp&type=ws")
        invalid("security=reality&pbk=$key&publicKey=$key")
    }

    @Test fun `supported URI variants preserve semantics`() {
        val parsed = VlessProfile.parse("$base/?SECURITY=TLS&serverName=front.example&TYPE=raw#A%2BB")
        assertEquals("tls", parsed.security)
        assertEquals("tcp", parsed.transport)
        assertEquals("front.example", parsed.serverName)
        assertEquals("A+B", parsed.name)
        assertEquals("", outbound("packetEncoding=none").getString("packet_encoding"))
    }

    @Test fun `bypass and DNS policy remain unchanged for new transports`() {
        val config = JSONObject(VlessProfile.parse("$base?security=tls&type=grpc&serviceName=tunnel")
            .singBoxConfig("127.0.0.1:9000", org.json.JSONArray().put(JSONObject()
                .put("tag", "ru").put("type", "inline").put("rules", org.json.JSONArray()
                    .put(JSONObject().put("ip_cidr", org.json.JSONArray().put("192.0.2.0/24")))))))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertEquals("hijack-dns", config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
        assertEquals("ipv4_only", config.getJSONObject("dns").getString("strategy"))
        assertEquals("proxy", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
    }
    /** Native relay tests consume these production-generated configs after JVM tests. */
    @Test fun `export configs for native relay compatibility tests`() {
        val queries = linkedMapOf(
            "tcp-plain" to "type=tcp",
            "tcp-tls" to "type=tcp&security=tls&sni=front.example",
            "tcp-tls-utls" to "type=tcp&security=tls&sni=front.example&fp=chrome",
            "tcp-tls-vision" to "type=tcp&security=tls&sni=front.example&fp=chrome&flow=xtls-rprx-vision",
            "tcp-reality-vision" to "type=tcp&security=reality&sni=front.example&fp=chrome&pbk=$key&sid=0011&flow=xtls-rprx-vision",
            "ws-plain" to "type=ws&host=front.example&path=%2Fsocket%2Bpath%3Ftoken%3Dtest",
            "ws-reality" to "type=ws&security=reality&sni=front.example&fp=chrome&pbk=$key&sid=0011&host=front.example&path=%2Fsocket",
            "ws-tls" to "type=ws&security=tls&sni=front.example&fp=chrome&host=front.example&path=%2Fsocket",
            "ws-tls-early-data" to "type=ws&security=tls&sni=front.example&host=front.example&path=%2Fsocket%3Fed%3D2048",
            "grpc-plain" to "type=grpc&serviceName=test-service",
            "grpc-reality" to "type=grpc&security=reality&sni=front.example&fp=chrome&pbk=$key&sid=0011&alpn=h2&serviceName=test-service",
            "grpc-tls" to "type=grpc&security=tls&sni=front.example&fp=chrome&alpn=h2&serviceName=test-service",
            "tls-wrong-sni" to "type=tcp&security=tls&sni=wrong.example"
        )
        val directory = java.io.File("build/vless-fixtures").apply { mkdirs() }
        queries.forEach { (name, query) ->
            val parsed = VlessProfile.parse("$base?$query")
            val config = JSONObject(parsed.singBoxConfig("127.0.0.1:9000"))
            assertEquals("127.0.0.1", config.getJSONArray("outbounds").getJSONObject(0).getString("server"))
            java.io.File(directory, "$name.json").writeText(config.toString(), Charsets.UTF_8)
        }
    }

}
