package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SSH, with all mutations restricted to explicit board RAM simulation. */
@RunWith(AndroidJUnit4::class)
class ParkingBridgeIntegrationTest {
    private suspend fun client(): BoardSshClient {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runParkingSimulation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val password = context.getSharedPreferences("board_credentials", 0).getString("password", null)
        require(!password.isNullOrEmpty())
        return BoardSshClient().also {
            it.connect(BoardConnection(args.getString("boardHost") ?: "172.16.16.82", 22, "topeet", password))
        }
    }

    @Test fun deployedStandaloneParkingWorksInExplicitMemorySimulation() = runBlocking {
        val client = client()
        try {
            withTimeout(30_000) {
                val initial = client.parkingRequest(ParkingRequest("read"), simulation = true)
                assertTrue(initial.ok && initial.simulation)
                assertNull(initial.mode)
                val changed = client.parkingRequest(ParkingRequest("currents", leftRaw = 500, rightRaw = 600), simulation = true)
                assertTrue(changed.errorCode, changed.ok)
                assertEquals(500, changed.leftRaw); assertEquals(600, changed.rightRaw)
                assertTrue(changed.results.all { it.status == "verified" })
                for (on in listOf(true, false)) {
                    val result = client.parkingRequest(ParkingRequest("mode", enabled = on), simulation = true)
                    assertTrue(result.errorCode, result.ok)
                    assertEquals(on, result.mode)
                    assertEquals("command_ack", result.modeSource)
                }
                assertTrue(client.isConnected)
            }
        } finally { client.disconnect() }
    }

    @Test fun deployedManualOwnerAcceptsParkingWithoutOpeningAnotherSerialOwner() = runBlocking {
        val client = client()
        var drive: BoardDriveChannel? = null
        try {
            withTimeout(30_000) {
                val control = client.openDriveChannel(dryRun = true).also { drive = it }
                assertTrue(control.ready.dryRun && control.ready.unifiedParkingSupported)
                control.exchange(0, 0)
                val read = control.parkingSettings(ParkingRequest("read"))
                assertTrue(read.parkingResult!!.simulation)
                val changed = control.parkingSettings(ParkingRequest("currents", leftRaw = 500, rightRaw = 600))
                assertTrue(changed.parkingResult!!.ok)
                assertEquals(600, changed.parkingResult!!.rightRaw)
                for (on in listOf(true, false)) {
                    val mode = control.parkingSettings(ParkingRequest("mode", enabled = on))
                    assertTrue(mode.parkingResult!!.ok)
                    assertEquals(on, mode.parkingMode)
                    assertEquals(on, control.exchange(0, 0).parkingMode)
                }
                assertTrue(control.stopAndClose().stopped)
                assertTrue(client.isConnected)
            }
        } finally { drive?.close(); client.disconnect() }
    }
}
