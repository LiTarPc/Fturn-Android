package com.freeturn.app.domain.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class LogEntryTest {
    private fun expect(level: LogLevel, vararg lines: String) {
        lines.forEach { assertEquals(it, level, classifyLogLine(it)) }
    }

    @Test fun `successful core connections are green events`() = expect(LogLevel.Success,
        "[INFO] [STREAM 1] Established DTLS connection",
        "[INFO] [STREAM 1] TURN allocation up: relayed=127.0.0.1:1234",
        "[INFO] RAW stream 1 ready: relay=127.0.0.1:1234",
        "[INFO] [session 2] connected (active: 3)",
        "sing-box: WireGuard VPN запущен через локальный FreeTurn (IPv4)",
        "sing-box: VLESS VPN запущен через локальный FreeTurn (IPv4)",
        "VPN подключён", "[VK Auth] Success")

    @Test fun `errors take precedence over successful words`() = expect(LogLevel.Error,
        "[ERROR] RAW stream 1 ready: failed to attach TUN",
        "[VK Auth] Failed", "fatal error: could not connect", "panic: broken pipe",
        "VPN: ошибка запуска", "Не удалось подключиться")

    @Test fun `warnings retain their level`() = expect(LogLevel.Warning,
        "[WARN] connection established with fallback",
        "Watchdog: перезапуск", "Смена сети - переподключение")

    @Test fun `disconnections and negated success are never green`() = expect(LogLevel.Event,
        "[session 2] disconnected (active: 2)", "VPN not connected", "service not started",
        "VPN не подключён", "VPN не подключен")

    @Test fun `ordinary startup and closed streams are never green`() = expect(LogLevel.Plain,
        "Connecting to TURN relay...", "TCP mode: waiting for sessions to connect (total: 4)",
        "RAW VPN: MTU=1280", "[STREAM 1] Closed DTLS connection",
        "RAW stream 1 closed", "[STREAM 1] TURN allocation released", "unsuccessful handshake")

    @Test fun `core 4_2_3 captcha failures are red even with warning severity`() {
        for (outcome in listOf("failed", "request_failed", "rejected", "challenge_repeated",
            "invalid_response", "rate_limited", "service_unavailable", "timed_out")) {
            expect(LogLevel.Error,
                "[WARN] [STREAM 1] [Captcha] event=CAPTCHA_SUBMIT_RESULT outcome=$outcome",
                "[WARN] [STREAM 1] [Captcha] event=CAPTCHA_SOLVE_RESULT mode=manual attempt=2 outcome=$outcome duration=1s")
        }
    }

    @Test fun `only accepted captcha submission is green`() {
        expect(LogLevel.Success,
            "[INFO] [STREAM 1] [Captcha] event=CAPTCHA_SUBMIT_RESULT outcome=accepted")
        expect(LogLevel.Plain,
            "[INFO] [STREAM 1] [Captcha] event=CAPTCHA_SOLVE_RESULT mode=manual attempt=1 outcome=token_received duration=1s",
            "[INFO] [STREAM 1] [Captcha] event=CAPTCHA_SOLVE_STARTED mode=auto attempt=1",
            "[INFO] [STREAM 1] [Captcha] event=CAPTCHA_SUBMIT_RESULT outcome=unknown")
        expect(LogLevel.Warning,
            "[WARN] [STREAM 1] [Captcha] event=CAPTCHA_SOLVE_RESULT mode=manual attempt=1 outcome=cancelled duration=1s")
    }

    @Test fun `provider response text cannot replace captcha outcome`() {
        expect(LogLevel.Error,
            "[WARN] [STREAM 1] [Captcha] event=CAPTCHA_SUBMIT_RESULT outcome=rejected response={\"message\":\"connected successfully\",\"outcome\":\"accepted\"}")
        expect(LogLevel.Success,
            "[INFO] [STREAM 1] [Captcha] event=CAPTCHA_SUBMIT_RESULT outcome=accepted response={\"error_code\":0}")
    }
}
