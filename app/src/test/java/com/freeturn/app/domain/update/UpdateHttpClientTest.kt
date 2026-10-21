package com.freeturn.app.domain.update

import com.freeturn.app.data.RuleDownloadTunnel
import org.junit.Assert.*
import org.junit.Test

class UpdateHttpClientTest {
    @Test fun `active VPN endpoint is preferred even when direct internet is available`() {
        val endpoint = RuleDownloadTunnel.Endpoint(32001, "test")
        assertSame(endpoint, UpdateHttpClient.selectRoute(endpoint, true))
        assertSame(endpoint, UpdateHttpClient.selectRoute(endpoint, false))
    }

    @Test fun `VPN without a working download channel cannot silently use direct internet`() {
        assertThrows(IllegalStateException::class.java) { UpdateHttpClient.selectRoute(null, true) }
    }

    @Test fun `direct downloads are available when VPN is off`() {
        assertNull(UpdateHttpClient.selectRoute(null, false))
    }
}
