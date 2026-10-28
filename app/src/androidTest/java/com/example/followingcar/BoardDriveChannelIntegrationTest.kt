package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Always uses --dry-run on the board: no serial device is opened or motor touched. */
@RunWith(AndroidJUnit4::class)
class BoardDriveChannelIntegrationTest {
    @Test
    fun parkingSettingsAreAcknowledgedAndEmergencyClearsParkingInDryRun() = runBlocking {
        val client = connectedClient()
        var drive: BoardDriveChannel? = null
        try {
            withTimeout(30_000) {
                val control = client.openDriveChannel(dryRun = true).also { drive = it }
                assertTrue(control.ready.dryRun)
                assertTrue("Update the board bridge before running parking tests", control.ready.parkingSupported)
                assertEquals(ParkingSettings(), control.ready.parking)
                val parked = ParkingSettings(enabled = true, currentAmps = 6)
                assertEquals(parked, client.configureParking(control, parked).parking)
                assertFalse(control.exchange(30, -30).stopped)
                val stopped = control.exchange(0, 0)
                assertTrue(stopped.stopped)
                assertEquals(parked, stopped.parking)
                assertEquals(parked.copy(enabled = false), control.exchange(0, 0, emergency = true).parking)
                assertFalse(control.isOpen)
                assertTrue(client.isConnected)
            }
        } finally {
            drive?.close()
            client.disconnect()
        }
    }

    @Test
    fun dryRunCommandsAreAcknowledgedAndStopClosesOnlyTheControlChannel() = runBlocking {
        val client = connectedClient()
        var drive: BoardDriveChannel? = null
        try {
            withTimeout(30_000) {
                val control = client.openDriveChannel(dryRun = true).also { drive = it }
                assertTrue(control.ready.dryRun)
                assertEquals(600, control.ready.watchdogMs)
                assertEquals(100, control.ready.maxRpm)
                listOf(50 to -50, -50 to 50, -50 to -50, 50 to 50, 0 to 0).forEach { (left, right) ->
                    val ack = control.exchange(left, right)
                    assertEquals(left, ack.left)
                    assertEquals(right, ack.right)
                    assertEquals(left == 0 && right == 0, ack.stopped)
                    delay(100)
                }
                control.stopAndClose()
                assertFalse(control.isOpen)
                assertTrue(client.isConnected)
                assertEquals("control-closed", client.execute("printf 'control-closed'").stdout)
            }
        } finally {
            drive?.close()
            client.disconnect()
        }
    }

    @Test
    fun dryRunWatchdogEndsTheSessionAndDoesNotResumeStaleMotion() = runBlocking {
        val client = connectedClient()
        var drive: BoardDriveChannel? = null
        try {
            withTimeout(30_000) {
                val control = client.openDriveChannel(dryRun = true).also { drive = it }
                control.exchange(30, -30)
                delay(1200)
                assertFalse("Watchdog must close the control channel", control.isOpen)
                try {
                    control.exchange(30, -30)
                    fail("Expired control session accepted motion")
                } catch (_: IllegalStateException) {
                    // Local closed-channel check rejects the stale session.
                }
                assertTrue(client.isConnected)
                val next = client.openDriveChannel(dryRun = true).also { drive = it }
                assertTrue(next.exchange(0, 0).stopped)
                assertTrue(next.exchange(0, 0, emergency = true).stopped)
                assertFalse(next.isOpen)
            }
        } finally {
            drive?.close()
            client.disconnect()
        }
    }

    private suspend fun connectedClient(): BoardSshClient {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("boardPassword")
        assumeTrue("Pass boardPassword to opt in to real-board dry-run tests", !password.isNullOrEmpty())
        val client = BoardSshClient()
        try {
            client.connect(BoardConnection(
                arguments.getString("boardHost") ?: "172.16.16.82", 22,
                arguments.getString("boardUsername") ?: "topeet", requireNotNull(password)
            ))
            return client
        } catch (failure: Exception) {
            client.disconnect()
            throw failure
        }
    }
}
