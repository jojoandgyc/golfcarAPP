package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in saved mTLS identity + pinned SSH, with an inspected idle board and zero-only dry-run.
 * No Activity/ViewModel, pairing request, Wi-Fi change, video-service operation or real motor write.
 */
@RunWith(AndroidJUnit4::class)
class RemoteManualAvailabilityIntegrationTest {
    @Test fun authenticatedRemoteRouteAllowsZeroOnlyDryRunDespiteAnOldLocalPairingJournal() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires saved-credential remote manual dry-run opt-in",
            args.getString("useSavedBoardCredentials") == "true" &&
                args.getString("remoteManualDryRun") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject().put("schemaVersion", 1).put("kind", "remote_manual_availability")
            .put("versionName", version.versionName).put("versionCode", version.longVersionCode)
            .put("transport", "mtls_pinned_ssh").put("dryRun", true)
            .put("realMotorCommandsSent", false).put("videoServiceOperations", 0)
            .put("wifiOperations", 0).put("nonzeroTargetsSent", 0)
        val started = System.nanoTime()
        val ackTimes = mutableListOf<Long>()
        val client = BoardSshClient()
        var drive: BoardDriveChannel? = null
        var confirmedDryRun = false
        var stage = "saved_credentials"
        try {
            val credentials = RemoteCredentials(context)
            val profile = credentials.load()
            assumeTrue("Import an existing remote identity before this test", profile != null)
            val savedProfile = requireNotNull(profile)
            val settings = context.getSharedPreferences("board_connection", 0)
            val password = context.getSharedPreferences("board_credentials", 0)
                .getString("password", null).orEmpty()
            val username = settings.getString("username", "topeet").orEmpty()
            assumeTrue("Requires saved board credentials", password.isNotEmpty() && username.isNotBlank())
            val config = BoardConnection(savedProfile.host, savedProfile.controlPort, username, password,
                credentials.createTlsConfig())

            stage = "remote_capability_policy"
            val remote = BoardUiState(connected = true, remoteMode = true, pairingUnconfirmed = true)
            report.put("remoteCanEnableDrive", remote.canEnableDrive)
                .put("remoteCanInspectFollow", remote.canInspectFollow)
                .put("localPairingStillUnconfirmed", remote.pairingUnconfirmed)
            assertTrue("A stale local pairing journal must not lock an authenticated remote control route", remote.canEnableDrive)
            assertTrue("The remote route must still permit the ownership inspection", remote.canInspectFollow)
            assertFalse("An actively running pairing operation must remain a control lock",
                remote.copy(pairingBusy = true).canEnableDrive)
            assertFalse("The same unconfirmed journal must still protect the local route",
                remote.copy(remoteMode = false).canEnableDrive)

            stage = "remote_connect"
            val connectionStarted = System.nanoTime()
            withTimeout(25_000) { client.connect(config) }
            assertTrue(client.isConnected)
            report.put("connectMs", elapsedMillis(connectionStarted)).put("pinnedSshAuthenticated", true)

            stage = "inspect_control_before_open"
            val inspectStarted = System.nanoTime()
            val availability = withTimeout(15_000) { client.readFollowAvailability() }
            report.put("initialInspectionMs", elapsedMillis(inspectStarted))
                .put("controlAvailableBefore", availability.controlAvailable)
            // Do not try to stop or replace another owner, including a real manual controller.
            assumeTrue("Board control is occupied; no simulated bridge was started", availability.controlAvailable)

            stage = "dry_run_ready"
            val readyStarted = System.nanoTime()
            val channel = withTimeout(20_000) { client.openDriveChannel(dryRun = true) }.also { drive = it }
            assertTrue("The board must explicitly acknowledge simulation mode", channel.ready.dryRun)
            confirmedDryRun = true
            assertEquals("simulated", requireNotNull(channel.ready.feedback).source)
            report.put("readyMs", elapsedMillis(readyStarted)).put("readyDryRun", true)
                .put("safetyVersion", channel.ready.safetyVersion).put("watchdogMs", channel.ready.watchdogMs)
                .put("networkGraceMs", channel.ready.networkGraceMs)
                .put("runId", channel.diagnosticContext.runId)

            stage = "zero_only_ack"
            repeat(5) {
                val sent = System.nanoTime()
                val ack = withTimeout(10_000) { channel.exchange(0, 0) }
                ackTimes += elapsedMillis(sent)
                assertEquals(0, ack.left)
                assertEquals(0, ack.right)
                assertTrue(ack.targetStopped)
                assertTrue(ack.stopped)
                assertEquals("simulated", requireNotNull(ack.feedback).source)
                assertTrue(channel.isOpen)
                delay(100)
            }

            stage = "terminal_stop_ack"
            val stopStarted = System.nanoTime()
            val terminal = withTimeout(10_000) { channel.stopAndClose() }
            assertEquals(0, terminal.left)
            assertEquals(0, terminal.right)
            assertTrue(terminal.targetStopped)
            assertTrue(terminal.stopped)
            assertEquals("simulated", requireNotNull(terminal.feedback).source)
            assertFalse(channel.isOpen)
            assertTrue("Normal dry-run close must preserve its SSH transport", client.isConnected)
            report.put("terminalAckReceived", true).put("terminalStopMs", elapsedMillis(stopStarted))
            drive = null

            stage = "inspect_control_after_close"
            val idleStarted = System.nanoTime()
            withTimeout(15_000) {
                while (!client.readFollowAvailability().controlAvailable) delay(300)
            }
            report.put("controlAvailableAfter", true).put("idleConfirmedMs", elapsedMillis(idleStarted))
                .put("passed", true).put("skipped", false)
        } catch (skipped: AssumptionViolatedException) {
            report.put("passed", false).put("skipped", true).put("skippedStage", stage)
                .put("skipReason", if (stage == "inspect_control_before_open") "control_occupied" else "saved_credentials_unavailable")
            throw skipped
        } catch (failure: Throwable) {
            report.put("passed", false).put("skipped", false).put("failedStage", stage)
                .put("failureType", failure.javaClass.simpleName)
            throw AssertionError("Remote manual dry-run failed: stage=$stage type=${failure.javaClass.simpleName}")
        } finally {
            withContext(NonCancellable) {
                try {
                    val owned = drive
                    if (confirmedDryRun && owned?.isOpen == true) {
                        val closedNormally = withTimeoutOrNull(10_000) {
                            runCatching { owned.stopAndClose(); true }.getOrDefault(false)
                        } == true
                        report.put("cleanupTerminalAckReceived", closedNormally)
                    }
                } finally {
                    drive?.close("remote_manual_availability_test_cleanup")
                    client.disconnect("remote_manual_availability_test_finished")
                }
            }
            report.put("elapsedMs", elapsedMillis(started))
                .put("ackCount", ackTimes.size).put("ackSamplesMs", JSONArray(ackTimes))
            if (ackTimes.isNotEmpty()) report.put("ackP50Ms", percentile(ackTimes, .50))
                .put("ackP95Ms", percentile(ackTimes, .95)).put("ackMaxMs", ackTimes.max())
            File(context.filesDir, "remote-manual-availability-test.json").writeText(report.toString(2))
        }
    }

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun percentile(values: List<Long>, quantile: Double): Long =
        values.sorted()[(ceil(values.size * quantile).toInt() - 1).coerceIn(values.indices)]
}
