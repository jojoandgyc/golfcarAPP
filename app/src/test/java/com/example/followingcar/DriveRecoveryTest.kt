package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class DriveRecoveryTest {
    @Test fun onlyNegotiatedBoardsUseLongerAckWait() {
        assertEquals(900L, driveAckTimeoutMillis(null))
        assertEquals(2_500L, driveAckTimeoutMillis(5_000))
        for (value in listOf(-1, 0, 600, 2_500, 6_000, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { driveAckTimeoutMillis(value) }
        }
    }

    @Test fun recoveryCanOnlyAcknowledgeAStoppedTarget() {
        assertTrue(validatedDriveRecovery(true, true, 0, 0, true))
        for ((left, right, stopped) in listOf(Triple(1, 0, false), Triple(0, -1, false), Triple(0, 0, false))) {
            assertThrows(IllegalArgumentException::class.java) {
                validatedDriveRecovery(true, true, left, right, stopped)
            }
        }
    }

    @Test fun unnegotiatedOrMissingRecoveryCannotAuthorizeSuppression() {
        assertFalse(validatedDriveRecovery(false, null, 20, 20, false))
        assertFalse(validatedDriveRecovery(false, false, 20, 20, false))
        assertThrows(IllegalArgumentException::class.java) { validatedDriveRecovery(false, true, 0, 0, true) }
        assertThrows(IllegalArgumentException::class.java) { validatedDriveRecovery(true, null, 0, 0, true) }
    }

    @Test fun heldInputStaysBlockedAcrossRepeatedRecoveryAcksUntilRelease() {
        val gate = DriveRecoveryGate()
        assertTrue(gate.input())
        gate.recovered()
        repeat(10) { assertFalse(gate.input()) }
        gate.recovered() // The neutral request's ACK must not silently unlock the old gesture.
        assertTrue(gate.needsRelease)
        assertFalse(gate.input())
        gate.released()
        assertFalse(gate.needsRelease)
        assertTrue(gate.input())
    }

    @Test fun alreadyReleasedInputMayBeginAFreshGestureAfterRecovery() {
        val gate = DriveRecoveryGate()
        assertTrue(gate.input())
        gate.released()
        gate.recovered()
        assertFalse(gate.needsRelease)
        assertTrue(gate.input())
    }

    @Test fun lateNeutralConfirmationDoesNotCancelAFreshGesture() {
        val gate = DriveRecoveryGate()
        gate.input()
        assertTrue(gate.recovered())
        gate.released()
        assertTrue(gate.input()) // New gesture after release, while neutral ACK is returning.
        assertFalse(gate.recovered(neutralRequest = true))
        assertFalse(gate.needsRelease)
        assertTrue(gate.input())
        assertTrue(gate.recovered()) // A subsequent new outage still latches this gesture.
        assertTrue(gate.needsRelease)
    }

    @Test fun neutralConfirmationNeverUnlocksAnUnreleasedOldGesture() {
        val gate = DriveRecoveryGate()
        gate.input()
        gate.recovered()
        assertFalse(gate.recovered(neutralRequest = true))
        assertTrue(gate.needsRelease)
        assertFalse(gate.input())
    }
}
