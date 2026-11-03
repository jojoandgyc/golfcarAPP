package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class ParkingCurrentConfigurationTest {
    private val left = MotorConfigurationField.LEFT_PARKING_CURRENT
    private val right = MotorConfigurationField.RIGHT_PARKING_CURRENT
    private val maximum = MotorConfigurationField.MAX_OUTPUT_CURRENT
    private val speed = MotorConfigurationField.MAX_FORWARD_SPEED

    private fun snapshot() = MotorConfigurationSnapshot("parking-read-a", 1_700_000_000_000L,
        mapOf(left to 325L, right to 475L, maximum to 2000L, speed to 500L), writeAllowed = true,
        leftParkingCurrentRaw = 325, rightParkingCurrentRaw = 475)

    private fun state() = MotorConfigurationUiState(snapshot())

    @Test fun ignoresEveryMotorPageDraftAndAlwaysUsesPersistentMode() {
        val original = state().copy(draftValues = mapOf(speed to "invalid", maximum to "60",
            left to "55", right to "bad", MotorConfigurationField.RS485_ADDRESS to "2"),
            writeMode = MotorConfigurationWriteMode.TEMPORARY)
        val plan = requireNotNull(parkingCurrentWritePlan(original, "4.25", "5.5"))
        assertEquals(MotorConfigurationWriteMode.PERSISTENT, plan.writeMode)
        assertEquals(setOf(left, right), plan.changes.map { it.field }.toSet())
        assertEquals(setOf(MotorConfigurationChange(left, 325L, 425L),
            MotorConfigurationChange(right, 475L, 550L)), plan.changes.toSet())
        assertEquals("parking-read-a", plan.snapshotId)
        assertTrue(parkingCurrentWritePlanIsCurrent(original, plan))
        assertEquals("invalid", original.draftValues[speed])
        assertEquals("55", original.draftValues[left])
        assertEquals(MotorConfigurationWriteMode.TEMPORARY, original.writeMode)
    }

    @Test fun onlyChangedSideIsWrittenAndNormalizedValuesAreUnchanged() {
        assertNull(parkingCurrentWritePlan(state(), "3.250", "4.75"))
        val leftOnly = requireNotNull(parkingCurrentWritePlan(state(), "0", "4.750"))
        assertEquals(listOf(MotorConfigurationChange(left, 325L, 0L)), leftOnly.changes)
        val rightOnly = requireNotNull(parkingCurrentWritePlan(state(), "3.25", "20.00"))
        assertEquals(listOf(MotorConfigurationChange(right, 475L, 2000L)), rightOnly.changes)
    }

    @Test fun zeroAndSixtyAreAllowedOnlyWithinTheReadMaximum() {
        val fullRange = state().copy(snapshot = snapshot().copy(values = snapshot().values + (maximum to 6000L)))
        assertNotNull(parkingCurrentWritePlan(fullRange, "0", "60"))
        assertNotNull(parkingCurrentWritePlan(state(), "20", "20"))
        val attemptedMaximumChange = state().copy(draftValues = mapOf(maximum to "60"))
        assertNull(parkingCurrentWritePlan(attemptedMaximumChange, "20.01", "4.75"))
        assertEquals(setOf(left), parkingCurrentDraftAnalysis(attemptedMaximumChange, "20.01", "4.75").errors.keys)
    }

    @Test fun invalidInputsReportTheCorrespondingSideWithoutRounding() {
        for (text in listOf("", "-1", "60.01", "3.251", "NaN", "Infinity", "1e1", "1,5", "五")) {
            val analysis = parkingCurrentDraftAnalysis(state(), text, "4.75")
            assertTrue(text, left in analysis.errors)
            assertFalse(text, right in analysis.errors)
            assertNull(text, parkingCurrentWritePlan(state(), text, "4.75"))
        }
        assertEquals(setOf(left, right), parkingCurrentDraftAnalysis(state(), "bad", "bad").errors.keys)
    }

    @Test fun unreadOrInvalidMaximumCannotBeSubstitutedFromAnotherDraft() {
        for (raw in listOf<Long?>(null, 0L, 49L, 6001L)) {
            val values = snapshot().values - maximum + if (raw == null) emptyMap() else mapOf(maximum to raw)
            val candidate = state().copy(snapshot = snapshot().copy(values = values), draftValues = mapOf(maximum to "20"))
            assertNull(parkingCurrentWritePlan(candidate, "4", "5"))
            assertEquals(setOf(left, right), parkingCurrentDraftAnalysis(candidate, "4", "5").errors.keys)
        }
    }

    @Test fun bothCurrentRegistersNeedAReadBaselineEvenIfOnlyOneChanges() {
        for (missing in listOf(left, right)) {
            val candidate = state().copy(snapshot = snapshot().copy(values = snapshot().values - missing))
            assertNull(parkingCurrentWritePlan(candidate, "4", "5"))
            assertTrue(missing in parkingCurrentDraftAnalysis(candidate, "4", "5").errors)
        }
    }

    @Test fun staleBusyUnwritableAndDifferentConnectionsRejectTheWholePlan() {
        val ready = state()
        val plan = requireNotNull(parkingCurrentWritePlan(ready, "4", "5"))
        for (blocked in listOf(ready.copy(snapshot = null), ready.copy(requiresRead = true),
            ready.copy(reading = true), ready.copy(applying = true), ready.copy(actioning = true),
            ready.copy(snapshot = snapshot().copy(writeAllowed = false)),
            ready.copy(snapshot = snapshot().copy(snapshotId = "")),
            ready.copy(connection = MotorSerialSettings(slave = 2)),
            ready.copy(connection = MotorSerialSettings(slave = 0)))) {
            assertNull(parkingCurrentWritePlan(blocked, "4", "5"))
            assertFalse(parkingCurrentWritePlanIsCurrent(blocked, plan))
        }
    }

    @Test fun previouslyReviewedSnapshotIdAndExpectedRawMustStillMatchAtDispatch() {
        val plan = requireNotNull(parkingCurrentWritePlan(state(), "4", "5"))
        assertFalse(parkingCurrentWritePlanIsCurrent(state().copy(snapshot = snapshot().copy(snapshotId = "read-new")), plan))
        assertFalse(parkingCurrentWritePlanIsCurrent(state().copy(snapshot = snapshot().copy(
            values = snapshot().values + (left to 330L))), plan))
        assertFalse(parkingCurrentWritePlanIsCurrent(state(), plan.copy(changes = plan.changes.map {
            if (it.field == left) it.copy(expectedRaw = 324L) else it
        })))
    }

    @Test fun forgedOrExpandedPlansNeverReachTheSharedWriteExecutor() {
        val plan = requireNotNull(parkingCurrentWritePlan(state(), "4", "5"))
        for (forged in listOf(plan.copy(writeMode = MotorConfigurationWriteMode.TEMPORARY),
            plan.copy(changes = emptyList()), plan.copy(changes = plan.changes + plan.changes.first()),
            plan.copy(changes = plan.changes + MotorConfigurationChange(speed, 500, 700)),
            plan.copy(changes = plan.changes + MotorConfigurationChange(maximum, 2000, 6000)),
            plan.copy(changes = listOf(MotorConfigurationChange(left, 325, -1))),
            plan.copy(changes = listOf(MotorConfigurationChange(left, 325, 2001)))))
            assertFalse(parkingCurrentWritePlanIsCurrent(state(), forged))
    }

    @Test fun readHistoryRetainsIndependentFractionalValuesWithoutEnablingParking() {
        val history = requireNotNull(parkingCurrentSettings(snapshot()))
        assertEquals(ParkingSettings(false, 3.25, 4.75, 20.0), history)
        assertFalse(history.enabled)
        assertNull(parkingCurrentSettings(null))
        assertNull(parkingCurrentSettings(snapshot().copy(values = snapshot().values - right)))
        assertNull(parkingCurrentSettings(snapshot().copy(values = snapshot().values + (maximum to 6001L))))
    }
}
