package com.example.followingcar

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SystemHotspotObservationTest {
    private val observed = SystemHotspotObservation("swlan0", 19, "192.168.57.1", 24)
    private fun link(name: String = "swlan0", index: Int = 19, address: String = "192.168.57.1",
        prefix: Int = 24) = LocalDiscoveryLink(name, index, InetAddress.getByName(address), prefix)

    @Test fun existingSystemApIsUsableWithoutCreatingAnInterfaceOrReservation() {
        val beforeOpeningApp = systemHotspotObservations(listOf(link()))
        val afterOpeningApp = systemHotspotObservations(listOf(link()))
        assertEquals(listOf(observed), afterOpeningApp)
        assertTrue(sameSystemHotspotObservations(beforeOpeningApp, afterOpeningApp))
        // A pre-existing AP is not rejected by the old "new addresses only" rule.
        assertEquals(observed, verifiedSystemHotspotObservation("192.168.57.121", "192.168.57.1",
            24, afterOpeningApp))
    }

    @Test fun p2pVpnCellularLoopbackAndInvalidApAddressesCannotAuthorizeProvisioning() {
        val invalid = listOf(link(name = "p2p0"), link(name = "p2p-wlan0-0"),
            link(name = "tun0"), link(name = "rmnet_data0"), link(name = "lo"),
            link(name = "swlan0evil"), link(index = 0), link(address = "8.8.8.8"),
            link(address = "169.254.1.1"), link(address = "127.0.0.1"), link(prefix = 0),
            link(prefix = 31), link(address = "fe80::1"))
        assertTrue(systemHotspotObservations(invalid).isEmpty())
        assertFalse(sameSystemHotspotObservations(emptyList(), emptyList()))
    }

    @Test fun registeredInterfaceCannotBecomeAnApWhenDiscoveryReturnsAnUnboundFallback() {
        val staleStaFallback = link(name = "wlan0", index = 8, address = "172.16.16.134", prefix = 22)
        val result = systemHotspotObservations(listOf(staleStaFallback, link()), setOf("wlan0", "rmnet0", "tun0"))
        assertEquals(listOf(observed), result)
        assertTrue(systemHotspotObservations(listOf(staleStaFallback), setOf("wlan0")).isEmpty())
    }

    @Test fun interfaceIndexAddressPrefixOrIdentityChangeInvalidatesPreparedWrite() {
        val expected = listOf(observed)
        val replacements = listOf(emptyList(), listOf(observed.copy(interfaceIndex = 20)),
            listOf(observed.copy(address = "192.168.58.1")), listOf(observed.copy(prefixLength = 16)),
            listOf(observed.copy(interfaceName = "ap0")),
            listOf(observed, observed.copy(interfaceIndex = 20, interfaceName = "ap0")))
        for (replacement in replacements) assertFalse(sameSystemHotspotObservations(expected, replacement))
    }

    @Test fun observationOrderDoesNotInventAHotspotChangeAndResultsStayBounded() {
        val second = link(name = "ap0", index = 20, address = "172.20.1.1")
        val first = systemHotspotObservations(listOf(link(), second, link()))
        val reordered = systemHotspotObservations(listOf(second, link()))
        assertEquals(2, first.size)
        assertTrue(sameSystemHotspotObservations(first, reordered))
        assertEquals(2, systemHotspotObservations(listOf(link(), second,
            link(name = "softap0", index = 21, address = "10.1.1.1"))).size)
    }

    @Test fun restartRecoveryUsesCurrentApGatewayAndRequiresNoOldReservationOrCredentials() {
        val restoredProcessObservation = systemHotspotObservations(listOf(link()))
        assertEquals(observed, verifiedSystemHotspotObservation("192.168.57.121", "192.168.57.1",
            24, restoredProcessObservation))
        assertNull(verifiedSystemHotspotObservation("192.168.57.121", "192.168.57.1", 24, emptyList()))
    }

    @Test fun successfulBoardResponseCannotRedirectSshOutsideTheActualApOrToPhoneItself() {
        for ((ip, gateway, prefix) in listOf(
            Triple("172.16.16.82", "192.168.57.1", 24),
            Triple("192.168.57.121", "192.168.57.2", 24),
            Triple("192.168.57.121", "192.168.57.1", 16),
            Triple("192.168.57.1", "192.168.57.1", 24),
            Triple("192.168.57.0", "192.168.57.1", 24),
            Triple("192.168.57.255", "192.168.57.1", 24),
        )) assertNull(verifiedSystemHotspotObservation(ip, gateway, prefix, listOf(observed)))
    }

    @Test fun indistinguishableGatewaysOnTwoInterfacesAreNotRepresentedAsVerified() {
        assertNull(verifiedSystemHotspotObservation("192.168.57.121", "192.168.57.1", 24,
            listOf(observed, observed.copy(interfaceName = "ap0", interfaceIndex = 20))))
    }

    @Test fun oneEmptyEnumerationDoesNotRevokeAnEstablishedApButSustainedAbsenceDoes() {
        val monitor = SystemHotspotContinuity()
        assertFalse(monitor.isLost(observed, emptyList(), 0L))
        assertFalse(monitor.isLost(observed, emptyList(), 100_000_000L))
        assertFalse(monitor.isLost(observed, listOf(observed), 200_000_000L))
        assertFalse(monitor.isLost(observed, emptyList(), 1_000_000_000L))
        assertFalse(monitor.isLost(observed, emptyList(), 1_749_999_999L))
        assertTrue(monitor.isLost(observed, emptyList(), 1_750_000_000L))
    }

    @Test fun actualInterfaceReplacementInvalidatesImmediatelyAndResetDropsOldMissingEvidence() {
        val monitor = SystemHotspotContinuity()
        assertTrue(monitor.isLost(observed, listOf(observed.copy(interfaceIndex = 20)), 0L))
        assertFalse(monitor.isLost(observed, emptyList(), 1_000_000_000L))
        monitor.reset()
        assertFalse(monitor.isLost(observed, emptyList(), 2_000_000_000L))
        // A test/clock discontinuity is not evidence that the interface has been absent longer.
        assertFalse(monitor.isLost(observed, emptyList(), 1_000_000_000L))
    }
}
