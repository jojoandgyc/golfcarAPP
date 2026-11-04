package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.DiagnosticTestSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Memory-only fixtures. No SSH socket, video service, serial port or motor is opened. */
@RunWith(AndroidJUnit4::class)
class BoardParkingProtocolTest {
    private val channels = mutableListOf<BoardDriveChannel>()
    @After fun close() { channels.forEach { it.close("parking_protocol_test") } }

    @Test fun idleReadKeepsUnknownModeAndSigned32BitSpeed() {
        val request = ParkingRequest("read")
        val json = result("id", request).put("leftRpm", -110000).put("rightRpm", 110000)
        val reply = parseParkingReply(json, "id", request, simulation = true)
        assertNull(reply.mode)
        assertEquals("unknown", reply.modeSource)
        assertEquals(-110000, reply.leftRpm)
        assertEquals(110000, reply.rightRpm)
        assertFalse(reply.writeMayHaveOccurred)
    }

    @Test fun requestCarriesOnlyExplicitOperationAndValidatedHundredths() {
        assertEquals(425, parkingDraftRaw("4.25"))
        assertEquals(0, parkingDraftRaw("0"))
        assertNull(parkingDraftRaw(""))
        assertNull(parkingDraftRaw("NaN"))
        assertNull(parkingDraftRaw("60.01"))
        assertNull(parkingDraftRaw("1.001"))
        val json = JSONObject(String(parkingRequestBytes("request-id", ParkingRequest("currents", leftRaw = 425, rightRaw = 0))))
        assertEquals(setOf("schemaVersion", "requestId", "op", "leftRaw", "rightRaw"), json.keys().asSequence().toSet())
        assertEquals(425, json.getInt("leftRaw"))
        assertFalse(json.has("snapshotId"))
    }

    @Test fun simulationIdentityTypesAndUnmatchedReadbackAreRejected() {
        val request = ParkingRequest("mode", enabled = true)
        val invalid = listOf(result("id", request).put("simulation", false),
            result("id", request).put("simulation", "true"),
            result("id", request).put("requestId", "other"),
            result("id", request).put("mode", false),
            result("id", request).put("leftRaw", 1.5),
            result("id", request).put("modeSource", "unknown"))
        invalid.forEach { assertTrue(runCatching { parseParkingReply(it, "id", request, true) }.isFailure) }
        val read = ParkingRequest("read")
        assertTrue(runCatching { parseParkingReply(result("id", read).put("writeMayHaveOccurred", true), "id", read, true) }.isFailure)
    }

    @Test fun failedReadAllowsNoFabricatedMeasurementsAndPartialWriteHasPerSideOutcome() {
        val read = ParkingRequest("read")
        val unavailable = result("id", read).put("ok", false).put("errorCode", "control_busy")
        listOf("leftRaw", "rightRaw", "maxRaw", "leftRpm", "rightRpm", "leftError", "rightError")
            .forEach { unavailable.put(it, JSONObject.NULL) }
        assertNull(parseParkingReply(unavailable, "id", read, true).leftRaw)
        val request = ParkingRequest("currents", leftRaw = 800, rightRaw = 900)
        val partial = result("id", request).put("ok", false).put("errorCode", "readback_mismatch")
            .put("leftRaw", 800).put("rightRaw", 500).put("results", JSONArray()
                .put(row("left", "verified", 800, 800)).put(row("right", "unknown", 900, 500)))
        val reply = parseParkingReply(partial, "id", request, true)
        assertFalse(reply.ok)
        assertTrue(reply.writeMayHaveOccurred)
        assertEquals(listOf("verified", "unknown"), reply.results.map { it.status })
        assertTrue(parkingReplyError(reply).contains("回读"))
    }

    @Test fun modeCommandConfirmsWhileMovingWithoutClaimingStandstill() = runBlocking {
        val request = ParkingRequest("mode", enabled = true)
        val response = ack(1).put("parkingEnabled", true).put("parkingMode", true).put("parkingModeSource", "manual_session").put("stopped", false)
            .put("feedbackLeftRpm", 7).put("feedbackRightRpm", -3).put("feedbackSamples", 0)
            .put("parkingResult", result("1", request).put("leftRpm", 7).put("rightRpm", -3))
        val control = open(ready(), response)
        val received = control.parkingSettings(request)
        assertFalse(received.stopped)
        assertTrue(received.targetStopped)
        assertEquals(true, received.parkingResult?.mode)
        assertTrue(control.isOpen)
    }

    @Test fun negotiatedPendingModeAndZeroCurrentPersistWithoutDiscardingRecoveryLock() = runBlocking {
        val on = ParkingRequest("mode", enabled = true)
        val currents = ParkingRequest("currents", leftRaw = 0, rightRaw = 500)
        fun pending(json: JSONObject) = json.put("parkingEnabled", true).put("parkingMode", true)
            .put("parkingModeSource", "manual_session").put("recoveryRequired", true)
            .put("feedbackRecheck", true).put("standstillPending", true)
        val control = open(ready(), pending(ack(1)).put("parkingResult", result("1", on)),
            pending(ack(2)).put("parkingLeftCurrentAmps", 0.0)
                .put("parkingResult", result("2", currents).put("mode", true).put("modeSource", "manual_session")),
            pending(ack(3)).put("parkingLeftCurrentAmps", 0.0))
        assertTrue(control.parkingSettings(on).standstillPending)
        assertEquals(0.0, control.parkingSettings(currents).parking?.leftCurrentAmps)
        val heartbeat = control.exchange(0, 0)
        assertTrue(heartbeat.standstillPending && heartbeat.recoveryRequired)
        assertTrue(heartbeat.parking?.enabled == true)
        assertTrue(control.isOpen)
    }

