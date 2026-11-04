package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production Android codec -> loopback gateway -> Python --simulate only. */
@RunWith(AndroidJUnit4::class)
class ParkingUnifiedSimulationTest {
    private fun query(request: ParkingRequest): ParkingReply {
        requireMotorSimulator()
        val id = UUID.randomUUID().toString()
        val link = URL("http://127.0.0.1:28765/parking-request").openConnection() as HttpURLConnection
        try {
            link.requestMethod = "POST"; link.doOutput = true
            link.connectTimeout = 3000; link.readTimeout = 10000
            link.setRequestProperty("Content-Type", "application/json")
            link.outputStream.use { it.write(parkingRequestBytes(id, request)) }
            assertEquals(200, link.responseCode)
            val body = JSONObject(link.inputStream.bufferedReader().use { it.readText() })
            val result = CommandResult("parking_simulation", body.getString("stdout"), "", body.getInt("exitCode"))
            return parseParkingReply(result, id, request, simulation = true)
        } finally { link.disconnect() }
    }

    @Test fun readDoesNotInventAReleasedModeAndModeCommandsPreserveCurrents() {
        val read = query(ParkingRequest("read"))
        assertTrue(read.errorCode, read.ok)
        assertNull(read.mode)
        assertEquals("unknown", read.modeSource)
        assertEquals(800, read.leftRaw); assertEquals(800, read.rightRaw)
        for (enabled in listOf(true, false)) {
            val changed = query(ParkingRequest("mode", enabled = enabled))
            assertTrue(changed.errorCode, changed.ok)
            assertEquals(enabled, changed.mode)
            assertEquals("command_ack", changed.modeSource)
            assertEquals(800, changed.leftRaw); assertEquals(800, changed.rightRaw)
            assertTrue(changed.results.isEmpty())
        }
    }

    @Test fun saveReadsItsOwnBaselineWithoutASeparateReadOrFullMotorSnapshot() {
        val saved = query(ParkingRequest("currents", leftRaw = 500, rightRaw = 600))
        assertTrue(saved.errorCode, saved.ok)
        assertEquals(500, saved.leftRaw); assertEquals(600, saved.rightRaw)
        assertEquals(1500, saved.maxRaw)
        assertEquals(setOf("left", "right"), saved.results.map { it.side }.toSet())
        assertTrue(saved.results.all { it.status == "verified" && it.actualRaw == it.requestedRaw })
        assertNull(saved.mode)
    }

    @Test fun limitErrorIsAnOperationResultWithNoCurrentWriteInsteadOfAProtocolCrash() {
        val rejected = query(ParkingRequest("currents", leftRaw = 1600, rightRaw = 800))
        assertFalse(rejected.ok)
        assertEquals("current_limit_exceeded", rejected.errorCode)
        assertTrue(rejected.results.all { it.status == "not_attempted" })
        assertEquals(800, rejected.leftRaw); assertEquals(800, rejected.rightRaw)
    }
}
