package com.freeturn.app.data.config

import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BypassRuleSetTest {
    private val uri = "vless://11111111-2222-4333-8444-555555555555@127.0.0.1:8443?type=tcp&security=none"
    @Test fun `default RU rules survive persistence and respect a saved disabled configuration`() {
        val config = ClientConfig()
        assertTrue(config.bypassRulesEnabled)
        assertEquals(BypassRuleSet.BUILTIN_RU, config.bypassRuleSets.single().format)
        assertEquals(config, ServerJson.decodeList(ServerJson.encodeList(listOf(Server(name="default",client=config)))).single().client)
        val disabled = config.copy(bypassRulesEnabled=false, bypassRuleSets=emptyList())
        assertEquals(disabled, ServerJson.decodeList(ServerJson.encodeList(listOf(Server(name="off",client=disabled)))).single().client)
        val legacy = ServerJson.decodeList("[{\"name\":\"old\",\"client\":{}}]").single().client
        assertTrue(legacy.bypassRulesEnabled)
        assertEquals(BypassRuleSet.defaults(), legacy.bypassRuleSets)
        val update = BypassRuleSet.fromBytes("ru-aggregated.zone", "5.8.0.0/20".toByteArray())
        assertEquals(listOf(update), BypassRuleSet.add(config.bypassRuleSets,update))
    }
    @Test fun `VLESS uses IPv4 DNS and rejects literal IPv6 inside the TUN`() {
        val json=JSONObject(VlessProfile.parse(uri).singBoxConfig("127.0.0.1:9000"))
        assertEquals("ipv4_only",json.getJSONObject("dns").getString("strategy"))
        val reject=json.getJSONObject("route").getJSONArray("rules").getJSONObject(1)
        assertEquals(6,reject.getInt("ip_version"))
        assertEquals("reject",reject.getString("action"))
        assertEquals("proxy",json.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
    }
    @Test fun `CIDR imports preserve both address families and reject hostnames`() {
        val text = "\uFEFF# comment\n5.8.0.0/20\r\n2001:db8::/32 # test\n5.8.0.0/20\n"
        val rule = BypassRuleSet.fromBytes("ru.zone", text.toByteArray())
        assertEquals(BypassRuleSet.CIDR, rule.format)
        assertEquals(listOf("5.8.0.0/20", "2001:db8::/32"), BypassRuleSet.parseCidrs(rule.bytes().toString(Charsets.UTF_8)))
        for (bad in listOf("example.com/24", "256.1.1.1/24", "1.1.1.1/33", "::/129", "", "1.1.1.1"))
            assertTrue(bad, runCatching { BypassRuleSet.fromBytes("bad.zone", bad.toByteArray()) }.isFailure)
    }
    @Test fun `SRS version and compression are checked before native parsing`() {
        val b = ByteArrayOutputStream().apply { write(byteArrayOf(83,82,83,3)) }
        DeflaterOutputStream(b).use { it.write(byteArrayOf(1,0,-1)) }
        val rule = BypassRuleSet.fromBytes("rule.srs", b.toByteArray())
        assertEquals(BypassRuleSet.BINARY, rule.format)
        assertArrayEquals(b.toByteArray(), rule.bytes())
        assertTrue(runCatching { BypassRuleSet.fromBytes("future.srs", b.toByteArray().apply { this[3]=4 }) }.isFailure)
        assertTrue(runCatching { BypassRuleSet.fromBytes("bad.srs", byteArrayOf(83,82,83,3,1)) }.isFailure)
    }
    @Test fun `enabled bypass preserves proxy DNS and defaults while routing matches direct`() {
        val sets = JSONArray().put(JSONObject().put("type","inline").put("tag","ru").put("rules",
            JSONArray().put(JSONObject().put("ip_cidr",JSONArray().put("5.8.0.0/20")))))
        val json = JSONObject(VlessProfile.parse(uri).singBoxConfig("127.0.0.1:9000", sets))
        val route = json.getJSONObject("route")
        assertEquals("proxy", route.getString("final"))
        assertEquals("hijack-dns", route.getJSONArray("rules").getJSONObject(0).getString("action"))
        assertEquals(53, route.getJSONArray("rules").getJSONObject(0).getInt("port"))
        assertEquals("direct", route.getJSONArray("rules").getJSONObject(3).getString("outbound"))
        assertEquals("ru", route.getJSONArray("rules").getJSONObject(3).getJSONArray("rule_set").getString(0))
        assertEquals("proxy", json.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
        val disabled = JSONObject(VlessProfile.parse(uri).singBoxConfig("127.0.0.1:9000"))
        assertFalse(disabled.getJSONObject("route").has("rule_set"))
        assertEquals(1, disabled.getJSONArray("outbounds").length())
    }
    @Test fun `rules survive profile persistence and refresh replaces named file`() {
        val rule = BypassRuleSet.fromBytes("ru.zone", "5.8.0.0/20".toByteArray())
        val config = ClientConfig(vlessUri=uri, bypassRulesEnabled=true, bypassRuleSets=listOf(rule))
        assertEquals(config, ServerJson.decodeList(ServerJson.encodeList(listOf(Server(name="test", client=config)))).single().client)
        val refreshed = BypassRuleSet.fromBytes("ru.zone", "5.8.0.0/21".toByteArray())
        assertEquals(listOf(refreshed), BypassRuleSet.add(listOf(rule), refreshed))
        assertTrue(runCatching { BypassRuleSet.add((0..7).map { rule.copy(name="$it") }, rule) }.isFailure)
        assertTrue(runCatching { BypassRuleSet.readBounded(ByteArray(20).inputStream(),10) }.isFailure)
    }
}