    @Test fun softOperationRefusalKeepsOwnerSessionAndNextHeartbeatUsable() = runBlocking {
        val request = ParkingRequest("mode", enabled = true)
        val refused = result("1", request).put("ok", false).put("errorCode", "external_control_source")
            .put("mode", false).put("modeSource", "manual_session").put("writeMayHaveOccurred", false)
        val control = open(ready(), ack(1).put("parkingResult", refused), ack(2))
        assertFalse(requireNotNull(control.parkingSettings(request).parkingResult).ok)
        assertTrue(control.exchange(0, 0).stopped)
        assertTrue(control.isOpen)
    }

    @Test fun movingReadRefusalReportsRealTargetInsteadOfPretendingToStop() = runBlocking {
        val request = ParkingRequest("read")
        val refused = result("1", request).put("ok", false).put("errorCode", "motion_targets_active")
            .put("mode", false).put("modeSource", "manual_session")
        val control = open(ready(), ack(1).put("left", 10).put("right", 10).put("targetStopped", false)
            .put("stopped", false).put("parkingResult", refused), ack(2))
        val received = control.parkingSettings(request)
        assertEquals(10, received.left)
        assertFalse(received.targetStopped)
        assertTrue(control.exchange(0, 0).stopped)
    }

    @Test fun changingMaxCurrentOrUnnegotiatedCapabilityIsRejected() = runBlocking {
        val request = ParkingRequest("read")
        val old = open(ready().put("unifiedParkingSupported", false))
        assertTrue(runCatching { old.parkingSettings(request) }.isFailure)
        assertTrue(old.isOpen)
        val bad = open(ready(), ack(1).put("parkingMaxCurrentAmps", 50.0)
            .put("parkingResult", result("1", request).put("maxRaw", 5000)))
        assertTrue(runCatching { bad.parkingSettings(request) }.isFailure)
        assertFalse(bad.isOpen)
    }

    @Test fun partialWriteResultSurvivesAHardMotorFaultWithoutKeepingControlAlive() = runBlocking {
        val request = ParkingRequest("currents", leftRaw = 800, rightRaw = 900)
        val partial = result("1", request).put("ok", false).put("errorCode", "motor_fault")
            .put("leftRaw", 800).put("rightRaw", 500).put("leftError", 2)
            .put("results", JSONArray().put(row("left", "verified", 800, 800))
                .put(row("right", "not_attempted", 900, 500)))
        val control = open(ready(), ack(1).put("stopped", false).put("feedbackLeftError", 2)
            .put("parkingLeftCurrentAmps", 8.0).put("parkingResult", partial))
        val failure = runCatching { control.parkingSettings(request) }.exceptionOrNull()
        assertTrue(failure is ParkingResultException)
        assertEquals("verified", (failure as ParkingResultException).result.results.first().status)
        assertEquals(800, failure.result.results.first().actualRaw)
        assertFalse(control.isOpen)
    }

    private fun result(id: String, request: ParkingRequest) = JSONObject()
        .put("type", "parking").put("schemaVersion", 1).put("requestId", id).put("op", request.op)
        .put("ok", true).put("simulation", true).put("mode", request.enabled ?: JSONObject.NULL)
        .put("modeSource", if (request.enabled == null) "unknown" else "command_ack")
        .put("leftRaw", request.leftRaw ?: 500).put("rightRaw", request.rightRaw ?: 500).put("maxRaw", 6000)
        .put("leftRpm", 0).put("rightRpm", 0).put("leftError", 0).put("rightError", 0)
        .put("writeMayHaveOccurred", request.op != "read").put("errorCode", JSONObject.NULL)
        .put("results", JSONArray().apply { if (request.op == "currents") {
            put(row("left", "verified", request.leftRaw!!, request.leftRaw))
            put(row("right", "verified", request.rightRaw!!, request.rightRaw))
        } })
    private fun row(side: String, status: String, wanted: Int, actual: Int?) = JSONObject()
        .put("side", side).put("status", status).put("requestedRaw", wanted).put("actualRaw", actual ?: JSONObject.NULL)
    private fun ready() = JSONObject().put("type", "ready").put("token", token(1))
        .put("port", "synthetic-only").put("dryRun", true).put("watchdogMs", 600).put("maxRpm", 100)
        .put("networkGraceMs", 5000).put("unifiedParkingSupported", true).withDriveSafetyFixture()
    private fun ack(sequence: Int) = JSONObject().put("type", "ack").put("seq", sequence)
        .put("token", token(sequence + 1)).put("left", 0).put("right", 0)
        .put("recoveryRequired", false).put("parkingMode", JSONObject.NULL)
        .put("parkingModeSource", "unknown").withDriveSafetyFixture()
    private fun token(value: Int) = value.toString(16).padStart(32, '0')
    private suspend fun open(vararg responses: JSONObject): BoardDriveChannel {
        val channel = object : ChannelExec() {
            private val input = ByteArrayInputStream(responses.joinToString("\n", postfix = "\n").toByteArray())
            private val output = ByteArrayOutputStream()
            private var alive = false
            override fun setCommand(command: String) { check(command == driveBridgeCommand(true)) }
            override fun connect(timeout: Int) { alive = true }
            override fun isConnected() = alive
            override fun isClosed() = !alive
            override fun isEOF() = false
            override fun getInputStream(): InputStream = input
            override fun getOutputStream(): OutputStream = output
            override fun disconnect() { alive = false }
        }
        return BoardDriveChannel.open(DiagnosticTestSession(channel), true, Diagnostics.newContext()) { _, _ ->
            fail("Synthetic protocol checks must not use a real transport")
        }.also(channels::add)
    }
}
