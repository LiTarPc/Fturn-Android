package com.freeturn.app.domain.proxy

import org.junit.Assert.*
import org.junit.Test

class ProxyLogPrivacyTest {
    @Test fun displayedLogsAreSanitizedEvenWhenPrivacyModeIsNotSelected() {
        ProxyServiceState.setLogsEnabled(true)
        ProxyServiceState.clearLogs()
        ProxyServiceState.addLog("[WARN] [Captcha Proxy] header: Cookie = a=display-cookie; b=other-cookie")
        ProxyServiceState.addLog("[WARN] session_token=display-token reason=timeout duration_ms=35000")
        val text = ProxyServiceState.logs.value.joinToString("\n") { it.text }
        listOf("display-cookie", "other-cookie", "display-token").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("reason=timeout duration_ms=35000"))
        assertTrue(ProxyServiceState.logs.value.all { it.level == LogLevel.Warning })
        ProxyServiceState.clearLogs()
    }
}
