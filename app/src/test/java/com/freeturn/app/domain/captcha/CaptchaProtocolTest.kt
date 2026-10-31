package com.freeturn.app.domain.captcha

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptchaProtocolTest {
    private val generation = "a".repeat(32)
    private val requestId = "b".repeat(32)
    private val now = 1_700_000_000_000L
    private fun challenge() = JSONObject().put("v", 1).put("type", "captcha").put("generation", generation)
        .put("ipc_port", 12345).put("request_id", requestId).put("mode", "browser")
        .put("phase", "pending").put("expires_at_ms", now + 180_000)
    private fun parse(json: JSONObject) = CaptchaProtocol.parse(CaptchaProtocol.PREFIX + json, generation, now)

    @Test fun challengeAndUserWaitHaveTypedMetadataOnly() {
        val parsed = parse(challenge()) as CaptchaProtocol.Result.Accepted
        val event = parsed.event as CaptchaProtocol.Challenge
        assertEquals(requestId, event.requestId)
        assertEquals(CaptchaProtocol.Mode.BROWSER, event.mode)
        assertEquals(CaptchaProtocol.Phase.PENDING, event.phase)
        val auth = JSONObject().put("v", 1).put("type", "auth").put("generation", generation)
            .put("ipc_port", 12345).put("phase", "waiting_user")
        assertEquals(CaptchaProtocol.AuthPhase.WAITING_USER,
            ((parse(auth) as CaptchaProtocol.Result.Accepted).event as CaptchaProtocol.Auth).phase)
        auth.put("phase", "authorized")
        assertEquals(CaptchaProtocol.AuthPhase.AUTHORIZED,
            ((parse(auth) as CaptchaProtocol.Result.Accepted).event as CaptchaProtocol.Auth).phase)
    }
    @Test fun staleMalformedAndExpiredEventsCannotLoadAPage() {
        listOf(challenge().put("generation", "old-process"), challenge().put("v", 2),
            challenge().put("ipc_port", 0), challenge().put("request_id", "../../private"),
            challenge().put("expires_at_ms", now), challenge().put("expires_at_ms", now + 900_000),
            challenge().put("mode", "unknown")).forEach { assertTrue(parse(it) is CaptchaProtocol.Result.Rejected) }
        assertTrue(CaptchaProtocol.parse("FTURN_EVENT invalid secret-value", generation, now) is CaptchaProtocol.Result.Rejected)
        assertEquals(CaptchaProtocol.Result.NotEvent, CaptchaProtocol.parse("RAW stream 1 ready:", generation, now))
    }
    @Test fun rejectedPayloadNeverAppearsInResultOrError() {
        val raw = "FTURN_EVENT {\"url\":\"http://localhost:8765/secret-page\",\"token\":\"secret-token\"}"
        val result = CaptchaProtocol.parse(raw, generation, now)
        assertTrue(result is CaptchaProtocol.Result.Rejected)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("localhost"))
    }
    @Test fun pageOriginRejectsExternalCredentialsAndAmbiguousLocations() {
        listOf("https://example.com/check", "http://localhost.evil:1234/check", "http://127.0.0.1:8766/check", "http://user:pass@127.0.0.1:1234/check",
            "file:///private", "http://127.0.0.1/check", "http://127.0.0.1:1234/check#secret").forEach {
            assertFalse(it, CaptchaIpcClient.isLocalPage(it))
        }
        assertTrue(CaptchaIpcClient.isLocalPage("http://127.0.0.1:8765/check?opaque=value"))
        assertTrue(CaptchaIpcClient.sameOrigin("http://127.0.0.1:8765/a", "http://127.0.0.1:8765/b"))
        assertFalse(CaptchaIpcClient.sameOrigin("http://127.0.0.1:8765/a", "http://127.0.0.1:8766/b"))
    }
}
