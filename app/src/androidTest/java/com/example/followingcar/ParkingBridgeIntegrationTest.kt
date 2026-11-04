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
                assertEquals(true, initial.mode)
                assertEquals("current_readback", initial.modeSource)
                val changed = client.parkingRequest(ParkingRequest("currents", leftRaw = 500, rightRaw = 600), simulation = true)
                assertTrue(changed.errorCode, changed.ok)
                assertEquals(500, changed.leftRaw); assertEquals(600, changed.rightRaw)
                assertTrue(changed.results.all { it.status == "verified" })
                val disabled = client.parkingRequest(ParkingRequest("currents", leftRaw = 0, rightRaw = 0), simulation = true)
                assertTrue(disabled.errorCode, disabled.ok)
                assertEquals(false, disabled.mode)
                assertEquals(0, disabled.leftRaw); assertEquals(0, disabled.rightRaw)
                val mixed = client.parkingRequest(ParkingRequest("currents", leftRaw = 0, rightRaw = 600), simulation = true)
                assertTrue(mixed.ok)
                assertNull(mixed.mode)
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
                val disabled = control.parkingSettings(ParkingRequest("currents", leftRaw = 0, rightRaw = 0))
                assertTrue(disabled.parkingResult!!.ok)
                assertEquals(false, disabled.parkingMode)
                assertEquals(false, control.exchange(0, 0).parkingMode)
                val mixed = control.parkingSettings(ParkingRequest("currents", leftRaw = 0, rightRaw = 600))
                assertTrue(mixed.parkingResult!!.ok)
                assertNull(mixed.parkingMode)
                val readAgain = control.parkingSettings(ParkingRequest("read")).parkingResult!!
                assertEquals(0, readAgain.leftRaw); assertEquals(600, readAgain.rightRaw)
                assertNull(readAgain.mode)
                assertTrue(control.stopAndClose().stopped)
                assertTrue(client.isConnected)
            }
        } finally { drive?.close(); client.disconnect() }
    }
}
