package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class RemoteRecoveryPolicyTest {
    private val profile = RemoteProfile("world-agents.com", clientCertificateSha256 = "test",
        certificateExpiresAtEpochMillis = Long.MAX_VALUE)

    @Test fun uncertainLocalGroupDoesNotBlockRemoteRecoveryOrReleaseControlLocks() {
        val state = BoardUiState(remoteProfile = profile, pairingUnconfirmed = true,
            followUncertain = true, motorActionUnconfirmed = true)
        assertFalse(state.canManageConnectionMode)
        assertTrue(state.canSelectRemoteMode)
        assertTrue(state.canReconnectViaRemote)
        val connected = state.copy(remoteMode = true, connected = true)
        assertTrue(connected.followUncertain)
        assertTrue(connected.motorActionUnconfirmed)
        assertFalse(connected.canEnableDrive)
        assertFalse(connected.canStartFollow)
        assertFalse(connected.canExecuteTerminal)
    }

    @Test fun runningOrStoppingControlCannotSwitchToRemote() {
        val idle = BoardUiState(remoteProfile = profile)
        for (blocked in listOf(idle.copy(followActive = true), idle.copy(followStopping = true),
            idle.copy(driveStarting = true), idle.copy(driveEnabled = true), idle.copy(driveStopping = true),
            idle.copy(videoServiceBusy = true), idle.copy(busy = true), idle.copy(pairingBusy = true),
            idle.copy(connected = true))) assertFalse(blocked.canReconnectViaRemote)
    }

    @Test fun remoteRecoveryRequiresUsableProfile() {
        assertFalse(BoardUiState().canReconnectViaRemote)
        assertFalse(BoardUiState(remoteProfile = profile, remoteProfileLoading = true).canReconnectViaRemote)
        assertFalse(BoardUiState(remoteProfile = profile, remoteProfileError = "invalid").canReconnectViaRemote)
        assertTrue(BoardUiState(remoteProfile = profile, followUncertain = true).canReconnectViaRemote)
    }
}
