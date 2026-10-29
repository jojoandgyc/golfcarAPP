package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opted-in, production mTLS + pinned SSH path. All control bridges use dry-run;
 * no video service commands, camera workers, actual motor registers or Wi-Fi changes.
 */
@RunWith(AndroidJUnit4::class)
class RemoteRelayIntegrationTest {
    @Test fun remoteMutualTlsCarriesSimulatedControlAndCorrelatedFollowSession() = verifyHealthy(includeManual = true)

    @Test fun remoteFollowOnlyKeepsOriginalLeaseAndCorrelatesNormalStop() = verifyHealthy(includeManual = false)

    private fun verifyHealthy(includeManual: Boolean) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires idle-board remote relay dry-run opt-in",
            args.getString("useSavedBoardCredentials") == "true" &&
                args.getString("remoteRelayDryRun") == "true")
        val relayTransport = args.getString("relayTransport") ?: "unknown"
        require(relayTransport in setOf("tcp", "quic", "unknown"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = RemoteCredentials(context)
        val profile = requireNotNull(credentials.load()) { "Import remote credentials before this test" }
        val settings = context.getSharedPreferences("board_connection", 0)
        val password = context.getSharedPreferences("board_credentials", 0).getString("password", null).orEmpty()
        assumeTrue("Requires saved board credentials", password.isNotEmpty())
        val config = BoardConnection(profile.host, profile.controlPort,
            settings.getString("username", "topeet")!!, password, credentials.createTlsConfig())
        val client = BoardSshClient()
        var drive: BoardDriveChannel? = null
        var follow: BoardFollowChannel? = null
        var followStarted = false
        var followTerminal = false
        val manualRtt = mutableListOf<Long>()
        val followRtt = mutableListOf<Long>()
        var stage = "connect"
        val report = JSONObject().put("schemaVersion", 1).put("transport", "mtls_frp_${relayTransport}_ssh")
            .put("host", profile.host).put("dryRun", true).put("execute", false)
        try {
            val connectStarted = System.nanoTime()
            client.connect(config)
            report.put("connectMs", millisSince(connectStarted))
            assertTrue(client.isConnected)
            val status = client.execute("printf 'relay-ok'", kind = "board_status")
            assertEquals(0, status.exitCode)
            assertEquals("relay-ok", status.stdout)
            assertTrue("Never start a simulated bridge on an occupied board",
                client.readFollowAvailability().controlAvailable)

            if (includeManual) {
                stage = "manual_ready"
                val readyStarted = System.nanoTime()
                val manual = client.openDriveChannel(dryRun = true).also { drive = it }
                report.put("manualReadyMs", millisSince(readyStarted))
                assertTrue(manual.ready.dryRun)
                assertEquals(600, manual.ready.watchdogMs)
                stage = "manual_ack"
                repeat(15) { index ->
                    val speed = if (index % 3 == 0) 0 else 10
                    val started = System.nanoTime()
                    val ack = manual.exchange(speed, speed)
                    manualRtt += millisSince(started)
                    assertEquals(speed, ack.left)
                    assertEquals(speed, ack.right)
                    delay(100)
                }
                manual.stopAndClose()
                drive = null
                waitForIdle(client)
            }

            stage = "follow_ready"
            val followReadyStarted = System.nanoTime()
            val automatic = client.openFollowChannel(FollowCamera.ASTRA_PRO,
                execute = false, dryRun = true).also { follow = it }
            report.put("followReadyMs", millisSince(followReadyStarted))
            assertFalse(automatic.ready.execute)
            val runId = automatic.ready.runId
            assertTrue(runId.matches(Regex("[a-f0-9]{32}")))
            report.put("runId", runId)
            followStarted = true
            stage = "follow_start"
            var reply = automatic.start()
            assertFalse(reply.status.execute)
            stage = "follow_ack"
            repeat(5) {
                assertFalse("Dry-run worker exited before stop request", reply.terminal)
                delay(500)
                val started = System.nanoTime()
                reply = automatic.heartbeat()
                followRtt += millisSince(started)
                assertFalse(reply.status.execute)
            }
            stage = "follow_stop"
            reply = automatic.requestStop()
            withTimeout(12_000) {
                while (!reply.terminal) {
                    delay(500)
                    reply = automatic.heartbeat()
                }
            }
            followTerminal = true
            assertTrue(reply.status.processExited)
            assertEquals("STOPPED", reply.status.runState)
            automatic.close("remote_test_terminal")
            follow = null
            waitForIdle(client)

            stage = "board_log_correlation"
            val boardFinished = withTimeout(15_000) {
                var found: JSONObject? = null
                while (found == null) {
                    val logs = client.execute("cat /home/topeet/Desktop/rk_car_runtime_module/" +
                        "app_diagnostic_logs/*_${runId}_*.jsonl")
                    assertEquals(0, logs.exitCode)
                    assertFalse(logs.truncated)
                    val events = logs.stdout.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                    assertTrue(events.all { it.optString("runId") == runId })
                    found = events.lastOrNull { it.optString("event") == "session_finished" }
                    if (found == null) delay(300)
                }
                found
            }
            assertEquals("app_stop_requested", boardFinished.getJSONObject("firstStop").getString("source"))
            assertEquals(0, boardFinished.getInt("exitCode"))
            report.put("boardExitCode", boardFinished.getInt("exitCode"))
            report.put("boardStopSource", "app_stop_requested")
            report.put("manualAck", if (manualRtt.isNotEmpty()) latencySummary(manualRtt) else JSONObject.NULL)
            report.put("followAck", latencySummary(followRtt))
            report.put("manualWatchdogMs", 600).put("followWatchdogMs", 2000)
            report.put("versionName", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            report.put("passed", true)
            Diagnostics.event("test.remote_relay_completed", automatic.diagnosticContext,
                fields = mapOf("dryRun" to true, "manualP95Ms" to manualRtt.takeIf { it.isNotEmpty() }?.let { percentile(it, .95) },
                    "followP95Ms" to percentile(followRtt, .95)), critical = true)
            File(context.getExternalFilesDir(null), if (includeManual) "remote-relay-validation.json"
                else "remote-relay-follow-validation.json").writeText(report.toString(2))
        } catch (failure: Throwable) {
            report.put("passed", false).put("failedStage", stage)
                .put("failureType", failure.javaClass.simpleName)
                .put("manualAck", if (manualRtt.isNotEmpty()) latencySummary(manualRtt) else JSONObject.NULL)
                .put("followAck", if (followRtt.isNotEmpty()) latencySummary(followRtt) else JSONObject.NULL)
            File(context.getExternalFilesDir(null), if (includeManual) "remote-relay-validation.json"
                else "remote-relay-follow-validation.json").writeText(report.toString(2))
            throw failure
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(15_000) {
                    runCatching { drive?.takeIf { it.isOpen }?.stopAndClose() }
                    if (followStarted && !followTerminal && follow?.isOpen == true) {
                        runCatching {
                            var reply = follow!!.requestStop()
                            while (!reply.terminal) { delay(500); reply = follow!!.heartbeat() }
                        }
                    }
                }
                drive?.close("remote_test_cleanup")
                follow?.close("remote_test_cleanup")
                client.disconnect("remote_test_complete")
            }
        }
    }

    private suspend fun waitForIdle(client: BoardSshClient) = withTimeout(15_000) {
        while (!client.readFollowAvailability().controlAvailable) delay(300)
    }

    private fun millisSince(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    private fun percentile(values: List<Long>, quantile: Double): Long =
        values.sorted()[(ceil(values.size * quantile).toInt() - 1).coerceIn(values.indices)]
    private fun latencySummary(values: List<Long>): JSONObject = JSONObject()
        .put("count", values.size).put("p50Ms", percentile(values, .50))
        .put("p95Ms", percentile(values, .95)).put("p99Ms", percentile(values, .99))
        .put("maxMs", values.max()).put("samplesMs", JSONArray(values))
}
