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
        .open("motor-config-snapshot-v2.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

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
        if (field.words == 2) put((raw ushr 16).toInt())
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

    @Test fun simulatedBridgeFixtureParsesEveryConfigurationAndTelemetryField() {
        val reply = parse()
        assertTrue(reply.ok)
        assertFalse(reply.writeMayHaveOccurred)
        assertTrue(reply.results.isEmpty())
        val snapshot = requireNotNull(reply.snapshot)
        assertEquals(69, snapshot.values.size)
        assertEquals(MotorConfigurationField.entries.toSet(), snapshot.values.keys)
        assertEquals(7, snapshot.leftMotorInfo.size)
        assertEquals(7, snapshot.rightMotorInfo.size)
        assertTrue(snapshot.deviceInfo.any { it.address == 0x1f })
        assertTrue(snapshot.deviceInfo.any { it.address == 0x20 })
        assertEquals(1000L, snapshot.values[MotorConfigurationField.MAX_OUTPUT_CURRENT])
        assertEquals(0L, snapshot.leftParkingCurrentRaw)
        assertTrue(snapshot.writeAllowed)
        assertTrue(snapshot.stopAllowed)
        val signed = fixture()
        field(signed, "left_foc_compensation_angle").put("raw", JSONArray(listOf(0xb9b0)))
        assertEquals(-18000L, parse(signed).snapshot!!.values[MotorConfigurationField.LEFT_FOC_COMPENSATION_ANGLE])
        assertEquals("1,2", MotorConfigurationField.LEFT_PHASE_1_2.display(0x0201))
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
        assertEquals("persistent", encoded.getString("writeMode"))
        assertEquals(2, encoded.getInt("schemaVersion"))
        assertFalse("Read requests must contain no mutation data",
            JSONObject(String(motorConfigurationRequest(REQUEST_ID), Charsets.UTF_8)).has("changes"))
    }

    @Test fun temporaryDoubleWordSpeedsUseCompleteLogicalValuesAndPartialFailureIsUnknown() {
        val source = parse().snapshot!!
        val changes = listOf(MotorConfigurationField.MAX_FORWARD_SPEED,
            MotorConfigurationField.MAX_REVERSE_SPEED, MotorConfigurationField.MIN_SPEED).map { field ->
            MotorConfigurationChange(field, source.values.getValue(field), 110000)
        }
        val plan = MotorConfigurationWritePlan(source.snapshotId, changes, MotorConfigurationWriteMode.TEMPORARY)
        val request = JSONObject(String(motorConfigurationRequest(REQUEST_ID, plan), Charsets.UTF_8))
        assertEquals("temporary", request.getString("writeMode"))
        val encoded = request.getJSONArray("changes")
        changes.forEachIndexed { index, change ->
            assertEquals(change.field.id, encoded.getJSONObject(index).getString("id"))
            assertEquals("[1,44464]", encoded.getJSONObject(index).getJSONArray("raw").toString())
        }
        assertTrue(parse(successfulWrite(plan), plan).ok)

        // A bridge may have written only one word before losing an ACK. Never expose a
        // verified value, retain a usable baseline, or silently retry that logical field.
        val partial = fixture().put("op", "write").put("ok", false).put("snapshot", JSONObject.NULL)
            .put("writeMayHaveOccurred", true).put("error", JSONObject().put("code", "write_outcome_unknown"))
            .put("results", JSONArray().put(result(changes[0], "write_outcome_unknown"))
                .put(result(changes[1], "not_attempted")).put(result(changes[2], "not_attempted")))
        val reply = parse(partial, plan)
        assertFalse(reply.ok)
        assertTrue(reply.writeMayHaveOccurred)
        assertNull(reply.snapshot)
        assertEquals(MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN, reply.results[0].outcome)
        assertNull(reply.results[0].observedRaw)
        assertEquals(listOf(MotorConfigurationWriteOutcome.NOT_ATTEMPTED,
            MotorConfigurationWriteOutcome.NOT_ATTEMPTED), reply.results.drop(1).map { it.outcome })

        val pid = MotorConfigurationField.PID_SPEED_P
        val pidPlan = MotorConfigurationWritePlan(source.snapshotId,
            listOf(MotorConfigurationChange(pid, source.values.getValue(pid), 32769)),
            MotorConfigurationWriteMode.TEMPORARY)
        try {
            motorConfigurationRequest(REQUEST_ID, pidPlan)
            fail("PID entries support FC10 only")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun responseCorrelationAndSimulationCannotBeConfused() {
        assertRejected(fixture().put("requestId", "other-request"))
        assertRejected(fixture().put("op", "write"))
        assertRejected(fixture().put("simulation", false))
        assertRejected(fixture(), simulation = false)
        assertRejected(fixture().put("schemaVersion", 1))
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

    @Test fun signedWordEncodingAndSerialIdentityAreStrict() {
        val field = MotorConfigurationField.RIGHT_FOC_COMPENSATION_ANGLE
        assertEquals("[47536]", motorWords(field, -18000).toString())
        val source = parse().snapshot!!
        val plan = MotorConfigurationWritePlan(source.snapshotId, listOf(
            MotorConfigurationChange(field, source.values.getValue(field), -18000)))
        val encoded = JSONObject(String(motorConfigurationRequest(REQUEST_ID, plan,
            MotorSerialSettings(12, 38400, 3)), Charsets.UTF_8))
        val connection = encoded.getJSONObject("connection")
        assertEquals(12, connection.getInt("slave"))
        assertEquals(38400, connection.getInt("baudRate"))
        assertEquals("N", connection.getString("parity"))
        assertEquals(2, connection.getInt("stopBits"))
        val mismatch = fixture()
        mismatch.getJSONObject("connection").put("slave", 2)
        assertRejected(mismatch)
        val falseWidth = fixture()
        field(falseWidth, "max_output_current").put("address", 0x59)
        assertRejected(falseWidth)
    }

    @Test fun actionRequestIsConfirmedCorrelatedAndBounded() {
        val plan = MotorActionPlan(parse().snapshot!!.snapshotId, MotorAction.RIGHT_TARGET_SPEED, -100, 200)
        val encoded = JSONObject(String(motorActionRequest(REQUEST_ID, plan), Charsets.UTF_8))
        assertEquals("action", encoded.getString("op"))
        assertEquals("right_target_speed", encoded.getString("actionId"))
        assertEquals(-100, encoded.getInt("value"))
        assertEquals(200, encoded.getInt("durationMs"))
        assertTrue(encoded.getBoolean("confirmed"))
        val root = fixture().put("op", "action").put("writeMayHaveOccurred", true)
            .put("actionResult", JSONObject().put("actionId", plan.action.id).put("value", plan.value)
                .put("durationMs", plan.durationMs).put("outcome", "acknowledged")
                .put("cleanupConfirmed", true).put("reconnectRequired", false).put("error", JSONObject.NULL))
        val reply = parseMotorActionReply(command(root), REQUEST_ID, plan, simulation = true)
        assertTrue(reply.ok)
        assertEquals(MotorActionOutcome.ACKNOWLEDGED, reply.actionResult!!.outcome)
        assertEquals(true, reply.actionResult.cleanupConfirmed)
        root.getJSONObject("actionResult").put("cleanupConfirmed", false)
        try {
            parseMotorActionReply(command(root), REQUEST_ID, plan, simulation = true)
            fail("Bounded action cannot succeed without its cleanup acknowledgement")
        } catch (_: IOException) { }
    }

    @Test fun changedCommunicationRequiresReconnectAndNeverPretendsToHaveReadBack() {
        val source = parse().snapshot!!
        val field = MotorConfigurationField.RS485_ADDRESS
        val plan = MotorConfigurationWritePlan(source.snapshotId, listOf(
            MotorConfigurationChange(field, source.values.getValue(field), 2)))
        val root = fixture().put("op", "write").put("ok", false).put("snapshot", JSONObject.NULL)
            .put("reconnectRequired", true).put("writeMayHaveOccurred", true)
            .put("error", JSONObject().put("code", "communication_reconnect_required"))
            .put("results", JSONArray().put(result(plan.changes.single(), "acknowledged_unverified")))
        val reply = parse(root, plan)
        assertTrue(reply.reconnectRequired)
        assertFalse(reply.ok)
        assertNull(reply.snapshot)
        assertEquals(MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED, reply.results.single().outcome)
    }

    @Test fun factoryResetAcknowledgementRetainsReconnectAndUnknownState() {
        val plan = MotorActionPlan(parse().snapshot!!.snapshotId, MotorAction.FACTORY_RESET, 1, 0)
        val root = fixture().put("op", "action").put("snapshot", JSONObject.NULL)
            .put("reconnectRequired", true).put("writeMayHaveOccurred", true)
            .put("actionResult", JSONObject().put("actionId", plan.action.id).put("value", 1)
                .put("durationMs", 0).put("outcome", "acknowledged").put("cleanupConfirmed", JSONObject.NULL)
                .put("reconnectRequired", true).put("error", JSONObject.NULL))
        val acknowledged = parseMotorActionReply(command(root), REQUEST_ID, plan, simulation = true)
        assertTrue(acknowledged.ok)
        assertTrue(acknowledged.reconnectRequired)
        assertNull(acknowledged.snapshot)
        root.put("ok", false).put("error", JSONObject().put("code", "write_outcome_unknown"))
        root.getJSONObject("actionResult").put("outcome", "write_outcome_unknown")
        val unknown = parseMotorActionReply(command(root), REQUEST_ID, plan, simulation = true)
        assertFalse(unknown.ok)
        assertTrue(unknown.reconnectRequired)
        assertEquals(MotorActionOutcome.WRITE_OUTCOME_UNKNOWN, unknown.actionResult!!.outcome)
    }

    @Test fun maximumCatalogRequestFitsBoundedBridgeInput() {
        val snapshot = parse().snapshot!!
        val changes = MotorConfigurationField.entries.map { field ->
            val old = snapshot.values.getValue(field)
            val replacement = if (old != field.minRaw && field.accepts(field.minRaw)) field.minRaw
                else if (field.choices.isNotEmpty()) field.choices.keys.first { it != old }
                else if (field.phasePair) 0x0606L else field.maxRaw
            MotorConfigurationChange(field, old, replacement)
        }
        assertTrue(motorConfigurationRequest(REQUEST_ID,
            MotorConfigurationWritePlan(snapshot.snapshotId, changes)).size <= 8192)
    }

    companion object { private const val REQUEST_ID = "android-fixture" }
}
