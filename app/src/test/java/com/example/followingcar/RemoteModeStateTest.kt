package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class RemoteModeStateTest {
    private val profile = RemoteProfile("car.nujabes.top", 2443, 8443, "a".repeat(64), Long.MAX_VALUE)

    @Test fun selectingRemotePreservesTheLocalAddress() {
        val local = BoardUiState(host = "172.16.16.82", port = "22")
        val remote = local.copy(remoteMode = true, remoteProfile = profile)
        assertEquals("car.nujabes.top", remote.connectionHost)
        assertEquals("2443", remote.connectionPort)
        assertEquals(local.host, remote.host)
        assertEquals(local.port, remote.port)
        assertEquals(local.connectionHost, remote.copy(remoteMode = false).connectionHost)
    }

    @Test fun credentialsAndConnectionModeCannotChangeDuringControlOrConnectionWork() {
        val idle = BoardUiState(remoteMode = true, remoteProfile = profile)
        assertTrue(idle.canManageConnectionMode)
        listOf(idle.copy(connected = true), idle.copy(busy = true), idle.copy(driveStarting = true),
            idle.copy(driveEnabled = true), idle.copy(driveStopping = true), idle.copy(followActive = true),
            idle.copy(followStopping = true), idle.copy(videoServiceBusy = true),
            idle.copy(remoteProfileLoading = true), idle.copy(pairingBusy = true),
            idle.copy(pairingUnconfirmed = true)).forEach { assertFalse(it.canManageConnectionMode) }
    }

    @Test fun modeSwitchIsAvailableToRecoverAnUnconfirmedDisconnectedSession() {
        val state = BoardUiState(followUncertain = true)
        assertTrue(state.canManageConnectionMode)
        assertFalse(state.canStartFollow)
        assertFalse(state.canEnableDrive)
    }
}
