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
        assertNull(state.connectionSelectionBlockReason())
        assertTrue(state.canSelectRemoteMode)
        assertTrue(state.canReconnectViaRemote)
        assertNull(state.connectionBlockReason(ConnectionMethod.REMOTE,
            WifiDirectState(confirmationRequired = true)))
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

    @Test fun authenticatedRemoteRouteCanUseControlsDespiteOnlyHistoricalPairingUncertainty() {
        val previous = BoardUiState(remoteProfile = profile, pairingUnconfirmed = true)
        val remote = previous.withSelectedRemoteMode(true).copy(connected = true)
        assertTrue(remote.pairingUnconfirmed)
        assertFalse(remote.pairingBlocksControl)
        assertTrue(remote.canEnableDrive)
        assertTrue(remote.canInspectFollow)
        assertTrue(remote.canStartFollow)
        assertTrue(remote.canReadMotorConfiguration)
        assertTrue(remote.canConfigureMotor)
        assertNull(remote.driveStartBlock())
        assertNull(remote.driveStartBlockReason)
        // Permission to start is not a start action or a claim that the old pairing was verified.
        assertFalse(remote.manualActive)
        assertFalse(remote.followActive)
        assertFalse(remote.followStopping)
        assertFalse(remote.driveEnabled)
        assertEquals(0, remote.driveLeft)
        assertEquals(0, remote.driveRight)
        assertFalse(remote.videoPreviewWanted)
        assertFalse(previous.connected)
        assertTrue(previous.pairingUnconfirmed)
    }

    @Test fun localRouteStillRequiresItsHistoricalPairingToBeReconciled() {
        val local = BoardUiState(connected = true, remoteMode = false, pairingUnconfirmed = true)
        assertTrue(local.pairingBlocksControl)
        assertFalse(local.canEnableDrive)
        assertFalse(local.canInspectFollow)
        assertFalse(local.canStartFollow)
        assertFalse(local.canReadMotorConfiguration)
        assertFalse(local.canConfigureMotor)
        assertEquals(DriveStartBlock.LOCAL_PAIRING_UNCONFIRMED, local.driveStartBlock())
        assertFalse(local.driveStartBlockReason.isNullOrBlank())
    }

    @Test fun activePairingBlocksBothRoutesEvenWithoutAnUncertainJournal() {
        for (remote in listOf(false, true)) for (unknown in listOf(false, true)) {
            val pairing = BoardUiState(connected = true, remoteMode = remote, pairingBusy = true,
                pairingUnconfirmed = unknown)
            assertTrue(pairing.pairingBlocksControl)
            assertFalse(pairing.canEnableDrive)
            assertFalse(pairing.canInspectFollow)
            assertFalse(pairing.canStartFollow)
            assertFalse(pairing.canReadMotorConfiguration)
            assertFalse(pairing.canConfigureMotor)
            assertEquals(DriveStartBlock.PAIRING_BUSY, pairing.driveStartBlock())
        }
    }

    @Test fun remotePairingExceptionCannotBypassActualControlLocksOrOngoingOperations() {
        val remote = BoardUiState(connected = true, remoteMode = true, pairingUnconfirmed = true)
        val blocked = listOf(
            remote.copy(busy = true) to DriveStartBlock.BUSY,
            remote.copy(videoServiceBusy = true) to DriveStartBlock.VIDEO_BUSY,
            remote.copy(driveStarting = true) to DriveStartBlock.MANUAL_ACTIVE,
            remote.copy(driveEnabled = true) to DriveStartBlock.MANUAL_ACTIVE,
            remote.copy(driveStopping = true) to DriveStartBlock.MANUAL_ACTIVE,
            remote.copy(followActive = true) to DriveStartBlock.FOLLOW_ACTIVE,
            remote.copy(followStopping = true) to DriveStartBlock.FOLLOW_STOPPING,
            remote.copy(followUncertain = true) to DriveStartBlock.FOLLOW_UNCONFIRMED,
            remote.copy(motorActionUnconfirmed = true) to DriveStartBlock.MOTOR_UNCONFIRMED,
            remote.copy(driveCleanupUnconfirmed = true) to DriveStartBlock.DRIVE_UNCONFIRMED,
        )
        for ((state, reason) in blocked) {
            assertEquals(reason, state.driveStartBlock())
            assertFalse(state.canEnableDrive)
            assertFalse(state.canStartFollow)
            assertFalse(state.canConfigureMotor)
            assertTrue(state.pairingUnconfirmed)
            assertFalse(state.driveStartBlockReason.isNullOrBlank())
        }
        // Read-only recovery remains reachable while real control ownership is unresolved.
        assertTrue(remote.copy(followUncertain = true).canInspectFollow)
        assertTrue(remote.copy(driveCleanupUnconfirmed = true).canInspectFollow)
        assertTrue(remote.copy(motorActionUnconfirmed = true).canInspectFollow)
        assertTrue(remote.copy(motorActionUnconfirmed = true).canReadMotorConfiguration)
        assertFalse(remote.copy(followUncertain = true).canReadMotorConfiguration)
        assertFalse(remote.copy(driveCleanupUnconfirmed = true).canReadMotorConfiguration)
    }

    @Test fun merelySelectingRemoteWhileDisconnectedDoesNotAuthorizeAnyControl() {
        val remote = BoardUiState(remoteProfile = profile, pairingUnconfirmed = true)
            .withSelectedRemoteMode(true)
        assertFalse(remote.pairingBlocksControl)
        assertFalse(remote.connected)
        assertFalse(remote.canEnableDrive)
        assertFalse(remote.canInspectFollow)
        assertFalse(remote.canStartFollow)
        assertFalse(remote.canReadMotorConfiguration)
        assertFalse(remote.canConfigureMotor)
        assertEquals(DriveStartBlock.DISCONNECTED, remote.driveStartBlock())
    }

    @Test fun switchingRoutesPreservesEveryUnresolvedFlagAndDoesNotConfirmLocalPairing() {
        val original = BoardUiState(remoteProfile = profile, pairingUnconfirmed = true,
            pairingHotspotActive = true, followUncertain = true, driveCleanupUnconfirmed = true,
            motorActionUnconfirmed = true)
        val remote = original.withSelectedRemoteMode(true)
        val localAgain = remote.withSelectedRemoteMode(false)
        for (state in listOf(remote, localAgain)) {
            assertTrue(state.pairingUnconfirmed)
            assertTrue(state.pairingHotspotActive)
            assertTrue(state.followUncertain)
            assertTrue(state.driveCleanupUnconfirmed)
            assertTrue(state.motorActionUnconfirmed)
            assertFalse(state.manualActive)
            assertFalse(state.followActive)
            assertFalse(state.canEnableDrive)
        }
        assertTrue(remote.remoteMode)
        assertFalse(remote.pairingBlocksControl)
        assertFalse(localAgain.remoteMode)
        assertTrue(localAgain.pairingBlocksControl)
    }

    @Test fun manualStartPermissionAndVisibleReasonAgreeAcrossRouteAndLockMatrix() {
        for (remote in listOf(false, true)) for (connected in listOf(false, true)) {
            for (unknown in listOf(false, true)) {
                val base = BoardUiState(connected = connected, remoteMode = remote, pairingUnconfirmed = unknown)
                for (state in listOf(base, base.copy(busy = true), base.copy(pairingBusy = true),
                    base.copy(videoServiceBusy = true), base.copy(driveStarting = true),
                    base.copy(driveEnabled = true), base.copy(driveStopping = true),
                    base.copy(followActive = true), base.copy(followStopping = true),
                    base.copy(followUncertain = true), base.copy(motorActionUnconfirmed = true),
                    base.copy(driveCleanupUnconfirmed = true))) {
                    if (state.canEnableDrive) {
                        assertNull(state.driveStartBlockReason)
                        assertNull(state.driveStartBlock())
                    } else {
                        assertFalse(state.driveStartBlockReason.isNullOrBlank())
                        assertEquals(state.driveStartBlock()!!.message, state.driveStartBlockReason)
                    }
                }
            }
        }
    }
}
