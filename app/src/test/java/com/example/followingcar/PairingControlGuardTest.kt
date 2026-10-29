package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class PairingControlGuardTest {
    @Test fun pairingCannotCompeteWithEitherControlModeOrSsh() {
        listOf(BoardUiState(connected = true), BoardUiState(busy = true),
            BoardUiState(followActive = true), BoardUiState(followStopping = true),
            BoardUiState(driveStarting = true), BoardUiState(driveEnabled = true),
            BoardUiState(driveStopping = true), BoardUiState(videoServiceBusy = true))
            .forEach { assertFalse(it.canManagePairing) }
    }

    @Test fun uncertainFollowCanRestoreNetworkButCannotStartControl() {
        val state = BoardUiState(followUncertain = true)
        assertTrue(state.canManagePairing)
        assertFalse(state.canStartFollow)
        assertFalse(state.canEnableDrive)
    }

    @Test fun pendingPairingLocksCompetingWirelessMutationAndControl() {
        assertFalse(BoardUiState(pairingBusy = true).canManageWifiDirect)
        assertFalse(BoardUiState(pairingHotspotActive = true).canManageWifiDirect)
        assertFalse(BoardUiState(pairingUnconfirmed = true).canManageWifiDirect)
        assertFalse(BoardUiState(connected = true, pairingUnconfirmed = true).canStartFollow)
        assertFalse(BoardUiState(connected = true, pairingBusy = true).canEnableDrive)
    }
}
