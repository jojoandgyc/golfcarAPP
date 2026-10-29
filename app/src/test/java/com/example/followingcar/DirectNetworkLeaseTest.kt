package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class DirectNetworkLeaseTest {
    private fun group() = WifiDirectState(supported = true, permissionGranted = true, wifiEnabled = true,
        groupInfoKnown = true, groupExists = true, isGroupOwner = true,
        networkName = "DIRECT-AB-Example", ownerAddress = "192.168.49.1")

    @Test fun onlyConfirmedPhoneOwnedGroupCanBeUsedForDirectSsh() {
        val ready = group()
        assertNotNull(DirectNetworkLease.from(ready))
        listOf(ready.copy(groupInfoKnown = false), ready.copy(groupExists = false),
            ready.copy(isGroupOwner = false), ready.copy(permissionGranted = false), ready.copy(confirmationRequired = true),
            ready.copy(wifiEnabled = false), ready.copy(supported = false),
            ready.copy(networkName = null), ready.copy(ownerAddress = null))
            .forEach { assertNull(DirectNetworkLease.from(it)) }
    }

    @Test fun groupRemovalReplacementAndPermissionLossInvalidateOnlyAnExistingLease() {
        val ready = group()
        val lease = DirectNetworkLease.from(ready)!!
        assertFalse(lease.isLost(ready))
        assertFalse("An unfinished refresh is not a disconnect", lease.isLost(ready.copy(groupInfoKnown = false)))
        listOf(ready.copy(groupExists = false), ready.copy(isGroupOwner = false),
            ready.copy(networkName = "DIRECT-CD-Other"), ready.copy(ownerAddress = "192.168.50.1"),
            ready.copy(permissionGranted = false), ready.copy(wifiEnabled = false), ready.copy(supported = false))
            .forEach { assertTrue(lease.isLost(it)) }
    }

    @Test fun restoringNetworkNeverUnlocksOrRestartsUnconfirmedFollow() {
        val pending = BoardUiState(followUncertain = true)
        assertTrue("Network restoration must not deadlock behind the old control lock", pending.canManageWifiDirect)
        assertFalse(pending.canStartFollow)
        assertFalse(pending.canEnableDrive)
        val connected = pending.copy(connected = true)
        assertFalse(connected.canStartFollow)
        assertFalse(connected.canManageWifiDirect)
        assertFalse(connected.followActive)
    }

    @Test fun networkChangesAreBlockedDuringSshControlAndCleanup() {
        val idle = BoardUiState()
        assertTrue(idle.canManageWifiDirect)
        listOf(idle.copy(connected = true), idle.copy(busy = true), idle.copy(driveStarting = true),
            idle.copy(driveEnabled = true), idle.copy(driveStopping = true), idle.copy(followActive = true),
            idle.copy(followStopping = true), idle.copy(videoServiceBusy = true))
            .forEach { assertFalse(it.canManageWifiDirect) }
    }
}
