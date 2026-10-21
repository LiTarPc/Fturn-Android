package com.freeturn.app.data.config

import com.freeturn.app.data.RuleDownloadTunnel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HotspotRoutingTest {
    @Test fun `RAW shares rule routing and uses authenticated private SOCKS backend`() {
        val cfg = ClientConfig(tunnelTransport = TunnelTransport.RAW)
        val rules = JSONArray().put(JSONObject().put("tag", "bypass-0").put("type", "inline"))
        val json = JSONObject(SingBoxConfig.fromClient(cfg, rules, RuleDownloadTunnel.Endpoint(23456, "private-secret")))
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", proxy.getString("type"))
        assertEquals("127.0.0.1", proxy.getString("server"))
        assertEquals(23456, proxy.getInt("server_port"))
        assertEquals("private-secret", proxy.getString("password"))
        assertEquals("proxy", json.getJSONObject("route").getString("final"))
        assertEquals("hijack-dns", json.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
        val bypass = json.getJSONObject("route").getJSONArray("rules").getJSONObject(3)
        assertEquals("direct", bypass.getString("outbound"))
        assertEquals("bypass-0", bypass.getJSONArray("rule_set").getString(0))
        assertEquals(cfg.rawMtu, json.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
    }
    @Test fun `RAW cannot fall back to an absent backend`() {
        assertThrows(IllegalArgumentException::class.java) { SingBoxConfig.fromClient(ClientConfig(tunnelTransport = TunnelTransport.RAW)) }
    }
    @Test fun `hotspot adds an authenticated listener without overriding VPN or bypass routes`() {
        val base = SingBoxConfig.fromClient(ClientConfig(tunnelTransport = TunnelTransport.RAW), rawBackend = RuleDownloadTunnel.Endpoint(23456, "private"))
        val hotspot = HotspotConfig(true, 1080, "public-password")
        val result = JSONObject(hotspot.configure(base))
        val inbound = result.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("socks", inbound.getString("type"))
        assertEquals("0.0.0.0", inbound.getString("listen"))
        assertEquals("public-password", inbound.getJSONArray("users").getJSONObject(0).getString("password"))
        val route = result.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        assertEquals("resolve", rules.getJSONObject(0).getString("action"))
        assertEquals("hotspot-in", rules.getJSONObject(0).getJSONArray("inbound").getString(0))
        assertEquals("ipv4_only", rules.getJSONObject(0).getString("strategy"))
        val oldRules = JSONObject(base).getJSONObject("route").getJSONArray("rules")
        assertEquals(oldRules.length() + 1, rules.length())
        for (i in 0 until oldRules.length()) assertEquals(oldRules.getJSONObject(i).toString(), rules.getJSONObject(i + 1).toString())
        assertEquals("proxy", route.getString("final"))
        assertEquals(base, hotspot.copy(enabled = false).configure(base))
    }
    @Test fun `hotspot rejects root ports and missing or oversized authentication`() {
        for (cfg in listOf(HotspotConfig(true, 80, "password"), HotspotConfig(true, 65536, "password"),
            HotspotConfig(true, 1080, ""), HotspotConfig(true, 1080, "x".repeat(256)))) {
            assertThrows(IllegalArgumentException::class.java) { cfg.validate() }
        }
    }
    @Test fun `hotspot credentials and port survive backups with old backup defaults`() {
        val legacy = JSONObject().put("v", 3).put("servers", JSONArray()).put("ownClientId", "a".repeat(32))
        val old = com.freeturn.app.data.backup.SettingsBackup.decode(legacy.toString())
        assertEquals(1080, old.hotspotPort)
        assertEquals("", old.hotspotPassword)
        val data = old.copy(hotspotProxyEnabled = true, hotspotPort = 1088, hotspotPassword = "backup-password")
        val restored = com.freeturn.app.data.backup.SettingsBackup.decode(com.freeturn.app.data.backup.SettingsBackup.encode(data))
        assertEquals(data, restored)
    }

}
