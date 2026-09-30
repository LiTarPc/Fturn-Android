package com.freeturn.app.data.share

import com.freeturn.app.data.CoreArgs
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.config.VlessProfile
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import com.freeturn.app.data.server.ServerOpts
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VlessImportTest {
    private val uri = "vless://11111111-2222-4333-8444-555555555555@127.0.0.1:8443?encryption=none&security=none&type=tcp#Germany_Xray_Test"
    private val sample = "eyJuYW1lIjogIkdlcm1hbnkgVkxFU1MgVENQIiwgInByb3ZpZGVyIjogInZrIiwgInBlZXIiOiAiMTkyLjAuMi4xOjU2NDExIiwgInRyYW5zcG9ydCI6ICJ0Y3AiLCAibGlua3MiOiAiaHR0cHM6Ly92ay5ydS9jYWxsL2pvaW4vMTIzNDUiLCAib2JmIjogInJ0cG9wdXMiLCAia2V5IjogIjExMjIzMzQ0NTU2Njc3ODg5OWFhYmJjY2RkZWVmZjAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmMDAiLCAiY2lkIjogIjExMTExMTExMjIyMjQzMzM4NDQ0NTU1NTU1NTU1NTU1IiwgInNiIjogInZsZXNzOi8vMTExMTExMTEtMjIyMi00MzMzLTg0NDQtNTU1NTU1NTU1NTU1QDEyNy4wLjAuMTo4NDQzP2VuY3J5cHRpb249bm9uZSZzZWN1cml0eT1ub25lJnR5cGU9dGNwI0dlcm1hbnlfWHJheV9UZXN0In0="

    @Test fun `redacted sample accepts plain and prefixed standard base64`() {
        for (input in listOf(" $sample  ", "freeturn://$sample")) {
            val parsed = FreeturnLink.parse(input).getOrThrow()
            assertEquals(uri, parsed.sbUri)
            assertEquals("https://vk.ru/call/join/12345", parsed.links)
            assertEquals(parsed, FreeturnLink.parse(parsed.encode()).getOrThrow())
            assertTrue(FreeturnLink.looksLikeLink(input))
        }
    }

    @Test fun `runtime config replaces endpoint while preserving imported URI`() {
        val config = JSONObject(VlessProfile.parse(uri).singBoxConfig("127.0.0.1:9000"))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals(9000, outbound.getInt("server_port"))
        assertEquals("127.0.0.1", outbound.getString("server"))
        assertFalse(outbound.has("tls"))
        assertFalse(outbound.has("transport"))
        assertEquals(8443, VlessProfile.parse(uri).port)
    }

    @Test fun `unsupported parameters and invalid UUID fail explicitly`() {
        for (input in listOf(uri.replace("security=none", "security=reality"),
            uri.replace("type=tcp", "type=ws"), uri.replace("encryption=none", "encryption=other"),
            uri.replace("11111111-2222-4333-8444-555555555555", "bad"),
            uri.replace("8443", "65536"), uri.replace("#", "&flow=xtls-rprx-vision#"))) {
            assertTrue(input, runCatching { VlessProfile.parse(input) }.isFailure)
        }
    }

    @Test fun `ambiguous wg and sb is rejected`() {
        val json = JSONObject(String(Base64.getDecoder().decode(sample)))
        json.put("wg", "some config")
        assertTrue(FreeturnLink.parse(Base64.getEncoder().encodeToString(json.toString().toByteArray())).isFailure)
    }

    @Test fun `profile persistence includes VLESS and core always uses TCP forwarding`() {
        val cfg = ClientConfig(vlessUri = uri, tunnelTransport = TunnelTransport.VLESS)
        val server = Server(name = "test", client = cfg)
        assertEquals(cfg, ServerJson.decodeList(ServerJson.encodeList(listOf(server))).single().client)
        val args = CoreArgs.client(cfg, ServerOpts())
        assertEquals("tcp", args[args.indexOf("-mode") + 1])
        assertTrue(cfg.vpnActive)
        assertFalse(cfg.wireGuardActive)
    }
}
