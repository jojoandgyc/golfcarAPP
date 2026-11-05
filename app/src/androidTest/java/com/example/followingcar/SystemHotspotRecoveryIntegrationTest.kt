package com.example.followingcar

import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Second phase of the host-orchestrated restart test. Force-stop only the App before running.
 * Requires an existing system AP and durable request. Never provisions or changes credentials,
 * launches Settings, toggles a hotspot, starts control, or sends a video-service start command.
 */
@RunWith(AndroidJUnit4::class)
class SystemHotspotRecoveryIntegrationTest {
    @Test fun newProcessRecoversTheSameRequestWithoutResendingHotspotCredentials() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runSystemHotspotRecovery") == "true")
        assumeTrue(args.getString("confirmedControlIdle") == "true")
        val context = instrumentation.targetContext
        val store = PairingRecoveryStore(context)
        val saved = store.load()
        assumeTrue("A previous durable pairing request is required", saved is PairingRecoveryLoad.Pending)
        val original = (saved as PairingRecoveryLoad.Pending).record
        val before = currentSystemHotspotObservations(context)
        assumeTrue("The user's system hotspot must already be running", before.isNotEmpty())
        val startedMs = System.nanoTime() / 1_000_000
        var phase = "confirm_new_process"
        var terminalCode: String? = null
        val report = JSONObject().put("kind", "system_hotspot_process_restart_recovery")
            .put("requestIdBefore", original.requestId).put("journalPhaseBefore", original.phase.name)
            .put("beforeInterfaces", systemHotspotTestJson(before)).put("pid", Process.myPid())
        try {
            val initialSnapshot = Diagnostics.snapshot()
            assertTrue("Diagnostics must be available to audit recovery", initialSnapshot.status.available)
            val initialEvents = initialSnapshot.events.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            val processEvent = initialEvents.lastOrNull {
                it.optString("event") == "app.process_started" && it.optInt("pid", -1) == Process.myPid()
            }
            val currentInstance = processEvent?.optString("instanceId").orEmpty()
            assertTrue("Current App process identity was not recorded", canonicalUuid(currentInstance))
            val suppliedPrevious = args.getString("previousAppInstanceId")
            if (suppliedPrevious != null) assertTrue("Previous process identity must be a UUID", canonicalUuid(suppliedPrevious))
            val previousInstance = suppliedPrevious ?: initialEvents.lastOrNull {
                it.optString("event").startsWith("pairing.") &&
                    it.optJSONObject("fields")?.optString("requestId") == original.requestId &&
                    it.optString("instanceId") != currentInstance
            }?.optString("instanceId")
            assertTrue("Previous process evidence is unavailable; supply previousAppInstanceId from phase one",
                previousInstance != null && canonicalUuid(previousInstance))
            assertNotEquals("Activity recreation is not a process restart", previousInstance, currentInstance)
            report.put("previousAppInstanceId", previousInstance).put("appInstanceId", currentInstance)
                .put("newProcessConfirmed", true)

            phase = "load_recovery_state"
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                lateinit var model: BoardViewModel
                activity.onActivity { model = ViewModelProvider(it)[BoardViewModel::class.java] }
                withTimeout(20_000) {
                    model.state.first { !it.remoteProfileLoading && !it.busy && !it.videoServiceBusy }
                    model.pairing.state.first { !it.recoveryLoading }
                }
                assertFalse("Recovery requires idle, confirmed control", model.state.value.manualActive ||
                    model.state.value.followBlocksControl || model.state.value.driveCleanupUnconfirmed ||
                    model.state.value.motorActionUnconfirmed)
                assertTrue("Disconnect existing SSH before this recovery-only test", model.state.value.canManagePairing)
                assertFalse("A new process must not assume its old pairing is already verified", model.pairing.state.value.hotspotActive)
                assertTrue("Durable pairing must require read-only reconciliation after restart",
                    model.pairing.state.value.confirmationRequired)

                val controlStarted = AtomicBoolean(false)
                val credentialDispatchStarted = AtomicBoolean(false)
                val stateMonitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    model.state.collect {
                        if (it.manualActive || it.followActive || it.followStopping) controlStarted.set(true)
                    }
                }
                val pairingMonitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    model.pairing.state.collect {
                        if (it.stage == PairingStage.SENDING_CREDENTIALS) credentialDispatchStarted.set(true)
                    }
                }
                try {
                    phase = "read_only_recovery"
                    withContext(Dispatchers.Main) { model.pairing.retryStatus() }
                    val result = withTimeout(110_000) { model.pairing.state.first { !it.busy } }
                    report.put("terminalPairingStage", result.stage.name)
                    if (result.stage != PairingStage.PAIRED) {
                        val codes = Diagnostics.snapshot().events.mapNotNull { line ->
                            val event = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                            if (event.optLong("monotonicMs", -1) < startedMs || event.optString("event") !in
                                setOf("pairing.network_released", "pairing.failed", "pairing.recovery_trigger"))
                                return@mapNotNull null
                            event.optJSONObject("fields")?.opt("code")?.let(::pairingBoardErrorCode)
                        }.distinct()
                        terminalCode = codes.lastOrNull() ?: "not_paired"
                        report.put("terminalPairingCode", terminalCode).put("failureCodes", JSONArray(codes))
                    }
                    assertEquals("Read-only recovery must confirm the existing request", PairingStage.PAIRED, result.stage)
                    assertTrue(result.hotspotActive)
                    assertFalse(result.confirmationRequired)
                    val boardIp = requireNotNull(result.boardIp)
                    val afterRecovery = store.load()
                    assertTrue("Recovery must preserve the request journal", afterRecovery is PairingRecoveryLoad.Pending)
                    val recoveredRecord = (afterRecovery as PairingRecoveryLoad.Pending).record
                    assertEquals(original.requestId, recoveredRecord.requestId)
                    assertEquals(original.address, recoveredRecord.address)
                    assertEquals(PairingRecoveryPhase.PAIRED, recoveredRecord.phase)
                    report.put("requestIdAfter", recoveredRecord.requestId)
                        .put("journalPhaseAfter", recoveredRecord.phase.name).put("boardIp", boardIp)

                    phase = "automatic_pinned_ssh"
                    withTimeout(40_000) { model.state.first { it.connected && !it.busy } }
                    assertFalse("Recovered AP connection must stay on the local route", model.state.value.remoteMode)
                    assertEquals(boardIp, model.state.value.host)
                    assertFalse(controlStarted.get())
                    assertFalse("Recovery must never dispatch hotspot credentials", credentialDispatchStarted.get())
                    report.put("pinnedSshAuthenticated", true)
                } finally {
                    stateMonitor.cancelAndJoin()
                    pairingMonitor.cancelAndJoin()
                }
            }
            // Closing this Activity/VM still does not own or stop the user's system AP.
            delay(300)
            phase = "audit_recovery"
            val after = currentSystemHotspotObservations(context)
            report.put("afterInterfaces", systemHotspotTestJson(after))
            assertTrue("Recovery/disposal changed the user's system hotspot", sameSystemHotspotObservations(before, after))
            val finalSnapshot = Diagnostics.snapshot()
            assertTrue("Diagnostics became unavailable during recovery", finalSnapshot.status.available)
            val credentialEvents = finalSnapshot.events.mapNotNull { line ->
                val event = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                if (event.optLong("monotonicMs", -1) < startedMs) return@mapNotNull null
                val name = event.optString("event")
                name.takeIf { it.startsWith("pairing.") &&
                    (event.optJSONObject("fields")?.optString("stage") == PairingStage.SENDING_CREDENTIALS.name ||
                        it in setOf("pairing.provision", "pairing.provision_sent", "pairing.provision_requested")) }
            }
            assertTrue("Credential dispatch appeared in the recovery timeline", credentialEvents.isEmpty())
            assertTrue("Recovery unexpectedly started control or video", systemHotspotControlStartEvents(startedMs).isEmpty())
            val finalRecord = store.load()
            assertTrue(finalRecord is PairingRecoveryLoad.Pending)
            assertEquals(original.requestId, (finalRecord as PairingRecoveryLoad.Pending).record.requestId)
            report.put("credentialDispatchEventCount", credentialEvents.size).put("provisionRequestsSent", 0)
                .put("realMotorCommandsSent", false).put("cameraStarted", false).put("success", true)
        } catch (failure: Throwable) {
            report.put("success", false).put("failurePhase", phase).put("failureType", failure.javaClass.simpleName)
            throw AssertionError("System hotspot restart recovery failed: phase=$phase " +
                "type=${failure.javaClass.simpleName} code=${terminalCode ?: "none"}")
        } finally {
            if (!report.has("afterInterfaces")) report.put("afterInterfaces",
                systemHotspotTestJson(currentSystemHotspotObservations(context)))
            report.put("elapsedMs", System.nanoTime() / 1_000_000 - startedMs)
            File(context.getExternalFilesDir(null), "system-hotspot-recovery-validation.json")
                .writeText(report.toString(2))
        }
    }

    private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
