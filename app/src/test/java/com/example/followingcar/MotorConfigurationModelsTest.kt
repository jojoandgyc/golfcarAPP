package com.example.followingcar

import org.junit.Assert.*
import org.junit.Test

class MotorConfigurationModelsTest {
    private fun snapshot() = MotorConfigurationSnapshot("read-a", 1_700_000_000_000,
        mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to 2000L,
            MotorConfigurationField.MAX_FORWARD_SPEED to 500L,
            MotorConfigurationField.MAX_REVERSE_SPEED to 400L,
            MotorConfigurationField.CLOSED_LOOP_ACCELERATION to 100L,
            MotorConfigurationField.CLOSED_LOOP_DECELERATION to 200L),
        writeAllowed = true, leftParkingCurrentRaw = 500, rightParkingCurrentRaw = 600)

    @Test fun protocolRangesAndCurrentPrecisionAreExact() {
        val current = MotorConfigurationField.MAX_OUTPUT_CURRENT
        assertEquals(50L, current.parse("0.50"))
        assertEquals(1251L, current.parse("12.51"))
        assertEquals(6000L, current.parse("60"))
        listOf("0.49", "60.01", "12.511", "NaN", "Infinity", "1e1", "1,5", "-1", "", "١٢").forEach {
            assertNull(it, current.parse(it))
        }
        assertEquals("0.5", current.display(50))
        assertEquals("12.51", current.display(1251))
        assertEquals(110000L, MotorConfigurationField.MAX_FORWARD_SPEED.parse("110000"))
        assertNull(MotorConfigurationField.MAX_FORWARD_SPEED.parse("110001"))
        assertNull(MotorConfigurationField.MAX_REVERSE_SPEED.parse("12.5"))
        assertEquals(10L, MotorConfigurationField.CLOSED_LOOP_ACCELERATION.parse("10"))
        assertNull(MotorConfigurationField.CLOSED_LOOP_ACCELERATION.parse("9"))
        assertEquals(65535L, MotorConfigurationField.CLOSED_LOOP_DECELERATION.parse("65535"))
        assertNull(MotorConfigurationField.CLOSED_LOOP_DECELERATION.parse("65536"))
    }

    @Test fun normalizedEquivalentInputIsNotWritten() {
        val state = MotorConfigurationUiState(snapshot(), mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "20.00"))
        assertTrue(analyzeMotorConfigurationDraft(state).changes.isEmpty())
        assertNull(motorConfigurationWritePlan(state))
    }

    @Test fun planContainsOnlyChangedFieldsAndOriginalReadIdentity() {
        val state = MotorConfigurationUiState(snapshot(), mapOf(
            MotorConfigurationField.MAX_FORWARD_SPEED to "700",
            MotorConfigurationField.MAX_OUTPUT_CURRENT to "20.0"))
        val plan = requireNotNull(motorConfigurationWritePlan(state))
        assertEquals("read-a", plan.snapshotId)
        assertEquals(listOf(MotorConfigurationChange(MotorConfigurationField.MAX_FORWARD_SPEED, 500, 700)), plan.changes)
    }

    @Test fun oneInvalidInputPreventsTheWholeWritePreview() {
        val state = MotorConfigurationUiState(snapshot(), mapOf(
            MotorConfigurationField.MAX_FORWARD_SPEED to "700",
            MotorConfigurationField.MAX_OUTPUT_CURRENT to "60.01"))
        assertEquals(setOf(MotorConfigurationField.MAX_OUTPUT_CURRENT), analyzeMotorConfigurationDraft(state).errors.keys)
        assertNull(motorConfigurationWritePlan(state))
    }

    @Test fun unreadStaleBusyOrBoardBlockedStateCannotProduceWritePlan() {
        val ready = MotorConfigurationUiState(snapshot(), mapOf(MotorConfigurationField.MAX_FORWARD_SPEED to "700"))
        assertNotNull(motorConfigurationWritePlan(ready))
        listOf(ready.copy(snapshot = null), ready.copy(requiresRead = true), ready.copy(reading = true),
            ready.copy(applying = true), ready.copy(snapshot = snapshot().copy(snapshotId = "")),
            ready.copy(snapshot = snapshot().copy(writeAllowed = false)),
            ready.copy(snapshot = snapshot().copy(values = emptyMap()))).forEach {
            assertNull(motorConfigurationWritePlan(it))
        }
        assertFalse("Unknown board gate defaults to denied", MotorConfigurationSnapshot("x", 1, emptyMap()).writeAllowed)
    }

    @Test fun documentedValueCanRepairOutOfRangeExistingValueWithExpectedSnapshot() {
        val original = snapshot().copy(values = mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to 65000L))
        val state = MotorConfigurationUiState(original, mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "20"))
        assertTrue(analyzeMotorConfigurationDraft(state).errors.isEmpty())
        assertNotNull(motorConfigurationWritePlan(state))
        assertEquals(65000L, motorConfigurationWritePlan(state)!!.changes.single().expectedRaw)
    }

    @Test fun maximumCurrentCannotInvalidateEitherParkingCurrent() {
        val draft = mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "5.5")
        assertNull(motorConfigurationWritePlan(MotorConfigurationUiState(snapshot(), draft)))
        assertNull(motorConfigurationWritePlan(MotorConfigurationUiState(
            snapshot().copy(leftParkingCurrentRaw = 700, rightParkingCurrentRaw = 500), draft)))
        assertNotNull(motorConfigurationWritePlan(MotorConfigurationUiState(snapshot(),
            mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "6"))))
    }

    @Test fun reducingCurrentRequiresBothParkingReadingsButRaisingDoesNotGuessMissingValues() {
        val incomplete = snapshot().copy(rightParkingCurrentRaw = null)
        assertNull(motorConfigurationWritePlan(MotorConfigurationUiState(incomplete,
            mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "15"))))
        assertNotNull(motorConfigurationWritePlan(MotorConfigurationUiState(incomplete,
            mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "25"))))
    }

    @Test fun partialWriteResultsDoNotProvideAFreshWriteBaseline() {
        val results = listOf(MotorConfigurationWriteResult(MotorConfigurationField.MAX_FORWARD_SPEED, 700,
            MotorConfigurationWriteOutcome.VERIFIED, 700),
            MotorConfigurationWriteResult(MotorConfigurationField.MAX_REVERSE_SPEED, 600,
                MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN))
        val state = MotorConfigurationUiState(snapshot = null,
            draftValues = mapOf(MotorConfigurationField.MAX_REVERSE_SPEED to "600"),
            requiresRead = true, writeResults = results)
        assertNull(motorConfigurationWritePlan(state))
        assertEquals(2, state.writeResults.size)
        assertNull(state.writeResults[1].observedRaw)
    }
}
