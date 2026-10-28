package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in SSH tests. --dry-run never loads a camera, model or motor library. */
@RunWith(AndroidJUnit4::class)
class BoardFollowIntegrationTest {
    @Test
    fun selectedFollowOptionsReachBoardConfigurationAndDryRun() = runBlocking {
        val client = connectedClient()
        val options = FollowLaunchOptions(
            targetDistanceMeters = 1.5,
            gesturesEnabled = true,
            resumeGesture = FollowResumeGesture.THUMB_UP,
            avoidanceEnabled = true,
            reverseEnabled = true,
            reverseDistanceMeters = 1.4,
        )
        var channel: BoardFollowChannel? = null
        try {
            withTimeout(45_000) {
                val configuration = client.readFollowConfiguration(FollowCamera.ASTRA_PRO, options)
                assertEquals(1.4, configuration.targetDistanceMeters, 0.0001)
                val control = client.openFollowChannel(FollowCamera.ASTRA_PRO,
                    execute = false, dryRun = true, options = options).also { channel = it }
                assertEquals(FollowCamera.ASTRA_PRO, control.ready.camera)
                var reply = control.start()
                while (reply.status.runState == "STARTING") {
                    delay(100)
                    reply = control.heartbeat()
                }
                assertEquals("RUNNING", reply.status.runState)
                reply = control.requestStop()
                while (!reply.terminal) {
                    delay(100)
                    reply = control.heartbeat()
                }
                assertTrue(reply.status.processExited)
                assertNull(reply.status.physicalStopped)
            }
        } finally {
            channel?.close()
            client.disconnect()
        }
    }

    @Test
    fun configurationAndDryRunFollowUseTheDocumentedContinuousChain() = runBlocking {
        val client = connectedClient()
        var channel: BoardFollowChannel? = null
        try {
            withTimeout(45_000) {
                val configuration = client.readFollowConfiguration(FollowCamera.CONFIG)
                assertTrue(configuration.targetDistanceMeters > 0)
                assertTrue(configuration.models.isNotEmpty())
                val control = client.openFollowChannel(FollowCamera.CONFIG, execute = false, dryRun = true)
                    .also { channel = it }
                assertTrue(control.ready.dryRun)
                var reply = control.start()
                assertFalse(reply.status.execute)
                while (reply.status.runState == "STARTING") {
                    delay(100)
                    reply = control.heartbeat()
                }
                assertEquals("RUNNING", reply.status.runState)
                assertNull(reply.status.physicalStopped)
                reply = control.requestStop()
                while (!reply.terminal) {
                    delay(100)
                    reply = control.heartbeat()
                }
                assertTrue(reply.status.processExited)
                assertNull(reply.status.physicalStopped)
                assertFalse(control.isOpen)
                assertTrue(client.isConnected)
            }
        } finally {
            channel?.close()
            client.disconnect()
        }
    }

    private suspend fun connectedClient(): BoardSshClient {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("boardPassword")
        assumeTrue("Provide boardPassword for opt-in read-only/dry-run board tests", !password.isNullOrEmpty())
        return BoardSshClient().also { client ->
            try {
                client.connect(BoardConnection(
                    arguments.getString("boardHost") ?: "172.16.16.82", 22,
                    arguments.getString("boardUsername") ?: "topeet", requireNotNull(password),
                ))
            } catch (failure: Exception) {
                client.disconnect()
                throw failure
            }
        }
    }
}
