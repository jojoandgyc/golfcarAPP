package com.example.followingcar

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class BoardLocalDiscoveryTest {
    private fun attributes(id: String = BOARD_DISCOVERY_ID, fingerprint: String = BOARD_DISCOVERY_SSH_FINGERPRINT) =
        mapOf("id" to id.toByteArray(), "sshfp" to fingerprint.toByteArray())

    @Test fun acceptsTheBoardsTypeAndBothFingerprintRepresentationsOnlyAsHints() {
        assertTrue(matchingBoardAdvertisement("_followingcar._tcp.", attributes()))
        assertTrue(matchingBoardAdvertisement("._followingcar._tcp", attributes(fingerprint = "SHA256:$BOARD_DISCOVERY_SSH_FINGERPRINT")))
        assertFalse(matchingBoardAdvertisement("_ssh._tcp", attributes()))
        assertFalse(matchingBoardAdvertisement("_followingcar._tcp", attributes(id = "other-board")))
        assertFalse(matchingBoardAdvertisement("_followingcar._tcp", attributes(fingerprint = "wrong-key")))
    }

    @Test fun incompleteOrOversizeTxtRecordsDoNotBecomeCandidates() {
        assertFalse(matchingBoardAdvertisement(BOARD_DISCOVERY_SERVICE_TYPE, emptyMap()))
        assertFalse(matchingBoardAdvertisement(BOARD_DISCOVERY_SERVICE_TYPE, attributes(id = "x".repeat(129))))
        assertFalse(matchingBoardAdvertisement(BOARD_DISCOVERY_SERVICE_TYPE,
            mapOf("id" to BOARD_DISCOVERY_ID.toByteArray(), "sshfp" to byteArrayOf(0xff.toByte()))))
    }

    @Test fun rejectsUnspecifiedLoopbackMulticastAndBroadcastAddresses() {
        listOf("0.0.0.0", "127.0.0.1", "224.0.0.251", "255.255.255.255", "::", "::1", "ff02::fb")
            .forEach { assertNull(it, localDiscoveryAddress(InetAddress.getByName(it), 19)) }
    }

    @Test fun scopesLinkLocalIpv6ToReceivingWifiAndRejectsUnknownScope() {
        val address = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 3)
        assertNull(localDiscoveryAddress(address, 0))
        assertTrue(localDiscoveryAddress(address, 19)!!.endsWith("%19"))
        assertFalse(localDiscoveryAddress(address, 19)!!.endsWith("%3"))
        assertFalse(localDiscoveryAddress(InetAddress.getByName("fd00::1234"), 19)!!.contains('%'))
    }

    @Test fun returnsAtMostSixUniqueEndpointsWithIpv4BeforeIpv6() {
        val endpoints = listOf(LocalBoardEndpoint("fe80::1%19"), LocalBoardEndpoint("192.168.1.2"),
            LocalBoardEndpoint("192.168.1.2"), LocalBoardEndpoint("192.168.1.3", 0),
            LocalBoardEndpoint("invalid address")) + (4..12).map { LocalBoardEndpoint("192.168.1.$it") }
        val actual = orderedLocalEndpoints(endpoints)
        assertEquals(6, actual.size)
        assertEquals("192.168.1.2", actual.first().host)
        assertEquals(1, actual.count { it.host == "192.168.1.2" })
        assertTrue(actual.all { ':' !in it.host })
    }

    @Test fun distinctIpv6ScopesAreNotAccidentallyCollapsed() {
        val actual = orderedLocalEndpoints(listOf(LocalBoardEndpoint("fe80::1%19"), LocalBoardEndpoint("fe80::1%20")))
        assertEquals(2, actual.size)
    }

    @Test fun rejectsAdvertisedInternetAndOtherLanAddressesDespiteMatchingTxt() {
        val prefixes = listOf(LocalAddressPrefix(InetAddress.getByName("172.16.16.113"), 22))
        assertTrue(isOnLocalDiscoveryLink(InetAddress.getByName("172.16.16.82"), prefixes))
        assertTrue(isOnLocalDiscoveryLink(InetAddress.getByName("172.16.19.50"), prefixes))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("117.50.34.249"), prefixes))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("192.168.1.82"), prefixes))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("172.16.20.82"), prefixes))
    }

    @Test fun excludesPhoneItselfNetworkAndDirectedBroadcastButPreservesValidDot255Host() {
        val prefixes = listOf(LocalAddressPrefix(InetAddress.getByName("172.16.16.113"), 22))
        listOf("172.16.16.113", "172.16.16.0", "172.16.19.255").forEach {
            assertFalse(it, isOnLocalDiscoveryLink(InetAddress.getByName(it), prefixes))
        }
        assertTrue(isOnLocalDiscoveryLink(InetAddress.getByName("172.16.16.255"), prefixes))
    }

    @Test fun ipv6GlobalAndUlaMustShareAnActualWifiPrefix() {
        val prefixes = listOf(LocalAddressPrefix(InetAddress.getByName("2001:db8:12::1"), 64),
            LocalAddressPrefix(InetAddress.getByName("fd00:55::1"), 64))
        assertTrue(isOnLocalDiscoveryLink(InetAddress.getByName("2001:db8:12::42"), prefixes))
        assertTrue(isOnLocalDiscoveryLink(InetAddress.getByName("fd00:55::42"), prefixes))
        assertTrue(isOnLocalDiscoveryLink(InetAddress.getByName("fe80::42"), prefixes))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("2001:db8:13::42"), prefixes))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("fd00:56::42"), prefixes))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("2001:db8:12::1"), prefixes))
    }

    @Test fun missingPrefixesOrDefaultRouteDoNotAuthorizeAnAdvertisedAddress() {
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("192.168.1.82"), emptyList()))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("fe80::1"), emptyList()))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("8.8.8.8"),
            listOf(LocalAddressPrefix(InetAddress.getByName("192.168.1.100"), 0))))
        assertFalse(isOnLocalDiscoveryLink(InetAddress.getByName("192.168.1.82"),
            listOf(LocalAddressPrefix(InetAddress.getByName("192.168.1.100"), 33))))
    }
}
