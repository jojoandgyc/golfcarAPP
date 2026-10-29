package com.example.followingcar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotorConfigurationAccessTest {
    @Test fun onlyConfirmedIdleConnectionAllowsConfiguration() {
        assertFalse(BoardUiState().canConfigureMotor)
        val idle = BoardUiState(connected = true)
        assertTrue(idle.canConfigureMotor)
        listOf(idle.copy(busy = true), idle.copy(driveStarting = true), idle.copy(driveEnabled = true),
            idle.copy(driveStopping = true), idle.copy(followActive = true), idle.copy(followStopping = true),
            idle.copy(followUncertain = true), idle.copy(videoServiceBusy = true),
            idle.copy(parkingUpdating = true), idle.copy(pairingBusy = true), idle.copy(pairingUnconfirmed = true))
            .forEach { assertFalse(it.canConfigureMotor) }
    }

    @Test fun configurationBusyAlsoBlocksCompetingControlStarts() {
        val saving = BoardUiState(connected = true, busy = true)
        assertFalse(saving.canEnableDrive)
        assertFalse(saving.canStartFollow)
        assertFalse(saving.canToggleVideoService)
        assertFalse(saving.canInspectFollow)
    }
}
