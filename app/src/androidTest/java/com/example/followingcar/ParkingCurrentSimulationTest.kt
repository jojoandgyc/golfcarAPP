package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production parking editor/codec <-> loopback Python --simulate <-> RAM registers only. */
@RunWith(AndroidJUnit4::class)
class ParkingCurrentSimulationTest {
    @Test fun parkingShortcutWritesOnlyBothCurrentsAndRejectsAStaleBaseline() {
        requireMotorSimulator()
        val readId = UUID.randomUUID().toString()
        val initial = parseMotorConfigurationReply(
            simulatedMotorRequest(motorConfigurationRequest(readId)), readId, simulation = true)
        assertTrue(initial.error, initial.ok)
        val before = requireNotNull(initial.snapshot)
        val parkingFields = setOf(MotorConfigurationField.LEFT_PARKING_CURRENT,
            MotorConfigurationField.RIGHT_PARKING_CURRENT)
        val unrelatedDrafts = mapOf(MotorConfigurationField.CLOSED_LOOP_ACCELERATION to "250",
            MotorConfigurationField.MAX_OUTPUT_CURRENT to "1")
        val state = MotorConfigurationUiState(snapshot = before, draftValues = unrelatedDrafts,
            writeMode = MotorConfigurationWriteMode.TEMPORARY, connection = before.connection)

        val plan = requireNotNull(parkingCurrentWritePlan(state, "5", "5"))
        assertEquals(MotorConfigurationWriteMode.PERSISTENT, plan.writeMode)
        assertEquals(before.snapshotId, plan.snapshotId)
        assertEquals(2, plan.changes.size)
        assertEquals(parkingFields, plan.changes.map { it.field }.toSet())
        plan.changes.forEach { change ->
            assertEquals(before.values.getValue(change.field), change.expectedRaw)
            assertEquals(500L, change.raw)
        }
        assertTrue(parkingCurrentWritePlanIsCurrent(state, plan))
        assertEquals(unrelatedDrafts, state.draftValues)
        assertEquals(MotorConfigurationWriteMode.TEMPORARY, state.writeMode)

        val writeId = UUID.randomUUID().toString()
        val raw = simulatedMotorRequest(motorConfigurationRequest(writeId, plan, before.connection))
        val reply = parseMotorConfigurationReply(raw, writeId, plan, simulation = true,
            connection = before.connection)
        assertTrue(reply.error, reply.ok)
        assertTrue(reply.writeMayHaveOccurred)
        assertFalse(reply.reconnectRequired)
        assertEquals(2, reply.results.size)
        assertEquals(parkingFields, reply.results.map { it.field }.toSet())
        reply.results.forEach { result ->
            assertEquals(MotorConfigurationWriteOutcome.VERIFIED, result.outcome)
            assertEquals(500L, result.requestedRaw)
            assertEquals(500L, requireNotNull(result.observedRaw))
        }
        val trace = JSONObject(raw.stdout).getJSONArray("simulationWrites")
        assertEquals(2, trace.length())
        assertEquals(setOf(0x006D, 0x0076),
            (0 until trace.length()).map { trace.getJSONObject(it).getInt("address") }.toSet())
        repeat(trace.length()) { index ->
            val frame = trace.getJSONObject(index)
            assertEquals(16, frame.getInt("function"))
            assertEquals(1, frame.getJSONArray("raw").length())
            assertEquals(500, frame.getJSONArray("raw").getInt(0))
        }
        val after = requireNotNull(reply.snapshot)
        parkingFields.forEach { assertEquals(500L, after.values.getValue(it)) }
        assertEquals(before.values.filterKeys { it !in parkingFields },
            after.values.filterKeys { it !in parkingFields })
        assertNotEquals(before.snapshotId, after.snapshotId)

        // Each simulator invocation starts from its fixed seed. A reviewed plan
        // based on the previous process's changed snapshot must therefore fail
        // before any write, rather than overwrite the reset configuration.
        val stalePlan = requireNotNull(parkingCurrentWritePlan(state.copy(snapshot = after), "6", "6"))
        assertEquals(after.snapshotId, stalePlan.snapshotId)
        val staleId = UUID.randomUUID().toString()
        val rejectedRaw = simulatedMotorRequest(motorConfigurationRequest(staleId, stalePlan, before.connection))
        val rejected = parseMotorConfigurationReply(rejectedRaw, staleId, stalePlan, simulation = true,
            connection = before.connection)
        assertFalse(rejected.ok)
        assertFalse(rejected.writeMayHaveOccurred)
        assertEquals("snapshot_changed_refresh_required",
            JSONObject(rejectedRaw.stdout).getJSONObject("error").getString("code"))
        assertEquals(0, JSONObject(rejectedRaw.stdout).getJSONArray("simulationWrites").length())
        assertEquals(2, rejected.results.size)
        assertTrue(rejected.results.all { it.outcome == MotorConfigurationWriteOutcome.NOT_ATTEMPTED })
    }
}
