package com.freeturn.app.domain.proxy

import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkSelectionTest {
    private fun network(id: String, rank: Int, dns: List<String> = listOf("192.0.2.53"),
        vpn: Boolean = false, validated: Boolean = true, internet: Boolean = true) =
        PhysicalNetworkSelection.Candidate(id, id, dns, rank, internet, vpn, validated)

    @Test fun `default cellular keeps its DNS even while WiFi is available`() {
        val wifi = network("wifi", 1)
        val cell = network("cell", 2, listOf("198.51.100.53"))
        assertEquals(cell, PhysicalNetworkSelection.select("cell", listOf(wifi, cell)))
    }

    @Test fun `VPN DNS is never used as carrier DNS`() {
        val vpn = network("tun", 0, listOf("172.19.0.2"), vpn = true)
        val wifi = network("wifi", 1)
        assertEquals(wifi, PhysicalNetworkSelection.select("tun", listOf(vpn, wifi)))
    }

    @Test fun `validated cellular beats unvalidated standby WiFi behind VPN`() {
        val wifi = network("wifi", 1, validated = false)
        val cell = network("cell", 2)
        assertEquals(cell, PhysicalNetworkSelection.select("tun", listOf(wifi, cell)))
    }

    @Test fun `missing DNS on the selected link is not replaced by standby DNS`() {
        val cell = network("cell", 2, emptyList())
        val wifi = network("wifi", 1)
        assertTrue(PhysicalNetworkSelection.select("cell", listOf(cell, wifi))!!.dnsServers.isEmpty())
    }

    @Test fun `lost network is excluded from both interface and DNS selection`() {
        val wifi = network("wifi", 1)
        val cell = network("cell", 2, listOf("198.51.100.53"))
        assertEquals(cell, PhysicalNetworkSelection.select("wifi", listOf(wifi, cell), "wifi"))
    }

    @Test fun `VPN only and non internet networks yield no carrier DNS`() {
        val vpn = network("tun", 0, vpn = true)
        val wifi = network("wifi", 1, internet = false)
        assertNull(PhysicalNetworkSelection.select("tun", listOf(vpn, wifi)))
    }
}
