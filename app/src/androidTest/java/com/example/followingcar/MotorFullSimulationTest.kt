package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production App codec <-> real Python bridge <-> in-memory Modbus controller.
 * The loopback gateway has no route that omits --simulate; no board is contacted. */
@RunWith(AndroidJUnit4::class)
class MotorFullSimulationTest {
    @Test fun everyDocumentedConfigurationAndCommandRoundTrips() {
        requireMotorSimulator()
        val readId = UUID.randomUUID().toString()
        val initial = parseMotorConfigurationReply(simulatedMotorRequest(motorConfigurationRequest(readId)),
            readId, simulation = true)
        assertTrue(initial.error, initial.ok)
        val snapshot = requireNotNull(initial.snapshot)
        assertEquals(69, snapshot.values.size)
        assertEquals(69, MotorConfigurationField.entries.size)
        assertEquals(8, MotorAction.entries.size)
        var persisted = 0
        var temporary = 0
        val linkChanging = setOf("rs485_address", "rs485_baud_rate_code", "rs485_parity_code")
        for (field in MotorConfigurationField.entries) {
            val previous = snapshot.values.getValue(field)
            val value = changedSimulationValue(field, previous)
            for (mode in MotorConfigurationWriteMode.entries) {
                if (mode == MotorConfigurationWriteMode.TEMPORARY && !field.supportsTemporary) continue
                val plan = MotorConfigurationWritePlan(snapshot.snapshotId,
                    listOf(MotorConfigurationChange(field, previous, value)), mode)
                val id = UUID.randomUUID().toString()
                val raw = simulatedMotorRequest(motorConfigurationRequest(id, plan))
                val reply = parseMotorConfigurationReply(raw, id, plan, simulation = true)
                assertTrue("${field.id}: no write attempt", reply.writeMayHaveOccurred)
                val trace = JSONObject(raw.stdout).getJSONArray("simulationWrites")
                val splitTemporary = mode == MotorConfigurationWriteMode.TEMPORARY && field.words == 2
                assertEquals(if (splitTemporary) 2 else 1, trace.length())
                if (splitTemporary) {
                    assertEquals(setOf(field.address, field.address + 1),
                        (0 until trace.length()).map { trace.getJSONObject(it).getInt("address") }.toSet())
                    val words = motorWords(field, value)
                    repeat(trace.length()) { index ->
                        val frame = trace.getJSONObject(index)
                        assertEquals(6, frame.getInt("function"))
                        assertEquals(words.getInt(frame.getInt("address") - field.address), frame.getJSONArray("raw").getInt(0))
                    }
                } else {
                    assertEquals(field.address, trace.getJSONObject(0).getInt("address"))
                    assertEquals(if (mode == MotorConfigurationWriteMode.TEMPORARY) 6 else 16,
                        trace.getJSONObject(0).getInt("function"))
                }
                if (field.id in linkChanging) {
                    assertTrue("${field.id}: reconnect must be required", reply.reconnectRequired)
                    assertFalse("Changed bus settings cannot be claimed verified", reply.ok)
                    assertEquals(MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED, reply.results.single().outcome)
                } else {
                    assertTrue("${field.id}: ${reply.error}", reply.ok)
                    assertEquals(value, reply.snapshot!!.values.getValue(field))
                    assertEquals(MotorConfigurationWriteOutcome.VERIFIED, reply.results.single().outcome)
                }
                if (mode == MotorConfigurationWriteMode.PERSISTENT) persisted++ else temporary++
            }
        }
        var commands = 0
        val excessive = MotorActionPlan(snapshot.snapshotId, MotorAction.RIGHT_TARGET_SPEED, 500, 100)
        val excessiveId = UUID.randomUUID().toString()
        val excessiveRaw = simulatedMotorRequest(motorActionRequest(excessiveId, excessive))
        val rejected = parseMotorActionReply(excessiveRaw, excessiveId, excessive, simulation = true)
        assertFalse("A target above the simulated configured maximum must be rejected", rejected.ok)
        assertFalse(rejected.writeMayHaveOccurred)
        assertEquals(0, JSONObject(excessiveRaw.stdout).getJSONArray("simulationWrites").length())
        for (action in MotorAction.entries) {
            val variants = if (action.choices.isEmpty()) listOf(50L, -50L) else action.choices.keys.toList()
            for (value in variants) {
                val duration = action.durationRange(value).first
                val plan = MotorActionPlan(snapshot.snapshotId, action, value, duration)
                val id = UUID.randomUUID().toString()
                val raw = simulatedMotorRequest(motorActionRequest(id, plan))
                val reply = parseMotorActionReply(raw, id, plan, simulation = true)
                assertTrue("${action.id}: ${reply.error}", reply.ok)
                assertEquals(MotorActionOutcome.ACKNOWLEDGED, reply.actionResult!!.outcome)
                assertEquals(action == MotorAction.FACTORY_RESET, reply.reconnectRequired)
                val trace = JSONObject(raw.stdout).getJSONArray("simulationWrites")
                assertEquals(action.address, trace.getJSONObject(0).getInt("address"))
                if (duration > 0) {
                    assertTrue("Bounded operation must clean up", reply.actionResult.cleanupConfirmed == true)
                    assertTrue("Bounded operation must send stop/cancel", trace.length() >= 2)
                }
                commands++
            }
        }
        // New processes deliberately reset only simulated registers, proving no persistence to hardware.
        val finalId = UUID.randomUUID().toString()
        val again = parseMotorConfigurationReply(simulatedMotorRequest(motorConfigurationRequest(finalId)),
            finalId, simulation = true)
        assertEquals(snapshot.values, again.snapshot!!.values)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("motorCoverage", JSONObject().put("persistentFields", persisted)
                .put("temporaryFields", temporary).put("commandVariants", commands)
                .put("hardwareTouched", false).toString())
        })
    }
}

