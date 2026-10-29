package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Root orchestrates only the dedicated relay service after the private ready barrier appears. */
@RunWith(AndroidJUnit4::class)
class RemoteRelayFaultIntegrationTest {
    @Test fun relayInterruptionInvalidatesTransportWithoutRestartingDryRunFollow() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit relay fault injection opt-in required",
            args.getString("useSavedBoardCredentials") == "true" &&
                args.getString("remoteRelayFault") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val readyFile = File(app.filesDir, "remote-fault-ready.json")
        val resultFile = File(app.filesDir, "remote-fault-result.json")
        readyFile.delete()
        resultFile.delete()
        val credentials = RemoteCredentials(app)
        val profile = requireNotNull(credentials.load())
        val username = app.getSharedPreferences("board_connection", 0).getString("username", "topeet")!!
        val password = app.getSharedPreferences("board_credentials", 0).getString("password", null).orEmpty()
        assumeTrue("Saved board credentials are required", password.isNotEmpty())
        val client = BoardSshClient()
        var channel: BoardFollowChannel? = null
        var started = false
        var observedFault = false
        var runId: String? = null
        var ackCount = 0
        val diagnostic = Diagnostics.newContext()
        try {
            client.connect(BoardConnection(profile.host, profile.controlPort, username, password,
                credentials.createTlsConfig()), diagnostic)
            assertTrue("Never interrupt an occupied board", client.readFollowAvailability().controlAvailable)
            val follow = client.openFollowChannel(FollowCamera.ASTRA_PRO, execute = false,
                dryRun = true, diagnostic = diagnostic).also { channel = it }
            assertTrue(follow.ready.dryRun)
            assertFalse(follow.ready.execute)
            runId = follow.ready.runId
            assertTrue(runId.matches(Regex("[a-f0-9]{32}")))
            started = true
            var reply = follow.start()
            withTimeout(5000) {
                while (reply.status.runState != "RUNNING") {
                    assertFalse("Worker stopped before injection", reply.terminal)
                    delay(500)
                    reply = follow.heartbeat()
                }
            }
            val barrier = JSONObject().put("schemaVersion", 1).put("type", "remote_fault_ready")
                .put("runId", runId).put("dryRun", true).put("execute", false)
            readyFile.writeText(barrier.toString())
            val faultStarted = System.nanoTime()
            val fault = withTimeout(15_000) {
                var failure: Exception? = null
                while (failure == null) {
                    delay(500)
                    try {
                        reply = follow.heartbeat()
                        ackCount += 1
                        assertFalse("Unexpected terminal response before transport interruption", reply.terminal)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failure = error }
                }
                failure
            }
            observedFault = true
            val connectedAfterFault = client.isConnected
            delay(250)
            val snapshot = Diagnostics.snapshot()
            val first = snapshot.failures.firstOrNull { it.context.operationId == diagnostic.operationId }
            val events = snapshot.events.map(::JSONObject).filter {
                it.optString("operationId") == diagnostic.operationId
            }
            val starts = events.count { it.optString("event") == "follow.request_send" &&
                it.optJSONObject("fields")?.optString("commandType") == "start" }
            val report = JSONObject().put("schemaVersion", 1).put("type", "remote_fault_result")
                .put("runId", runId).put("dryRun", true).put("execute", false)
                .put("faultType", fault.javaClass.simpleName)
                .put("firstFailureEvent", first?.event ?: JSONObject.NULL)
                .put("connectedAfterFailure", connectedAfterFault).put("channelOpenAfterFailure", follow.isOpen)
                .put("successfulHeartbeatCount", ackCount).put("startRequestCount", starts)
                .put("elapsedMs", elapsedMillis(faultStarted))
                .put("passed", !connectedAfterFault && !follow.isOpen && starts == 1 && first != null)
            resultFile.writeText(report.toString(2))
            assertFalse("Lost relay must immediately invalidate cached SSH connectivity", connectedAfterFault)
            assertFalse(follow.isOpen)
            assertNotNull("Keep earliest failure for this session", first)
            assertEquals("Fault recovery must never send another start", 1, starts)
        } finally {
            withContext(NonCancellable) {
                // If root never injected a fault, stop the still-owned simulation normally.
                if (started && !observedFault && channel?.isOpen == true) withTimeoutOrNull(12_000) {
                    runCatching {
                        var reply = channel!!.requestStop()
                        while (!reply.terminal) { delay(500); reply = channel!!.heartbeat() }
                    }
                }
                channel?.close("remote_fault_test_cleanup")
                client.disconnect("remote_fault_test_complete", diagnostic)
                if (!resultFile.exists()) resultFile.writeText(JSONObject().put("schemaVersion", 1)
                    .put("type", "remote_fault_result").put("runId", runId ?: JSONObject.NULL)
                    .put("dryRun", true).put("passed", false).put("readyPublished", readyFile.exists())
                    .put("faultObserved", observedFault).toString(2))
            }
        }
    }
}
