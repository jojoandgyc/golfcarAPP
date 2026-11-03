package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class DriveSafetyProtocolTest {
    private fun feedback(stopped: Boolean = true, targetStopped: Boolean = true) = mapOf<String, Any?>(
        "targetStopped" to targetStopped, "stopped" to stopped,
        "feedbackLeftRpm" to 0, "feedbackRightRpm" to 0,
        "feedbackLeftError" to 0, "feedbackRightError" to 0,
        "feedbackAgeMs" to 0, "feedbackSamples" to 2, "feedbackSource" to "hardware")

    private fun parse(fields: Map<String, Any?> = feedback(), left: Int = 0, right: Int = 0,
        dryRun: Boolean = false) = validatedDriveFeedback(fields, dryRun, left, right)

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe or malformed feedback was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun everyLaunchRequiresVersionBeforeTheOldBridgeCanOpenHardware() {
        for (dryRun in listOf(false, true)) {
            val command = driveBridgeCommand(dryRun)
            assertTrue(command.contains("app_joystick_bridge.py --require-safety-version 2"))
            assertEquals(dryRun, command.endsWith(" --dry-run"))
        }
        validateDriveSafetyVersion(2, true)
        for (version in listOf(null, 1, 3, "2", true, 2.5, Double.NaN))
            rejects { validateDriveSafetyVersion(version, true) }
        for (readOnly in listOf(null, false, "true", 1))
            rejects { validateDriveSafetyVersion(2, readOnly) }
    }

    @Test fun zeroTargetDoesNotMeanMeasuredStopWhileTheWheelsAreDecelerating() {
        val result = parse(feedback(stopped = false) + mapOf("feedbackLeftRpm" to 35, "feedbackRightRpm" to -21))
        assertTrue(result.targetStopped)
        assertFalse(result.stopped)
        assertFalse(result.feedback.zeroSpeedConfirmed)
        assertEquals(35, result.feedback.leftRpm)
    }

    @Test fun actualUnexpectedSpeedIsPreservedBeyondTheCommandLimit() {
        val result = parse(feedback(stopped = false) + mapOf("feedbackLeftRpm" to 4_000, "feedbackRightRpm" to -3_000))
        assertEquals(4_000, result.feedback.leftRpm)
        assertEquals(-3_000, result.feedback.rightRpm)
        assertFalse(result.stopped)
    }

    @Test fun stopRequiresTwoFreshZeroSamplesWithoutFaultsAndAZeroTarget() {
        assertTrue(parse().stopped)
        for (changed in listOf(mapOf("feedbackLeftRpm" to 1), mapOf("feedbackRightRpm" to -1),
            mapOf("feedbackLeftError" to 1), mapOf("feedbackRightError" to 1),
            mapOf("feedbackSamples" to 0), mapOf("feedbackSamples" to 1)))
            rejects { parse(feedback() + changed) }
        rejects { parse(feedback(targetStopped = false), left = 30, right = -30) }
        assertFalse(parse(feedback(stopped = false)).stopped)
    }

    @Test fun nonzeroTargetsMayHaveZeroMeasuredSpeedWithoutClaimingStop() {
        val result = parse(feedback(stopped = false, targetStopped = false), left = 30, right = -30)
        assertFalse(result.targetStopped)
        assertFalse(result.stopped)
        assertEquals(0, result.feedback.leftRpm)
        rejects { parse(feedback(stopped = false, targetStopped = true), left = 30, right = -30) }
    }

    @Test fun allMeasurementFieldsAndStrictTypesAreMandatory() {
        for (key in feedback().keys) rejects { parse(feedback() - key) }
        for (key in listOf("targetStopped", "stopped"))
            for (value in listOf("true", 1, null)) rejects { parse(feedback() + (key to value)) }
        for (key in listOf("feedbackLeftRpm", "feedbackRightRpm", "feedbackLeftError", "feedbackRightError",
            "feedbackAgeMs", "feedbackSamples"))
            for (value in listOf("0", true, null, 0.5, Double.NaN, Double.POSITIVE_INFINITY,
                Int.MAX_VALUE.toLong() + 1)) rejects { parse(feedback() + (key to value)) }
    }

    @Test fun expiredAndInvalidMeasurementsAreNotAcceptedAsHealthyAcks() {
        for (age in listOf(-1, 251, 10_000)) rejects { parse(feedback() + ("feedbackAgeMs" to age)) }
        rejects { parse(feedback() + ("feedbackSamples" to -1)) }
        for (key in listOf("feedbackLeftError", "feedbackRightError"))
            for (error in listOf(-1, 65536)) rejects { parse(feedback(stopped = false) + (key to error)) }
        assertTrue(parse(feedback() + ("feedbackAgeMs" to 250)).stopped)
    }

    @Test fun simulatedFeedbackCannotAuthorizeHardwareOperation() {
        val simulated = feedback() + ("feedbackSource" to "simulated")
        rejects { parse(simulated) }
        assertTrue(parse(simulated, dryRun = true).stopped)
        rejects { parse(feedback(), dryRun = true) }
        for (source in listOf("", "cached", "Hardware", true, null))
            rejects { parse(feedback() + ("feedbackSource" to source)) }
    }

    @Test fun motorFaultCodesRejectEvenAnAckThatDoesNotClaimStop() {
        for (key in listOf("feedbackLeftError", "feedbackRightError"))
            for (error in listOf(1, 1024, 65535))
                rejects { parse(feedback(stopped = false) + (key to error)) }
    }

    @Test fun transportDelayAndLocalAgeConsumeTheSameFreshnessBudget() {
        val received = 1_000_000_000L
        assertTrue(driveFeedbackIsFresh(received, 125, received + 375_000_000L))
        assertFalse(driveFeedbackIsFresh(received, 125, received + 376_000_000L))
        assertFalse(driveFeedbackIsFresh(received, 501, received))
        assertFalse(driveFeedbackIsFresh(0L, 0, received))
        assertFalse(driveFeedbackIsFresh(received, 0, received - 1))
        assertFalse(driveFeedbackIsFresh(received, -1, received))
    }

    @Test fun aSlowAckDoesNotBecomePermissionToConfigureParking() {
        val received = 1_000_000_000L
        val sample = parse().feedback
        val slow = DriveAck(0, 0, true, feedback = sample, feedbackReceivedNanos = received,
            feedbackAgeAtReceiptMs = 900)
        assertFalse(slow.hasFreshFeedback(received))
        val next = slow.copy(feedbackReceivedNanos = received + 1_000_000_000L, feedbackAgeAtReceiptMs = 80)
        assertTrue(next.hasFreshFeedback(received + 1_000_000_000L))
    }

    @Test fun parkingPermissionExpiresWithoutAnotherAckAndDoesNotDiscardRecoveryLocks() {
        val received = 1_000_000_000L
        val ready = BoardUiState(connected = true, driveEnabled = true, driveConfirmedStopped = true,
            parkingSupported = true, parkingSettings = ParkingSettings(false, 4.25, 5.5, 20.0),
            driveFeedbackLeftRpm = 0, driveFeedbackRightRpm = 0, driveFeedbackVerified = true,
            driveLastFeedbackNanos = received, driveFeedbackAgeAtReceiptMs = 50)
        assertTrue(ready.canEditParkingAt(received))
        assertFalse(ready.canEditParkingAt(received + 451_000_000L))
        for (locked in listOf(ready.copy(driveFeedbackVerified = false), ready.copy(driveConfirmedStopped = false),
            ready.copy(driveFeedbackLeftRpm = 1), ready.copy(driveFeedbackRightRpm = -1),
            ready.copy(driveFeedbackRecheck = true), ready.copy(driveRecoveryNeedsRelease = true),
            ready.copy(driveStandstillPending = true),
            ready.copy(motorActionUnconfirmed = true), ready.copy(followUncertain = true)))
            assertFalse(locked.canEditParkingAt(received))
    }

    @Test fun readOnlyParkingPreservesIndependentFractionalValues() {
        val result = validatedParkingSettings(mapOf("parkingEnabled" to false,
            "parkingLeftCurrentAmps" to 4.25, "parkingRightCurrentAmps" to 5.5,
            "parkingMaxCurrentAmps" to 20.0))
        assertEquals(4.25, result.leftCurrentAmps, 0.0)
        assertEquals(5.5, result.rightCurrentAmps, 0.0)
        assertTrue(result.canEnable)
        assertEquals(result.copy(enabled = true).copy(enabled = false), result)
    }

    @Test fun invalidOrZeroCurrentCannotEnableButCanStillBeDisplayedAndDisabled() {
        assertFalse(ParkingSettings().canEnable)
        for (settings in listOf(ParkingSettings(true, 0.0, 5.0, 20.0),
            ParkingSettings(true, 5.0, 0.0, 20.0), ParkingSettings(true, 5.0, 5.0, 0.0),
            ParkingSettings(true, 5.0, 5.0, 100.0),
            ParkingSettings(true, 21.0, 5.0, 20.0), ParkingSettings(true, 5.0, 61.0, 100.0))) {
            assertFalse(settings.canEnable)
            assertFalse(settings.copy(enabled = false).enabled)
        }
        rejects { ParkingSettings(leftCurrentAmps = Double.NaN) }
        rejects { ParkingSettings(rightCurrentAmps = -1.0) }
    }

    @Test fun oldSingleCurrentCannotBeInterpretedAsBothReadOnlyCurrents() {
        rejects { validatedParkingSettings(mapOf("parkingEnabled" to false, "parkingCurrent" to 5)) }
        for (bad in listOf(null, "5", Double.NaN, Double.POSITIVE_INFINITY, -1.0))
            rejects { validatedParkingSettings(mapOf("parkingEnabled" to false,
                "parkingLeftCurrentAmps" to bad, "parkingRightCurrentAmps" to 5.0, "parkingMaxCurrentAmps" to 60.0)) }
    }

    @Test fun protocolCannotClaimParkingEnabledWithInvalidReadOnlyCurrents() {
        for (invalid in listOf(mapOf("parkingLeftCurrentAmps" to 0.0),
            mapOf("parkingRightCurrentAmps" to 0.0), mapOf("parkingMaxCurrentAmps" to 0.0),
            mapOf("parkingMaxCurrentAmps" to 100.0),
            mapOf("parkingLeftCurrentAmps" to 61.0), mapOf("parkingRightCurrentAmps" to 61.0))) {
            val fields = mapOf<String, Any?>("parkingEnabled" to false,
                "parkingLeftCurrentAmps" to 5.0, "parkingRightCurrentAmps" to 5.0,
                "parkingMaxCurrentAmps" to 60.0) + invalid
            assertFalse(validatedParkingSettings(fields).canEnable)
            rejects { validatedParkingSettings(fields + ("parkingEnabled" to true)) }
        }
    }
}
