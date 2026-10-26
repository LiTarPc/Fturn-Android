package com.freeturn.app.data.config

import com.freeturn.app.data.RuleDownloadTunnel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HotspotRoutingTest {
    @Test fun `RAW cannot fall back to an absent backend`() {
        assertThrows(IllegalArgumentException::class.java) { SingBoxConfig.fromClient(ClientConfig(tunnelTransport = TunnelTransport.RAW)) }
    }
    @Test fun `hotspot adds an authenticated listener without overriding VPN or bypass routes`() {
        val base = SingBoxConfig.build(JSONObject().put("type", "direct").put("tag", "proxy"), JSONArray())
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
