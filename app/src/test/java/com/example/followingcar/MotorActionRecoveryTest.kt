package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class MotorActionRecoveryTest {
    private val speed = MotorActionPlan("a".repeat(64), MotorAction.RIGHT_TARGET_SPEED, 20, 1000)
    private val stop = MotorActionPlan("a".repeat(64), MotorAction.RIGHT_STOP, 0, 0)

    private fun reply(plan: MotorActionPlan, cleanup: Boolean? = null) = MotorActionReply(
        ok = true, snapshot = null,
        actionResult = MotorActionResult(plan.action, plan.value, plan.durationMs,
            MotorActionOutcome.ACKNOWLEDGED, cleanupConfirmed = cleanup),
        writeMayHaveOccurred = true, reconnectRequired = false, error = null)

    @Test fun boundedActionRequiresBothAckAndConfirmedCleanup() {
        assertTrue(motorActionCompletionConfirmed(speed, reply(speed, true)))
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed, null)))
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed, false)))
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed, true).copy(ok = false)))
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed, true).copy(error = "cleanup_failed")))
    }

    @Test fun unknownFailedOrMissingResultNeverClearsLock() {
        val acknowledged = requireNotNull(reply(speed, true).actionResult)
        for (outcome in listOf(MotorActionOutcome.WRITE_OUTCOME_UNKNOWN, MotorActionOutcome.NOT_ATTEMPTED)) {
            assertFalse(motorActionCompletionConfirmed(speed,
                reply(speed, true).copy(actionResult = acknowledged.copy(outcome = outcome))))
        }
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed, true).copy(actionResult = null)))
        assertFalse(motorActionCompletionConfirmed(speed,
            reply(speed, true).copy(actionResult = acknowledged.copy(error = "failed"))))
    }

    @Test fun ackForAnotherRequestCannotReleaseLock() {
        assertFalse(motorActionCompletionConfirmed(speed, reply(stop)))
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed.copy(value = 30), true)))
        assertFalse(motorActionCompletionConfirmed(speed, reply(speed.copy(durationMs = 2000), true)))
    }

    @Test fun nonBoundedAckStillRejectsContradictoryCleanupOrReconnect() {
        assertTrue(motorActionCompletionConfirmed(stop, reply(stop)))
        assertFalse(motorActionCompletionConfirmed(stop, reply(stop, false)))
        assertFalse(motorActionCompletionConfirmed(stop, reply(stop).copy(reconnectRequired = true)))
        val result = requireNotNull(reply(stop).actionResult)
        assertFalse(motorActionCompletionConfirmed(stop,
            reply(stop).copy(actionResult = result.copy(reconnectRequired = true))))
        val reset = MotorActionPlan("a".repeat(64), MotorAction.FACTORY_RESET, 1)
        assertFalse(motorActionCompletionConfirmed(reset, reply(reset).copy(reconnectRequired = true)))
    }

    @Test fun recoveryReadRequiresVerifiedIdleSnapshotNotMerelyAnSshReplyOrStopGate() {
        val snapshot = MotorConfigurationSnapshot("a".repeat(64), 0, emptyMap(), writeAllowed = true)
        val read = MotorConfigurationReply(true, snapshot, emptyList(), false, null)
        assertTrue(motorActionRecoveryReadConfirmed(read))
        assertFalse(motorActionRecoveryReadConfirmed(read.copy(ok = false)))
        assertFalse(motorActionRecoveryReadConfirmed(read.copy(snapshot = null)))
        assertFalse(motorActionRecoveryReadConfirmed(read.copy(reconnectRequired = true)))
        assertFalse(motorActionRecoveryReadConfirmed(read.copy(error = "check_failed")))
        assertFalse(motorActionRecoveryReadConfirmed(read.copy(snapshot = snapshot.copy(writeAllowed = false, stopAllowed = true))))
    }

    @Test fun unknownActionBlocksCompetingControlButAllowsExplicitRecoveryRead() {
        val state = BoardUiState(connected = true, motorActionUnconfirmed = true)
        assertFalse(state.canEnableDrive)
        assertFalse(state.canStartFollow)
        assertFalse(state.canExecuteTerminal)
        assertFalse(state.canToggleVideoService)
        assertFalse(state.canConfigureMotor)
        assertTrue(state.canReadMotorConfiguration)
        assertFalse(state.copy(driveEnabled = true, parkingSupported = true).canConfigureParking)
        assertFalse(state.copy(busy = true).canReadMotorConfiguration)
        assertFalse(state.copy(followUncertain = true).canReadMotorConfiguration)
    }

    @Test fun disconnectionAndReconnectionDoNotClearLockOrPreventSshRecovery() {
        val locked = BoardUiState(connected = true, motorActionUnconfirmed = true)
        val disconnected = locked.withTransportState(false)
        assertTrue(disconnected.motorActionUnconfirmed)
        assertTrue(disconnected.canRecoverConnection)
        assertTrue(disconnected.canManageConnectionMode)
        assertTrue(disconnected.canManagePairing)
        assertTrue(disconnected.canManageWifiDirect)
        assertFalse(disconnected.copy(busy = true).canManagePairing)
        val reconnected = disconnected.withTransportState(true)
        assertTrue(reconnected.motorActionUnconfirmed)
        assertFalse(reconnected.canStartFollow)
        assertTrue(reconnected.canReadMotorConfiguration)
    }

    @Test fun onlyBoundedSpeedOrLearningActionsNeedVisibilityCancellation() {
        assertTrue(motorActionNeedsVisibility(speed))
        assertTrue(motorActionNeedsVisibility(MotorActionPlan("a".repeat(64), MotorAction.PHASE_LEARNING, 1, 60000)))
        assertFalse(motorActionNeedsVisibility(stop))
        assertFalse(motorActionNeedsVisibility(MotorActionPlan("a".repeat(64), MotorAction.PHASE_LEARNING, 0, 0)))
        assertFalse(motorActionNeedsVisibility(null))
    }
}
