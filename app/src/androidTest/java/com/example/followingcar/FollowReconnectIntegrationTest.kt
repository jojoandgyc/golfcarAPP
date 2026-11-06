package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated authenticated connections and simulated workers only. Never toggles a radio or video. */
@RunWith(AndroidJUnit4::class)
class FollowReconnectIntegrationTest {
    @Test fun networkLossReattachesSameRunAndCompletedRunNeverRestarts() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("useSavedBoardCredentials") == "true" &&
            args.getString("followReconnectDryRun") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = RemoteCredentials(context)
        val profile = requireNotNull(identity.load())
        val settings = context.getSharedPreferences("board_connection", 0)
        val password = context.getSharedPreferences("board_credentials", 0).getString("password", null).orEmpty()
        assumeTrue(password.isNotEmpty())
        val connection = BoardConnection(profile.host, profile.controlPort,
            settings.getString("username", "topeet")!!, password, identity.createTlsConfig())
        var client = BoardSshClient()
        var channel: BoardFollowChannel? = null
        var ownedRunId: String? = null
        var terminal = false
        var stage = "connect"
        val report = JSONObject().put("versionName", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("dryRun", true).put("execute", false).put("realMotorCommandsSent", false)
            .put("videoOperations", 0).put("radioChanges", 0)
        try {
            withTimeout(25_000) { client.connect(connection) }
            stage = "inspect"
            assumeTrue("Control occupied; never replace an existing owner", client.readFollowAvailability().controlAvailable)
            stage = "launch"
            val options = FollowLaunchOptions(gesturesEnabled = true)
            val first = client.openFollowChannel(FollowCamera.ASTRA_PRO, execute = false, dryRun = true,
                options = options, reconnectable = true).also { channel = it }
            assertTrue(first.ready.dryRun && first.ready.reconnectable && first.ready.offlineUntilGesture)
            assertFalse(first.ready.execute)
            ownedRunId = first.ready.runId
            report.put("runId", ownedRunId)
            var reply = first.start()
            withTimeout(8_000) {
                while (reply.status.runState != "RUNNING") {
                    assertFalse(reply.terminal)
                    delay(200)
                    reply = first.heartbeat()
                }
            }
            stage = "network_loss"
            // Close only this test's SSH transport. No stop/close protocol command is sent.
            client.disconnect("test_follow_network_loss")
            first.close("test_follow_network_loss")
            channel = null
            delay(3_000) // Exceeds the original 2 s App lease, retaining the local worker lease.
            stage = "reattach"
            client = BoardSshClient()
            withTimeout(25_000) { client.connect(connection) }
            val resumed = client.attachFollowChannel(requireNotNull(ownedRunId)).also { channel = it }
            assertEquals(ownedRunId, resumed.ready.runId)
            assertTrue(resumed.ready.resumed && resumed.ready.dryRun)
            assertFalse(resumed.ready.execute)
            reply = resumed.heartbeat()
            assertFalse(reply.terminal)
            assertEquals("RUNNING", reply.status.runState)
            report.put("sameRunResumed", true).put("survivedOriginalNetworkLease", true)
            stage = "normal_stop"
            reply = resumed.requestStop()
            withTimeout(12_000) {
                while (!reply.terminal) { delay(200); reply = resumed.heartbeat() }
            }
            terminal = true
            report.put("terminalReceived", true)
            resumed.close("terminal_received")
            channel = null
            stage = "terminal_reattach"
            val ended = client.attachFollowChannel(requireNotNull(ownedRunId)).also { channel = it }
            assertEquals(ownedRunId, ended.ready.runId)
            assertTrue(ended.ready.resumed)
            val finalReply = ended.heartbeat()
            assertTrue(finalReply.terminal && finalReply.status.processExited)
            assertFalse(finalReply.status.finalStatusPending)
            assertEquals("STOPPED", finalReply.status.runState)
            assertTrue(client.readFollowAvailability().controlAvailable)
            report.put("completedRunRemainsTerminal", true).put("controlAvailableAfter", true).put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("failedStage", stage).put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            withContext(NonCancellable) {
                channel?.close("test_cleanup")
                client.disconnect("test_cleanup")
                if (ownedRunId != null && !terminal) {
                    val cleanup = BoardSshClient()
                    var owner: BoardFollowChannel? = null
                    try {
                        withTimeout(25_000) {
                            delay(2_200)
                            cleanup.connect(connection)
                            owner = cleanup.attachFollowChannel(requireNotNull(ownedRunId))
                            check(owner!!.ready.dryRun && !owner!!.ready.execute)
                            var ending = owner!!.requestStop()
                            while (!ending.terminal) { delay(200); ending = owner!!.heartbeat() }
                            report.put("cleanupTerminalReceived", true)
                        }
                    } catch (failure: Exception) {
                        report.put("cleanupUnconfirmed", true).put("cleanupFailureType", failure.javaClass.simpleName)
                    } finally {
                        owner?.close("test_cleanup_finished")
                        cleanup.disconnect("test_cleanup_finished")
                    }
                }
            }
            File(context.filesDir, "follow-reconnect-dryrun-test.json").writeText(report.toString(2))
        }
    }
}
