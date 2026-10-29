package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class WifiDirectModelsTest {
    private val device = WifiDirectPeer("private-board-name", "12:34:56:78:90:ab", "192.168.49.27", 3)
    private fun available() = WifiDirectState(supported = true, permissionGranted = true,
        wifiEnabled = true, groupInfoKnown = true, peers = listOf(device))
    private fun owner() = available().copy(groupExists = true, isGroupOwner = true,
        networkName = "DIRECT-test-private-name", passphrase = "private-group-password",
        ownerAddress = "192.168.49.1", ownerPrefixLength = 24)

    @Test fun stateAndPeerToStringNeverExposeIdentitiesOrCredentials() {
        val state = owner().copy(clients = listOf(device))
        val text = state.toString() + device.toString()
        listOf(device.name, device.address, device.ipAddress!!, state.networkName!!,
            state.passphrase!!, state.ownerAddress!!).forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("clientCount=1"))
    }

    @Test fun everyMutationRequiresCurrentControlPermissionAndIdleFramework() {
        assertNull(wifiDirectMutationError(available(), true))
        assertNotNull(wifiDirectMutationError(available(), false))
        assertNotNull(wifiDirectMutationError(available().copy(busy = true), true))
        assertNotNull(wifiDirectMutationError(available().copy(permissionGranted = false), true))
        assertNotNull(wifiDirectMutationError(available().copy(supported = false), true))
        assertNotNull(wifiDirectMutationError(available().copy(wifiEnabled = false), true))
        assertNotNull(wifiDirectMutationError(available().copy(confirmationRequired = true), true))
        // The same guard is called again after asynchronous preflight, so a new SSH session wins.
        assertNull(wifiDirectMutationError(owner(), true))
        assertNotNull(wifiDirectMutationError(owner(), false))
    }

    @Test fun creatingPhoneGroupNeverReplacesAnExistingOwnerOrClientGroup() {
        assertNull(wifiDirectGroupMutationError(available(), WifiDirectAction.CREATE))
        assertNotNull(wifiDirectGroupMutationError(owner(), WifiDirectAction.CREATE))
        assertNotNull(wifiDirectGroupMutationError(owner().copy(isGroupOwner = false), WifiDirectAction.CREATE))
        assertNotNull(wifiDirectGroupMutationError(available().copy(groupInfoKnown = false), WifiDirectAction.CREATE))
    }

    @Test fun invitationRequiresConfirmedPhoneOwnerAndAnExplicitDiscoveredPeer() {
        assertNull(wifiDirectGroupMutationError(owner(), WifiDirectAction.INVITE, device.address))
        assertNotNull(wifiDirectGroupMutationError(available(), WifiDirectAction.INVITE, device.address))
        assertNotNull(wifiDirectGroupMutationError(owner().copy(isGroupOwner = false), WifiDirectAction.INVITE, device.address))
        assertNotNull(wifiDirectGroupMutationError(owner().copy(groupInfoKnown = false), WifiDirectAction.INVITE, device.address))
        assertNotNull(wifiDirectGroupMutationError(owner().copy(peers = emptyList()), WifiDirectAction.INVITE, device.address))
        assertNotNull(wifiDirectGroupMutationError(owner().copy(clients = listOf(device)), WifiDirectAction.INVITE, device.address))
    }

    @Test fun invalidOrRedactedPeerAddressesCannotBeInvited() {
        listOf<String?>(null, "", "host-name", "02:00:00:00:00:00", "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff", "01:00:00:00:00:01", "12:34:56:78:90:abcdef").forEach {
            assertFalse(validWifiDirectPeerAddress(it))
        }
        assertTrue(validWifiDirectPeerAddress("12:34:56:78:90:AB"))
    }

    @Test fun groupReadyRequiresAgreementBetweenGroupAndConnectionQueries() {
        assertEquals(WifiDirectGroupObservation(false, true, false), wifiDirectGroupObservation(false, false, false, false))
        assertEquals(WifiDirectGroupObservation(true, true, true), wifiDirectGroupObservation(true, true, true, true))
        assertEquals(WifiDirectGroupObservation(true, true, false), wifiDirectGroupObservation(true, false, true, false))
        listOf(wifiDirectGroupObservation(true, true, false, false),
            wifiDirectGroupObservation(false, false, true, true),
            wifiDirectGroupObservation(true, true, true, false)).forEach {
            assertTrue(it.exists)
            assertFalse(it.known)
            assertFalse(it.owner)
        }
    }

    @Test fun acceptedActionWithoutObservedResultDoesNotConfirmGroupOrInvitation() {
        assertFalse(wifiDirectActionConfirmed(WifiDirectAction.CREATE, available()))
        assertFalse(wifiDirectActionConfirmed(WifiDirectAction.CREATE, owner().copy(groupInfoKnown = false)))
        assertTrue(wifiDirectActionConfirmed(WifiDirectAction.CREATE, owner()))
        assertFalse(wifiDirectActionConfirmed(WifiDirectAction.INVITE,
            owner().copy(peers = listOf(device.copy(status = 0))), device.address))
        assertTrue(wifiDirectActionConfirmed(WifiDirectAction.INVITE,
            owner().copy(clients = listOf(device.copy(status = 0))), device.address))
        assertFalse(wifiDirectActionConfirmed(WifiDirectAction.REMOVE, owner()))
        assertTrue(wifiDirectActionConfirmed(WifiDirectAction.REMOVE, available()))
    }

    @Test fun clearingPermissionOrChannelStateRemovesAllSensitiveAndStaleGroupData() {
        val cleared = owner().copy(busy = true, discovering = true, clients = listOf(device), confirmationRequired = true)
            .withoutSensitiveWifiDirectData()
        assertTrue(cleared.supported)
        assertFalse(cleared.busy)
        assertFalse(cleared.groupInfoKnown)
        assertFalse(cleared.groupExists)
        assertFalse(cleared.isGroupOwner)
        assertNull(cleared.passphrase)
        assertNull(cleared.networkName)
        assertNull(cleared.ownerAddress)
        assertNull(cleared.ownerPrefixLength)
        assertTrue(cleared.clients.isEmpty())
        assertTrue(cleared.peers.isEmpty())
        assertTrue("Clearing private data cannot acknowledge a pending system operation", cleared.confirmationRequired)
    }

    @Test fun timedOutMutationNeedsExplicitFreshGroupAndDiscoveryConfirmation() {
        val uncertain = owner().copy(confirmationRequired = true, peers = emptyList())
        assertFalse(wifiDirectRefreshConfirmsMutation(uncertain, false, true, true))
        assertFalse(wifiDirectRefreshConfirmsMutation(uncertain, true, false, true))
        assertFalse(wifiDirectRefreshConfirmsMutation(uncertain, true, true, false))
        assertFalse(wifiDirectRefreshConfirmsMutation(uncertain.copy(groupInfoKnown = false), true, true, true))
        assertFalse(wifiDirectRefreshConfirmsMutation(uncertain.copy(permissionGranted = false), true, true, true))
        assertTrue("A restricted peers list does not block group/discovery confirmation",
            wifiDirectRefreshConfirmsMutation(uncertain, true, true, true))
        assertTrue(wifiDirectRefreshConfirmsMutation(available().copy(confirmationRequired = true), true, true, true))
        assertTrue("An explicitly confirmed disabled radio cannot retain a live network operation",
            wifiDirectRefreshConfirmsMutation(uncertain.copy(wifiEnabled = false, groupInfoKnown = false), true, true, true))
    }

    @Test fun clientAddressUsesRealClientIpAndNeverFallsBackToPhoneOwnerIp() {
        assertEquals("192.168.49.27", usableWifiDirectClientAddress(device.ipAddress, "192.168.49.1"))
        assertNull(usableWifiDirectClientAddress("192.168.49.1", "192.168.49.1"))
        assertNull(usableWifiDirectClientAddress(null, "192.168.49.1"))
        assertNull(usableWifiDirectClientAddress("", "192.168.49.1"))
        assertEquals("fd00::27", usableWifiDirectClientAddress("fd00::27", "fd00::1"))
        assertNull(usableWifiDirectClientAddress("fd00:0:0:0:0:0:0:1", "fd00::1"))
    }

    @Test fun clientAddressRejectsNamesMalformedAndNonUnicastAddresses() {
        listOf("localhost", "board.local", "192.168.49.27.example.com", "192.168.1.999", "127.0.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "::", "::1", "ff02::1", "bad:ip::value",
            "192.168.49.27:22", "http://192.168.49.27", "١٩٢.١٦٨.٤٩.٢٧", "１９２.１６８.４９.２７").forEach {
            assertNull(it, usableWifiDirectClientAddress(it, "192.168.49.1"))
        }
    }

    @Test fun directConnectionRejectsPhoneAndOldLanAddressButAllowsActualSubnetHost() {
        assertNull(wifiDirectHostForConnection(owner(), "192.168.49.1"))
        assertNull(wifiDirectHostForConnection(owner(), "172.16.16.82"))
        assertNull(wifiDirectHostForConnection(owner(), "192.168.49.0"))
        assertNull(wifiDirectHostForConnection(owner(), "192.168.49.255"))
        assertEquals("192.168.49.27", wifiDirectHostForConnection(owner(), "192.168.49.27"))
        listOf(owner().copy(groupInfoKnown = false), owner().copy(isGroupOwner = false),
            owner().copy(groupExists = false), owner().copy(permissionGranted = false),
            owner().copy(wifiEnabled = false), owner().copy(confirmationRequired = true),
            owner().copy(ownerAddress = null)).forEach {
            assertNull(wifiDirectHostForConnection(it, "192.168.49.27"))
        }
    }

    @Test fun unknownPrefixAllowsOnlySystemReportedCurrentClients() {
        val unknown = owner().copy(ownerPrefixLength = null)
        assertNull(wifiDirectHostForConnection(unknown, "192.168.49.27"))
        assertNull(wifiDirectHostForConnection(unknown, "172.16.16.82"))
        assertEquals("192.168.49.27",
            wifiDirectHostForConnection(unknown.copy(clients = listOf(device)), "192.168.49.27"))
        // A discovered peer is not an attached client and cannot authorize an unknown subnet.
        assertNull(wifiDirectHostForConnection(unknown.copy(peers = listOf(device)), "192.168.49.27"))
        val ipv6 = unknown.copy(ownerAddress = "fd00::1", clients = listOf(device.copy(ipAddress = "fd00::27")))
        assertEquals("fd00:0:0:0:0:0:0:27", wifiDirectHostForConnection(ipv6, "fd00:0:0:0:0:0:0:27"))
        assertNull(wifiDirectHostForConnection(ipv6, "fd00::28"))
        assertNull(wifiDirectHostForConnection(ipv6.copy(clients = listOf(device.copy(ipAddress = "fd00::1"))), "fd00::1"))
    }

    @Test fun manualDirectSubnetUsesActualPrefixAndRejectsNetworkAndBroadcast() {
        val narrow = owner().copy(ownerAddress = "192.168.49.33", ownerPrefixLength = 27)
        assertEquals("192.168.49.45", wifiDirectHostForConnection(narrow, "192.168.49.45"))
        listOf("192.168.49.32", "192.168.49.63", "192.168.49.65").forEach {
            assertNull(wifiDirectHostForConnection(narrow, it))
        }
        val wider = owner().copy(ownerPrefixLength = 23)
        assertEquals("192.168.48.20", wifiDirectHostForConnection(wider, "192.168.48.20"))
        assertNull(wifiDirectHostForConnection(wider, "192.168.48.0"))
        assertNull(wifiDirectHostForConnection(wider, "192.168.49.255"))
        listOf(-1, 0, 31, 32, 33).forEach { prefix ->
            assertNull(wifiDirectHostForConnection(owner().copy(ownerPrefixLength = prefix), "192.168.49.27"))
        }
    }
}
