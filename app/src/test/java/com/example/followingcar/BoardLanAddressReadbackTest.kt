package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class BoardLanAddressReadbackTest {
    private fun record(address: String, prefix: Int = 22, iface: String = "wlan0") =
        "3: $iface    inet $address/$prefix brd 172.16.19.255 scope global dynamic noprefixroute $iface\\       valid_lft 80000sec preferred_lft 80000sec"

    @Test fun parsesRealIpOneLineOutputAndDeduplicatesWithFourAddressLimit() {
        val output = listOf(record("172.16.16.116"), record("172.16.16.116")) +
            (117..121).map { record("172.16.16.$it") }
        assertEquals(listOf("172.16.16.116", "172.16.16.117", "172.16.16.118", "172.16.16.119"),
            parseBoardLanAddresses(output.joinToString("\n")))
    }

    @Test fun ignoresOtherInterfacesIpv6AndMalformedRecords() {
        val output = listOf(record("172.16.16.116", iface = "eth0"), record("172.16.16.116", iface = "wlan0:1"),
            "3: wlan0 inet6 fe80::1/64 scope link", "inet 172.16.16.116/22 wlan0", record("172.16.16.116", 0),
            record("172.16.16.116", 33), record("172.16.256.116"), record("172.016.16.116"),
            record("172.16.16"), record("172.16.16.116"))
        assertEquals(listOf("172.16.16.116"), parseBoardLanAddresses(output.joinToString("\n")))
    }

    @Test fun excludesNonPeerRangesNetworkAndDirectedBroadcastAddresses() {
        val rejected = listOf("0.0.0.0", "0.16.16.116", "127.0.0.1", "169.254.1.20", "224.0.0.251",
            "240.1.2.3", "255.255.255.255", "172.16.16.0", "172.16.19.255")
        assertEquals(emptyList<String>(), parseBoardLanAddresses(rejected.joinToString("\n") { record(it) }))
        assertEquals(listOf("172.16.16.255"), parseBoardLanAddresses(record("172.16.16.255")))
    }

    @Test fun rejectsWholeOversizeResponseIncludingUtf8ByteOverflow() {
        assertEquals(emptyList<String>(), parseBoardLanAddresses(record("172.16.16.116") + "\n" + "x".repeat(8192)))
        val utf8Overflow = record("172.16.16.116") + "\n" + "字".repeat(2800)
        assertTrue(utf8Overflow.length < 8192)
        assertEquals(emptyList<String>(), parseBoardLanAddresses(utf8Overflow))
    }

    @Test fun permitsPointToPointAndHostPrefixesWithoutInventingBroadcastRules() {
        assertEquals(listOf("192.168.1.0", "192.168.1.1", "192.168.1.255"),
            parseBoardLanAddresses(listOf(record("192.168.1.0", 31), record("192.168.1.1", 31),
                record("192.168.1.255", 32)).joinToString("\n")))
        assertEquals("ip -o -4 address show dev wlan0", BOARD_LAN_ADDRESSES_COMMAND)
    }
}
