package com.freeturn.app.data.config

import com.freeturn.app.data.CoreArgs
import com.freeturn.app.data.server.ServerOpts
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WireGuardProfileTest {
    private val privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 33).toByte() })
    private val psk = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 65).toByte() })
    private val raw = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 10.8.0.1, 1.1.1.1
        MTU = 1420
        [Peer]
        PublicKey = $publicKey
        PresharedKey = $psk
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 192.0.2.1:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun `WG keys addresses and peer options survive endpoint conversion`() {
        val json = JSONObject(WireGuardProfile.parse(raw).singBoxConfig("127.0.0.1:9000"))
        val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertFalse(endpoint.getBoolean("system"))
        assertEquals(privateKey, endpoint.getString("private_key"))
        assertEquals("10.8.0.2/32", endpoint.getJSONArray("address").getString(0))
        assertEquals(1, endpoint.getJSONArray("address").length())
        assertEquals(1280, endpoint.getInt("mtu"))
        val peer = endpoint.getJSONArray("peers").getJSONObject(0)
        assertEquals(publicKey, peer.getString("public_key"))
        assertEquals(psk, peer.getString("pre_shared_key"))
        assertEquals("127.0.0.1", peer.getString("address"))
        assertEquals(9000, peer.getInt("port"))
        assertEquals(25, peer.getInt("persistent_keepalive_interval"))
        assertEquals("[\"0.0.0.0/0\"]", peer.getJSONArray("allowed_ips").toString())
        assertTrue(raw.contains("192.0.2.1:51820"))
        val dns = json.getJSONObject("dns")
        assertEquals("ipv4_only", dns.getString("strategy"))
        assertEquals("10.8.0.1", dns.getJSONArray("servers").getJSONObject(0).getString("server"))
        assertEquals("proxy", dns.getJSONArray("servers").getJSONObject(0).getString("detour"))
    }

    @Test fun `DNS and IPv6 policy precede RU bypass and partial allowed networks`() {
        val sets = JSONArray().put(JSONObject().put("tag", "ru").put("type", "inline")
            .put("rules", JSONArray().put(JSONObject().put("ip_cidr", JSONArray().put("5.255.0.0/16")))))
        val partial = raw.replace("0.0.0.0/0, ::/0", "10.8.0.0/24")
        val route = JSONObject(WireGuardProfile.parse(partial).singBoxConfig("127.0.0.1:9000", sets)).getJSONObject("route")
        val rules = route.getJSONArray("rules")
        assertEquals("hijack-dns", rules.getJSONObject(0).getString("action"))
        assertEquals(6, rules.getJSONObject(1).getInt("ip_version"))
        assertEquals("reject", rules.getJSONObject(1).getString("action"))
        assertEquals("direct", rules.getJSONObject(3).getString("outbound"))
        assertEquals("10.8.0.0/24", rules.getJSONObject(4).getJSONArray("ip_cidr").getString(0))
        assertEquals("proxy", rules.getJSONObject(4).getString("outbound"))
        assertEquals("direct", route.getString("final"))
    }

    @Test fun `WG forces UDP forwarding even after switching from VLESS`() {
        val cfg = ClientConfig(tunnelTransport = TunnelTransport.WIREGUARD, wireGuardConfig = raw, tcpForward = true)
        assertFalse(cfg.coreTcpForward)
        assertFalse(CoreArgs.client(cfg, ServerOpts()).contains("-mode"))
        assertTrue(cfg.copy(tunnelTransport = TunnelTransport.VLESS).coreTcpForward)
    }

    @Test fun `invalid WG keys fail without disclosing key material`() {
        val error = runCatching { WireGuardProfile.parse(raw.replace(privateKey, "SECRET_INVALID_KEY")) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("SECRET_INVALID_KEY"))
        assertNull(error.cause)
        assertTrue(runCatching { WireGuardProfile.parse(raw.replace("10.8.0.2/32, ", "")) }.isFailure)
    }

    @Test fun `native errors redact base64 and hexadecimal WG keys`() {
        val cfg = ClientConfig(wireGuardConfig = raw)
        val hex = Base64.getDecoder().decode(privateKey).joinToString("") { "%02x".format(it) }
        val result = VpnLogRedactor(cfg).redact("private_key=$hex psk=$psk public=$publicKey")
        assertFalse(result.contains(hex))
        assertFalse(result.contains(psk))
        assertFalse(result.contains(publicKey))
    }

    @Test fun `second peer endpoint is retained and domain bootstrap cannot recurse into WG`() {
        val multi = raw + "\n[Peer]\nPublicKey = $psk\nAllowedIPs = 192.168.10.0/24\nEndpoint = wg.example.org:51821\n"
        val json = JSONObject(WireGuardProfile.parse(multi).singBoxConfig("127.0.0.1:9000"))
        val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wg.example.org", endpoint.getJSONArray("peers").getJSONObject(1).getString("address"))
        assertEquals("bootstrap", endpoint.getJSONObject("domain_resolver").getString("server"))
    }
}
