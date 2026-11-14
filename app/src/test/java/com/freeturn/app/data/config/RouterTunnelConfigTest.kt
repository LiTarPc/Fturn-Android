package com.freeturn.app.data.config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouterTunnelConfigTest {
    private val configured = RouterTunnelConfig(true, "10.42.0.1", "10.42.0.2")
    @Test fun `disabled receiver leaves routing unchanged`() {
        val json = "{\"inbounds\":[],\"route\":{\"final\":\"proxy\"}}"
        assertEquals(json, RouterTunnelConfig().configure(json))
    }
    @Test fun `external IP packets share existing VPN and bypass rules`() {
        val original = JSONObject(SingBoxConfig.build(JSONObject().put("type", "direct").put("tag", "proxy"), JSONArray()))
        val configuredJson = JSONObject(configured.configure(original.toString()))
        assertEquals(original.getJSONObject("route").toString(), configuredJson.getJSONObject("route").toString())
        assertEquals(original.getJSONArray("outbounds").toString(), configuredJson.getJSONArray("outbounds").toString())
        assertEquals(original.getJSONObject("dns").toString(), configuredJson.getJSONObject("dns").toString())
        val input = configuredJson.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("fturn-fou", input.getString("type"))
        assertEquals("10.42.0.1", input.getString("listen"))
        assertEquals("10.42.0.2", input.getString("peer"))
        assertEquals("192.168.1.0/24", input.getJSONArray("source_prefixes").getString(0))
    }
    @Test fun `reject wildcard public hostname and oversized prefix`() {
        for (bad in listOf(configured.copy(listen = "0.0.0.0"), configured.copy(listen = "example.com"),
            configured.copy(peer = "8.8.8.8"), configured.copy(peer = configured.listen),
            configured.copy(port = 53), configured.copy(mtu = 1500), configured.copy(sourceCidr = "0.0.0.0/0"),
            configured.copy(sourceCidr = "192.168.1.7/24"), configured.copy(sourceCidr = "172.16.0.0/8"),
            configured.copy(sourceCidr = "10.0.0.0/7"), configured.copy(sourceCidr = "10.0.0.0/33"))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
    }
    @Test fun `persisted config round trips and malformed data disables receiver`() {
        assertEquals(configured, RouterTunnelConfig.decode(configured.encode()))
        assertFalse(RouterTunnelConfig.decode("broken").enabled)
        assertFalse(RouterTunnelConfig.decode(configured.copy(listen = "0.0.0.0").encode()).enabled)
    }
    @Test fun `automatic receiver needs no saved USB addresses and keeps its pairing secret`() {
        val key = RouterTunnelConfig.newPairingKey()
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(key, RouterTunnelConfig.newPairingKey())
        val config = RouterTunnelConfig(enabled = true, automatic = true, pairingKey = key)
        config.validate()
        assertEquals(config, RouterTunnelConfig.decode(config.encode()))
        val inbound = JSONObject(config.configure("{\"inbounds\":[]}")).getJSONArray("inbounds").getJSONObject(0)
        assertTrue(inbound.getBoolean("automatic"))
        assertEquals(key, inbound.getString("pairing_key"))
        assertEquals(40081, inbound.getInt("control_port"))
        assertEquals(1350, inbound.getInt("mtu"))
        for (bad in listOf(config.copy(pairingKey = ""), config.copy(port = 40081), config.copy(peerPort = 40081))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
            assertFalse(RouterTunnelConfig.decode(bad.encode()).enabled)
        }
    }
    @Test fun `older manual settings keep their saved MTU and do not enable automatic pairing`() {
        val old = "{\"enabled\":true,\"listen\":\"10.42.0.1\",\"peer\":\"10.42.0.2\",\"mtu\":1280}"
        val decoded = RouterTunnelConfig.decode(old)
        assertTrue(decoded.enabled)
        assertFalse(decoded.automatic)
        assertEquals(1280, decoded.mtu)
    }

}
