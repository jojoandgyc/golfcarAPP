package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class DriveInterlockTest {
    private fun ack(
        supported: Boolean = true,
        command: String = "drive",
        requestedLeft: Int = 40,
        requestedRight: Int = -40,
        actualLeft: Int = 0,
        actualRight: Int = 0,
        stopped: Boolean = true,
        blocked: Boolean? = true,
        directions: List<String>? = listOf("forward"),
    ) = validatedDriveInterlock(supported, command, requestedLeft, requestedRight, actualLeft,
        actualRight, stopped, blocked, directions)

    @Test fun negotiatedInfraredStopIsAValidAckRatherThanAControlFailure() {
        assertEquals(DriveInterlock(true, setOf("forward")), ack())
        assertEquals(DriveInterlock(true, setOf("forward", "right")),
            ack(requestedLeft = 60, requestedRight = -20, directions = listOf("forward", "right")))
    }

    @Test fun ordinaryNegotiatedAckStillConfirmsTheRequestedTarget() {
        assertEquals(DriveInterlock(false, emptySet<String>()), ack(actualLeft = 40, actualRight = -40,
            stopped = false, blocked = false, directions = emptyList()))
        assertEquals(DriveInterlock(false, emptySet<String>()), ack(command = "stop", requestedLeft = 0,
            requestedRight = 0, blocked = false, directions = emptyList()))
    }

    @Test fun legacyBoardMayOmitInfraredMetadataButCannotChangeTheTarget() {
        assertNull(ack(supported = false, actualLeft = 40, actualRight = -40, stopped = false,
            blocked = null, directions = null))
        rejects { ack(supported = false, blocked = null, directions = null) }
    }

    @Test fun unnegotiatedInfraredClaimsCannotAuthorizeAnUnexpectedStop() {
        rejects { ack(supported = false) }
        rejects { ack(supported = false, actualLeft = 40, actualRight = -40, stopped = false) }
        assertNull(ack(supported = false, actualLeft = 40, actualRight = -40, stopped = false,
            blocked = false, directions = emptyList()))
    }

    @Test fun noInfraredTriggerMustNotHideAChangedOrZeroTarget() {
        for ((left, right) in listOf(0 to 0, 40 to 0, 0 to -40, 41 to -40, -40 to 40)) {
            rejects { ack(actualLeft = left, actualRight = right, stopped = left == 0 && right == 0,
                blocked = false, directions = emptyList()) }
        }
    }

    @Test fun infraredOverrideAllowsOnlyACompleteStop() {
        for ((left, right) in listOf(40 to -40, 0 to -40, 40 to 0, 1 to 0, -1 to 0)) {
            rejects { ack(actualLeft = left, actualRight = right, stopped = false) }
        }
    }

    @Test fun infraredOverrideCannotBeAttachedToNeutralOrConfigurationCommands() {
        rejects { ack(requestedLeft = 0, requestedRight = 0) }
        for (command in listOf("stop", "close", "estop", "parking")) {
            rejects { ack(command = command, requestedLeft = 0, requestedRight = 0) }
            rejects { ack(command = command) }
        }
    }

    @Test fun stoppedFlagMustAlwaysMatchActualZeroSpeed() {
        rejects { ack(stopped = false) }
        rejects { ack(actualLeft = 40, actualRight = -40, stopped = true,
            blocked = false, directions = emptyList()) }
        rejects { ack(supported = false, actualLeft = 40, actualRight = -40, stopped = true,
            blocked = null, directions = null) }
    }

    @Test fun negotiatedCapabilityRequiresBothFieldsOnEveryAck() {
        rejects { ack(blocked = null) }
        rejects { ack(directions = null) }
        rejects { ack(command = "stop", requestedLeft = 0, requestedRight = 0,
            blocked = null, directions = null) }
    }

    @Test fun triggerDirectionsMustBeKnownNonemptyAndUnique() {
        for (directions in listOf(emptyList(), listOf("forward", "forward"), listOf("backward"),
            listOf("Forward"), listOf(""), listOf("private-untrusted-text"),
            listOf("forward", "right", "left", "forward"))) {
            rejects { ack(directions = directions) }
        }
        for (direction in listOf("forward", "right", "left")) {
            assertEquals(setOf(direction), ack(directions = listOf(direction))?.directions)
        }
    }

    @Test fun unblockedAckCannotCarryDirectionsFromAnEarlierTrigger() {
        rejects { ack(actualLeft = 40, actualRight = -40, stopped = false, blocked = false) }
    }

    private fun rejects(block: () -> Any?) {
        try {
            block()
            fail("Invalid motor/interlock acknowledgement was accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
