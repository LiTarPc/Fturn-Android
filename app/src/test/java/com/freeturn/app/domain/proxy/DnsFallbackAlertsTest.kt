package com.freeturn.app.domain.proxy

import org.junit.Assert.*
import org.junit.Test

class DnsFallbackAlertsTest {
    @Test fun `repeated fallback logs and core retries produce one alert`() {
        val alerts = DnsFallbackAlerts()
        assertTrue(alerts.mark(DnsFallback.DOH))
        repeat(10) { assertFalse(alerts.mark(DnsFallback.DOH)) }
    }

    @Test fun `missing network DNS and later DoH are distinct transitions`() {
        val alerts = DnsFallbackAlerts()
        assertTrue(alerts.mark(DnsFallback.CARRIER_UNAVAILABLE))
        assertTrue(alerts.mark(DnsFallback.DOH))
        assertFalse(alerts.mark(DnsFallback.CARRIER_UNAVAILABLE))
        assertFalse(alerts.mark(DnsFallback.DOH))
    }

    @Test fun `new connection or network handover can alert again`() {
        val alerts = DnsFallbackAlerts()
        alerts.mark(DnsFallback.DOH)
        alerts.reset()
        assertTrue(alerts.mark(DnsFallback.DOH))
    }
}