internal fun changedSimulationValue(field: MotorConfigurationField, previous: Long): Long {
    val candidates = if (field.choices.isNotEmpty()) field.choices.keys.toList()
        else if (field.phasePair) listOf(1L, 2L)
        else if (field.id.endsWith("parking_current")) listOf(100L, 101L)
        else if (field == MotorConfigurationField.MAX_OUTPUT_CURRENT) listOf(1001L, 1002L)
        else if (field.signed) listOf(-100L, -200L)
        else listOf(field.minRaw, field.minRaw + 1, 65536L, 32768L)
    return candidates.first { it != previous && field.accepts(it) }
}

internal fun requireMotorSimulator() {
    assumeTrue("Opt in with motorSimulator=true",
        InstrumentationRegistry.getArguments().getString("motorSimulator") == "true")
    val health = URL("http://127.0.0.1:28765/health").openConnection() as HttpURLConnection
    try {
        health.connectTimeout = 3000; health.readTimeout = 3000
        val info = JSONObject(health.inputStream.bufferedReader().use { it.readText() })
        assertEquals("followingcar-motor-simulator", info.getString("kind"))
        assertTrue(info.getBoolean("simulation"))
    } finally { health.disconnect() }
}

internal fun simulatedMotorRequest(request: ByteArray): CommandResult {
    val link = URL("http://127.0.0.1:28765/request").openConnection() as HttpURLConnection
    try {
        link.requestMethod = "POST"; link.doOutput = true
        link.connectTimeout = 5000; link.readTimeout = 80_000
        link.setRequestProperty("Content-Type", "application/json")
        link.outputStream.use { it.write(request) }
        assertEquals("Simulator transport failed", 200, link.responseCode)
        val body = JSONObject(link.inputStream.bufferedReader().use { it.readText() })
        val stdout = body.getString("stdout")
        assertTrue(JSONObject(stdout).getBoolean("simulation"))
        assertTrue("Must fit production motor response buffer", stdout.toByteArray().size <= 128 * 1024)
        return CommandResult("motor_simulation", stdout, "", body.getInt("exitCode"))
    } finally { link.disconnect() }
}
