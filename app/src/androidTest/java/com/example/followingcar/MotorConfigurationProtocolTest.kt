package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline Android org.json tests. Fixture comes from the bridge's --request --simulate CLI. */
@RunWith(AndroidJUnit4::class)
class MotorConfigurationProtocolTest {
    private fun fixture(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("motor-config-snapshot-v1.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun command(root: JSONObject, exitCode: Int = 0) = CommandResult(
        command = "offline-motor-config-fixture", stdout = root.toString(), stderr = "", exitCode = exitCode)

    private fun parse(root: JSONObject = fixture(), plan: MotorConfigurationWritePlan? = null,
        simulation: Boolean = true): MotorConfigurationReply =
        parseMotorConfigurationReply(command(root), REQUEST_ID, plan, simulation)

    private fun field(root: JSONObject, id: String): JSONObject {
        val fields = root.getJSONObject("snapshot").getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it) }.single { it.getString("id") == id }
    }

    private fun assertRejected(root: JSONObject, plan: MotorConfigurationWritePlan? = null,
        simulation: Boolean = true) {
        try {
            parse(root, plan, simulation)
            fail("Malformed or uncorrelated motor configuration must be rejected")
        } catch (_: IOException) { /* Fail closed: no snapshot or write-success result is exposed. */ }
    }

    private fun writePlan() = MotorConfigurationWritePlan(requireNotNull(parse().snapshot).snapshotId, listOf(
        MotorConfigurationChange(MotorConfigurationField.MAX_OUTPUT_CURRENT, 1000, 1251),
        MotorConfigurationChange(MotorConfigurationField.MAX_FORWARD_SPEED, 100, 110000),
        MotorConfigurationChange(MotorConfigurationField.MAX_REVERSE_SPEED, 100, 200)))

    private fun words(field: MotorConfigurationField, raw: Long) = JSONArray().apply {
        if (field.maxRaw > 65535) put((raw ushr 16).toInt())
        put((raw and 65535).toInt())
    }

    private fun result(change: MotorConfigurationChange, status: String, actual: Long? = null): JSONObject =
        JSONObject().put("id", change.field.id).put("status", status)
            .put("requestedRaw", words(change.field, change.raw))
            .put("actualRaw", actual?.let { words(change.field, it) } ?: JSONObject.NULL)
            .put("error", JSONObject.NULL)

    private fun successfulWrite(plan: MotorConfigurationWritePlan): JSONObject {
        val root = fixture().put("op", "write").put("writeMayHaveOccurred", true)
        val results = JSONArray()
        plan.changes.forEach { change ->
            results.put(result(change, "verified", change.raw))
            field(root, change.field.id).put("raw", words(change.field, change.raw))
        }
        return root.put("results", results)
    }

    @Test fun simulatedBridgeFixtureParsesFiveFieldsAndReadOnlyPhaseWords() {
        val reply = parse()
        assertTrue(reply.ok)
        assertFalse(reply.writeMayHaveOccurred)
        assertTrue(reply.results.isEmpty())
        val snapshot = requireNotNull(reply.snapshot)
        assertEquals(setOf(MotorConfigurationField.MAX_OUTPUT_CURRENT, MotorConfigurationField.MAX_FORWARD_SPEED,
            MotorConfigurationField.MAX_REVERSE_SPEED, MotorConfigurationField.CLOSED_LOOP_ACCELERATION,
            MotorConfigurationField.CLOSED_LOOP_DECELERATION), snapshot.values.keys)
        assertEquals(1000L, snapshot.values[MotorConfigurationField.MAX_OUTPUT_CURRENT])
        assertEquals(100L, snapshot.values[MotorConfigurationField.MAX_FORWARD_SPEED])
        assertEquals(200L, snapshot.values[MotorConfigurationField.CLOSED_LOOP_ACCELERATION])
        assertEquals(300L, snapshot.values[MotorConfigurationField.CLOSED_LOOP_DECELERATION])
        assertEquals(0L, snapshot.leftParkingCurrentRaw)
        assertEquals(0L, snapshot.rightParkingCurrentRaw)
        assertTrue(snapshot.writeAllowed)

        val root = fixture()
        field(root, "left_phase_sequence").put("raw", JSONArray(listOf(0x1234, 0xabcd, 0x00ff)))
        field(root, "right_phase_sequence").put("raw", JSONArray(listOf(0x1111, 0x2222, 0x3333)))
        val phaseDetails = requireNotNull(parse(root).snapshot).readOnlyInfo.map { it.value.orEmpty() }
        assertTrue(phaseDetails.any { it.contains("0x1234 · 0xABCD · 0x00FF") })
        assertTrue(phaseDetails.any { it.contains("0x1111 · 0x2222 · 0x3333") })
    }

    @Test fun requestUsesExactCurrentAndHighWordFirstForMaximumSpeed() {
        val plan = writePlan()
        assertEquals(1251L, MotorConfigurationField.MAX_OUTPUT_CURRENT.parse("12.51"))
        val encoded = JSONObject(String(motorConfigurationRequest(REQUEST_ID, plan), Charsets.UTF_8))
        assertEquals("write", encoded.getString("op"))
        assertEquals(plan.snapshotId, encoded.getString("snapshotId"))
        val changes = encoded.getJSONArray("changes")
        assertEquals(3, changes.length())
        assertEquals("[1000]", changes.getJSONObject(0).getJSONArray("expectedRaw").toString())
        assertEquals("[1251]", changes.getJSONObject(0).getJSONArray("raw").toString())
        assertEquals("[0,100]", changes.getJSONObject(1).getJSONArray("expectedRaw").toString())
        assertEquals("[1,44464]", changes.getJSONObject(1).getJSONArray("raw").toString())
        assertFalse("Read requests must contain no mutation data",
            JSONObject(String(motorConfigurationRequest(REQUEST_ID), Charsets.UTF_8)).has("changes"))
    }

    @Test fun responseCorrelationAndSimulationCannotBeConfused() {
        assertRejected(fixture().put("requestId", "other-request"))
        assertRejected(fixture().put("op", "write"))
        assertRejected(fixture().put("simulation", false))
        assertRejected(fixture(), simulation = false)
        assertRejected(fixture().put("schemaVersion", 2))
        assertRejected(fixture().put("ok", "true"))
    }

    @Test fun requiredFieldsAndRegisterWordsMustRemainCompleteAndIntegral() {
        val missing = fixture()
        val fields = missing.getJSONObject("snapshot").getJSONArray("fields")
        val retained = JSONArray()
        repeat(fields.length()) { index ->
            if (fields.getJSONObject(index).getString("id") != "max_forward_speed") retained.put(fields.getJSONObject(index))
        }
        missing.getJSONObject("snapshot").put("fields", retained)
        assertRejected(missing)
        val decimal = fixture()
        field(decimal, "max_output_current").put("raw", JSONArray(listOf(1000.5)))
        assertRejected(decimal)
        val tooWide = fixture()
        field(tooWide, "max_forward_speed").put("raw", JSONArray(listOf(0, 65536)))
        assertRejected(tooWide)
        val short = fixture()
        field(short, "max_forward_speed").put("raw", JSONArray(listOf(100)))
        assertRejected(short)
        val unexpectedThirdWord = fixture()
        field(unexpectedThirdWord, "left_parking_current").put("raw", JSONArray(listOf(0, 0, 0)))
        assertRejected(unexpectedThirdWord)
    }

    @Test fun verifiedResultRequiresBothMatchingReadbackAndMatchingSnapshot() {
        val plan = writePlan()
        assertTrue(parse(successfulWrite(plan), plan).ok)
        val mismatch = successfulWrite(plan)
        mismatch.getJSONArray("results").getJSONObject(0).put("actualRaw", JSONArray(listOf(1250)))
        assertRejected(mismatch, plan)
        val staleSnapshot = successfulWrite(plan)
        field(staleSnapshot, "max_output_current").put("raw", JSONArray(listOf(1000)))
        assertRejected(staleSnapshot, plan)
        val missingActual = successfulWrite(plan)
        missingActual.getJSONArray("results").getJSONObject(0).put("actualRaw", JSONObject.NULL)
        assertRejected(missingActual, plan)
    }

    @Test fun partialUnknownWriteNeverBecomesSuccessOrAFreshBaseline() {
        val plan = writePlan()
        val partial = fixture().put("op", "write").put("ok", false).put("snapshot", JSONObject.NULL)
            .put("writeMayHaveOccurred", true).put("error", JSONObject().put("code", "write_outcome_unknown").put("field", "max_forward_speed"))
            .put("results", JSONArray().put(result(plan.changes[0], "verified", 1251))
                .put(result(plan.changes[1], "write_outcome_unknown"))
                .put(result(plan.changes[2], "not_attempted")))
        val reply = parse(partial, plan)
        assertFalse(reply.ok)
        assertTrue(reply.writeMayHaveOccurred)
        assertNull(reply.snapshot)
        assertEquals(listOf(MotorConfigurationWriteOutcome.VERIFIED, MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN,
            MotorConfigurationWriteOutcome.NOT_ATTEMPTED), reply.results.map { it.outcome })
        assertNull(reply.results[1].observedRaw)

        partial.getJSONArray("results").getJSONObject(1).put("status", "acknowledged_unverified")
        val acknowledged = parse(partial, plan)
        assertFalse(acknowledged.ok)
        assertEquals(MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED, acknowledged.results[1].outcome)
        assertNull(acknowledged.results[1].observedRaw)
    }

    @Test fun snapshotFailureHasNoDefaultValuesAndSuccessfulMissingSnapshotIsRejected() {
        val failed = fixture().put("ok", false).put("snapshot", JSONObject.NULL)
            .put("error", JSONObject().put("code", "control_busy").put("field", JSONObject.NULL))
        val reply = parse(failed)
        assertFalse(reply.ok)
        assertNull(reply.snapshot)
        assertFalse(reply.writeMayHaveOccurred)
        assertTrue(reply.results.isEmpty())
        assertNotNull(reply.error)
        assertRejected(fixture().put("snapshot", JSONObject.NULL))
        val missingSnapshot = fixture()
        missingSnapshot.remove("snapshot")
        assertRejected(missingSnapshot)
    }

    companion object { private const val REQUEST_ID = "android-fixture" }
}
