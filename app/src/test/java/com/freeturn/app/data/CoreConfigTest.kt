package com.freeturn.app.data

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.server.ServerOpts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoreConfigTest {
    @Test fun mapsClientSettingsToMobileSchema() {
        val cfg = ClientConfig(
            serverAddress = "1.2.3.4:56000",
            vkLink = "https://vk.ru/call/join/abc",
            threads = 8,
            streamsPerCred = 4,
            tcpForward = true,
            useUdp = true,
            localPort = "127.0.0.1:9001",
            customDns = "8.8.8.8; 1.1.1.1",
            debugMode = true,
        )
        val json = JSONObject(CoreConfig.client(cfg, ServerOpts(), "9.9.9.9", "0123456789abcdef"))
        assertEquals("1.2.3.4:56000", json.getString("peer"))
        assertEquals("0123456789abcdef", json.getString("clientId"))
        assertEquals(8, json.getJSONObject("turn").getInt("n"))
        assertEquals("udp", json.getJSONObject("turn").getString("transport"))
        assertEquals("tcp", json.getJSONObject("proxy").getString("mode"))
        assertEquals("127.0.0.1:9001", json.getJSONObject("proxy").getString("listen"))
        assertEquals("https://vk.ru/call/join/abc", json.getJSONObject("vk").getJSONArray("links").getString(0))
        assertEquals(4, json.getJSONObject("vk").getInt("streamsPerCred"))
        assertEquals("mobile", json.getJSONObject("vk").getString("platform"))
        assertEquals("8.8.8.8", json.getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals("1.1.1.1", json.getJSONObject("dns").getJSONArray("servers").getString(1))
        assertEquals(true, json.getJSONObject("log").getBoolean("debug"))
        assertFalse(json.has("tunnel")) // Existing WireGuard backend still owns the VPN tunnel.
    }

    @Test fun rawModeAcceptsJsonOnly() {
        val cfg = ClientConfig(isRawMode = true, rawCommand = """{"peer":"a:1"}""")
        assertEquals("a:1", JSONObject(CoreConfig.client(cfg, ServerOpts(), null, "id")).getString("peer"))
    }
}
