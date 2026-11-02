package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in phone-to-board simulation. Never enables video, serial, or real motors. */
@RunWith(AndroidJUnit4::class)
class DriveHeartbeatDryRunTest {
    @Test fun phoneUsesAbsoluteHeartbeatPeriodAndStillExpiresWhenInputStops() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runDriveHeartbeatDryRun") == "true" &&
            args.getString("confirmedControlIdle") == "true")
        val app = instrumentation.targetContext
        val password = app.getSharedPreferences("board_credentials", 0).getString("password", null).orEmpty()
        assumeTrue(password.isNotEmpty())
        val username = app.getSharedPreferences("board_connection", 0).getString("username", "topeet")!!
        val client = BoardSshClient()
        var channel: BoardDriveChannel? = null
        val samples = JSONArray()
        try {
            client.connect(BoardConnection("172.16.16.82", 22, username, password))
            withTimeout(45_000) {
                val active = client.openDriveChannel(dryRun = true).also { channel = it }
                assertTrue(active.ready.dryRun)
                assertEquals(600, active.ready.watchdogMs)
                assertTrue(active.exchange(0, 0).stopped)
                repeat(40) { index ->
                    val began = System.nanoTime()
                    val targets = if (index % 4 == 0) 0 to 0 else 20 to -20
                    val ack = active.exchange(targets.first, targets.second)
                    assertEquals(targets.first, ack.left)
                    assertEquals(targets.second, ack.right)
                    samples.put(elapsedMillis(began))
                    val wait = driveHeartbeatWaitMs(began)
                    if (wait > 0) delay(wait)
                }
                assertTrue(active.exchange(0, 0).stopped)
                delay(1200)
                assertFalse("The unchanged 600 ms watchdog must close even a stopped simulation", active.isOpen)
                assertTrue(runCatching { active.exchange(20, -20) }.isFailure)
                val replacement = client.openDriveChannel(dryRun = true).also { channel = it }
                assertTrue(replacement.ready.dryRun)
                assertTrue(replacement.exchange(0, 0).stopped)
                replacement.stopAndClose()
            }
            val report = JSONObject().put("dryRun", true).put("hardwareTouched", false)
                .put("ackSamplesMs", samples).put("watchdogMs", 600)
            File(app.getExternalFilesDir(null), "drive-heartbeat-dry-run.json").writeText(report.toString())
        } finally {
            channel?.close("dry_run_test_finished")
            client.disconnect("dry_run_test_finished")
        }
    }
}
