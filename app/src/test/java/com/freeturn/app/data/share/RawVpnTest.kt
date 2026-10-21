package com.freeturn.app.data.share

import com.freeturn.app.data.config.*
import com.freeturn.app.data.server.*
import com.freeturn.app.domain.proxy.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RawVpnTest {
    private fun wire(v: Int = 1) = JSONObject().put("v", v).put("server", "192.0.2.1:56010")
        .put("vk", "https://vk.ru/call/join/test").put("key", "ab".repeat(32))
        .put("mtu", 1420).put("streams", 10).put("mode", "full").apply {
            if (v >= 2) put("streamsPerCred", 4)
            if (v >= 3) { put("transport", "udp"); put("obfProfile", "rtpopus3") }
            if (v >= 4) put("uplink", "tcp-flow")
        }
    private fun link(o: JSONObject) = "fturnraw://" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    private fun config() = ClientConfig(serverAddress = "192.0.2.1:56010", vkLink = "https://vk.ru/call/join/test",
        tunnelTransport = TunnelTransport.RAW, rawMtu = 1420, rawUplink = "tcp-flow", tcpForward = true)

    @Test fun `Windows links v1 through v4 preserve transport parameters`() {
        for (v in 1..4) {
            val text = link(wire(v))
            assertTrue(FreeturnLink.looksLikeLink(text))
            val decoded = FreeturnLink.parse(text).getOrThrow()
            assertTrue(decoded.rawVpn)
            assertEquals(1420, decoded.rawMtu)
            assertEquals(if (v >= 2) 4 else 5, decoded.streamsPerCred)
            assertEquals(if (v >= 3) "udp" else "tcp", decoded.transport)
            assertEquals(if (v >= 4) "tcp-flow" else "packet", decoded.rawUplink)
        }
    }
    @Test fun `malformed or future RAW profiles fail before startup`() {
        for (bad in listOf(wire(5), wire().put("key", "bad"), wire().put("mtu", 1501),
            wire().put("streams", 65), wire().put("extra", true), wire().put("uplink", "tcp-flow"),
            wire(4).put("uplink", "unknown"), wire(3).put("transport", "invalid"))) {
            assertTrue(FreeturnLink.parse(link(bad)).isFailure)
        }
    }
    @Test fun `RAW arguments never start legacy TCP or embedded tunnels`() {
        val cfg = config()
        assertTrue(cfg.vpnActive)
        assertFalse(cfg.coreTcpForward)
        val args = RawVpnConfig.args(cfg, ServerOpts("rtpopus3", "ab".repeat(32)), "a".repeat(32), "8.8.8.8")
        assertEquals("12", args[args.indexOf("-streams-per-cred") + 1])
        assertTrue(args.contains("-links"))
        assertFalse(args.contains("-link"))
        assertFalse(com.freeturn.app.data.CoreArgs.redactForLog(args, true).contains(cfg.vkLink))
        assertFalse(args.contains("-mode"))
        assertFalse(args.contains("-tunnel"))
        assertEquals("1420", args[args.indexOf("-raw-mtu") + 1])
        assertEquals("tcp-flow", args[args.indexOf("-raw-uplink") + 1])
        assertEquals("mobile", args[args.indexOf("-platform") + 1])
        assertThrows(IllegalArgumentException::class.java) { RawVpnConfig.args(cfg, ServerOpts(), "", null) }
    }
    @Test fun `RAW profile survives preference serialization`() {
        val cfg = config().copy(rawAddress = "10.77.0.3/24")
        val server = Server(name = "RAW", client = cfg)
        assertEquals(cfg, ServerJson.decodeList(ServerJson.encodeList(listOf(server))).single().client)
    }
    @Test fun `RAW needs handshake and tracks reconnect without double counting`() {
        val tracker = CoreConnectionTracker(10, false)
        CoreLogParser.parse("RAW stream 1 ready: relay=test").forEach(tracker::apply)
        CoreLogParser.parse("RAW stream 1 ready: relay=test").forEach(tracker::apply)
        assertEquals(1, tracker.active)
        CoreLogParser.parse("RAW stream 1 closed").forEach(tracker::apply)
        assertFalse(tracker.hasConnection)
        CoreLogParser.parse("RAW stream 1: retrying").forEach(tracker::apply)
        assertFalse(tracker.hasConnection)
        CoreLogParser.parse("RAW stream 1 ready: relay=test").forEach(tracker::apply)
        assertTrue(tracker.hasConnection)
    }
    @Test fun `RAW validates address and total stream limits`() {
        for (cfg in listOf(config().copy(rawAddress = "bad"), config().copy(rawAddress = "10.77.0.2/99"),
            config().copy(rawAddress = "256.77.0.2/24"), config().copy(rawMtu = 575),
            config().copy(threads = 64, vkLink = "https://vk.ru/call/join/a,https://vk.ru/call/join/b"))) {
            assertThrows(IllegalArgumentException::class.java) { RawVpnConfig.validate(cfg) }
        }
    }
}
