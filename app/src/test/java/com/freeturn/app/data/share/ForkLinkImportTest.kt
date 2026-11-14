package com.freeturn.app.data.share

import com.freeturn.app.viewmodel.share.ImportUiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Wire fixtures follow hackdiaz-dev/turn-proxy-android at bab60c5, not our encoder. */
class ForkLinkImportTest {
    private val call = "https://vk.ru/call/join/compatibility-fixture"
    private fun wire(json: JSONObject, scheme: Boolean = true): String =
        (if (scheme) "freeturn://" else "") + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    private fun profile() = JSONObject().put("v", 1).put("provider", "vk")
        .put("peer", "192.0.2.10:56000").put("transport", "tcp").put("n", 12)
        .put("spc", 6).put("name", "Профиль из форка")

    @Test fun `fork vk field supplies call link and allows ordinary import`() {
        for (scheme in listOf(true, false)) {
            val link = FreeturnLink.parse(wire(profile().put("vk", "  $call  "), scheme)).getOrThrow()
            assertEquals(call, link.links)
            assertEquals("192.0.2.10:56000", link.peer)
            assertEquals(6, link.streamsPerCred)
            assertTrue(FreeturnLink.looksLikeLink(wire(profile().put("vk", call), scheme)))
            val state = ImportUiState(link = link, vkLink = link.links)
            assertTrue(state.canConfirm)
            assertFalse(state.needsCallLink)
        }
    }

    @Test fun `missing or empty vk opens input and blocks confirmation until supplied`() {
        for (json in listOf(profile(), profile().put("vk", ""), profile().put("vk", "  "))) {
            val link = FreeturnLink.parse(wire(json)).getOrThrow()
            val state = ImportUiState(link = link, vkLink = link.links)
            assertTrue(state.needsCallLink)
            assertFalse(state.canConfirm)
            assertTrue(state.copy(vkLink = call).canConfirm)
            assertTrue(state.copy(vkLink = call).needsCallLink)
            assertFalse(state.copy(vkLink = "  ").canConfirm)
        }
    }

    @Test fun `existing links format wins while empty links permits fork vk alias`() {
        val oldCall = "https://vk.com/call/join/existing-fixture"
        assertEquals(oldCall, FreeturnLink.parse(wire(profile().put("links", oldCall).put("vk", call))).getOrThrow().links)
        assertEquals(call, FreeturnLink.parse(wire(profile().put("links", " ").put("vk", call))).getOrThrow().links)
        val existing = FreeturnLink(provider = "vk", peer = "192.0.2.10:56000", links = oldCall)
        assertEquals(existing, FreeturnLink.parse(existing.encode()).getOrThrow())
    }

    @Test fun `fork WG and relay parameters survive vk alias import`() {
        val wg = "[Interface]\nAddress = 10.13.13.3/32\n[Peer]\nEndpoint = 127.0.0.1:9000"
        val link = FreeturnLink.parse(wire(profile().put("vk", call).put("wg", wg)
            .put("mode", "udp").put("obf", "rtpopus3").put("key", "12".repeat(32))
            .put("cid", "fixture-client").put("mcap", true))).getOrThrow()
        assertEquals(wg, link.wgConf)
        assertEquals("rtpopus3", link.obfProfile)
        assertEquals("12".repeat(32), link.obfKey)
        assertEquals("fixture-client", link.clientId)
        assertTrue(link.manualCaptcha)
        assertEquals(call, link.links)
    }
}
