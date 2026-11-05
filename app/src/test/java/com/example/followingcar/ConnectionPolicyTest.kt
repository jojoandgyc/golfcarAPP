package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    private val profile = RemoteProfile("world-agents.com", clientCertificateSha256 = "test",
        certificateExpiresAtEpochMillis = Long.MAX_VALUE)
    private val idle = BoardUiState(host = "192.168.49.2", remoteProfile = profile)
    private val group = WifiDirectState(supported = true, permissionGranted = true, wifiEnabled = true,
        groupInfoKnown = true, groupExists = true, isGroupOwner = true,
        networkName = "DIRECT-AB-Test", ownerAddress = "192.168.49.1",
        clients = listOf(WifiDirectPeer("test-board", "02:12:34:56:78:90", "192.168.49.2", 0)))

    @Test fun recoveryRecordsDoNotTrapTheUserInOneMethod() {
        val pending = idle.copy(remoteMode = true, pairingUnconfirmed = true,
            pairingHotspotActive = true, followUncertain = true, motorActionUnconfirmed = true)
        assertNull(pending.connectionSelectionBlockReason())
        assertNull(pending.connectionBlockReason(ConnectionMethod.REMOTE,
            group.copy(confirmationRequired = true)))
        assertNotNull(pending.connectionBlockReason(ConnectionMethod.LAN, group))
        assertNotNull(pending.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, group))
        assertFalse(pending.canManageWifiDirect)
        assertTrue(pending.followUncertain)
        assertTrue(pending.motorActionUnconfirmed)
    }

    @Test fun activeWorkStillBlocksSelectionAndEveryConnectionRouteWithAnExplanation() {
        val blocked = listOf(idle.copy(connected = true), idle.copy(busy = true),
            idle.copy(driveStarting = true), idle.copy(driveEnabled = true), idle.copy(driveStopping = true),
            idle.copy(followActive = true), idle.copy(followStopping = true),
            idle.copy(videoServiceBusy = true), idle.copy(pairingBusy = true),
            idle.copy(remoteProfileLoading = true))
        blocked.forEach { state ->
            val reason = state.connectionSelectionBlockReason()
            assertFalse(reason.isNullOrBlank())
            ConnectionMethod.entries.forEach { method ->
                assertEquals(reason, state.connectionBlockReason(method, group))
            }
        }
    }

    @Test fun actualWirelessMutationBlocksAllRoutesIncludingRemote() {
        val reason = idle.connectionSelectionBlockReason(wirelessMutationPending = true)
        assertFalse(reason.isNullOrBlank())
        ConnectionMethod.entries.forEach { method ->
            assertEquals(reason, idle.connectionBlockReason(method, group, wirelessMutationPending = true))
        }
    }

    @Test fun confirmedPhoneHotspotCanReconnectOverLanWithoutPermittingDirectMutation() {
        val hotspot = idle.copy(pairingHotspotActive = true)
        assertNull(hotspot.connectionSelectionBlockReason())
        assertNull(hotspot.connectionBlockReason(ConnectionMethod.LAN, WifiDirectState()))
        assertNull(hotspot.connectionBlockReason(ConnectionMethod.REMOTE, WifiDirectState()))
        assertTrue(hotspot.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, group)!!.contains("系统热点配对"))
        assertFalse(hotspot.canManageWifiDirect)
    }

    @Test fun unconfirmedDirectOperationBlocksLocalConnectionButNotViewingOrRemoteRecovery() {
        val unknown = group.copy(confirmationRequired = true)
        assertNull(idle.connectionSelectionBlockReason())
        assertNotNull(idle.connectionBlockReason(ConnectionMethod.LAN, unknown))
        assertNotNull(idle.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, unknown))
        assertNull(idle.connectionBlockReason(ConnectionMethod.REMOTE, unknown))
    }

    @Test fun readOnlyDirectQueryAndDiscoveryDoNotBlockIndependentRoutes() {
        listOf(group.copy(busy = true), group.copy(discovering = true)).forEach { wireless ->
            assertNull(idle.connectionSelectionBlockReason())
            assertNull(idle.connectionBlockReason(ConnectionMethod.LAN, wireless))
            assertNull(idle.connectionBlockReason(ConnectionMethod.REMOTE, wireless))
            assertNotNull(idle.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, wireless))
        }
    }

    @Test fun unusedDirectHardwareIsNotARequirementForLanOrRemote() {
        val absent = WifiDirectState(supported = false, permissionGranted = false, wifiEnabled = false)
        assertNull(idle.connectionBlockReason(ConnectionMethod.LAN, absent))
        assertNull(idle.connectionBlockReason(ConnectionMethod.REMOTE, absent))
        assertNotNull(idle.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, absent))
    }

    @Test fun directRequiresAConfirmedPhoneOwnedLeaseAndCurrentClientAddress() {
        assertNull(idle.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, group))
        listOf(group.copy(supported = false), group.copy(permissionGranted = false),
            group.copy(wifiEnabled = false), group.copy(groupInfoKnown = false),
            group.copy(groupExists = false), group.copy(isGroupOwner = false),
            group.copy(networkName = null), group.copy(ownerAddress = null),
            group.copy(clients = emptyList())).forEach { wireless ->
            assertNotNull(idle.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, wireless))
        }
        listOf("192.168.49.1", "172.16.16.82", "localhost", "127.0.0.1", "").forEach { host ->
            assertNotNull(idle.copy(host = host).connectionBlockReason(ConnectionMethod.WIFI_DIRECT, group))
        }
    }

    @Test fun directManualAddressUsesOnlyTheActualObservedSubnet() {
        val subnet = group.copy(clients = emptyList(), ownerPrefixLength = 24)
        assertNull(idle.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, subnet))
        listOf("192.168.49.0", "192.168.49.255", "192.168.50.2").forEach { host ->
            assertNotNull(idle.copy(host = host).connectionBlockReason(ConnectionMethod.WIFI_DIRECT, subnet))
        }
    }

    @Test fun missingOrInvalidRemoteProfileDoesNotHideOtherMethodsOrImport() {
        listOf(idle.copy(remoteProfile = null), idle.copy(remoteProfileError = "profile_failure"))
            .forEach { state ->
                assertNull(state.connectionSelectionBlockReason())
                assertNotNull(state.connectionBlockReason(ConnectionMethod.REMOTE, group))
                assertNull(state.connectionBlockReason(ConnectionMethod.LAN, group))
                assertNull(state.connectionBlockReason(ConnectionMethod.WIFI_DIRECT, group))
            }
    }

    @Test fun routeEvaluationUsesTheRequestedMethodWithoutChangingCurrentSelection() {
        val state = idle.copy(remoteMode = true, pairingUnconfirmed = true)
        assertNotNull(state.connectionBlockReason(ConnectionMethod.LAN, group))
        assertNull(state.connectionBlockReason(ConnectionMethod.REMOTE, group))
        assertTrue(state.remoteMode)
        assertTrue(state.pairingUnconfirmed)
    }

    @Test fun explanationsNeverEchoCredentialsOrRawWirelessErrors() {
        val secret = "secret-placeholder-do-not-echo"
        val state = idle.copy(password = secret, remoteProfileError = secret)
        val wireless = group.copy(error = secret, passphrase = secret, confirmationRequired = true)
        ConnectionMethod.entries.forEach { method ->
            val reason = state.connectionBlockReason(method, wireless)
            assertNotNull(reason)
            assertFalse(reason!!.contains(secret))
        }
    }
}
