package com.example.followingcar

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

/** Explicit opt-in: provisions the board onto the user's already running system hotspot.
 * The operator must first confirm idle control. Never opens a control/camera/motor bridge.
 * Never creates, stops, reconfigures or owns a phone hotspot. Saved credentials are not exported.
 * Discovery is a separate fresh instance and never consults the pairing result for candidates.
 */
@RunWith(AndroidJUnit4::class)
class PairingHotspotIntegrationTest {
    @Test fun productionPairingAndIndependentHotspotDiscoveryReachTheSamePinnedBoard() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        // The old LocalOnlyHotspot opt-in deliberately no longer authorizes this operation.
        assumeTrue(args.getString("runSystemHotspotPairing") == "true")
        assumeTrue(args.getString("confirmedControlIdle") == "true")
        val context = instrumentation.targetContext
        assumeTrue("Save the user's system hotspot profile first",
            SystemHotspotCredentialStore(context).load() != null)
        val before = currentSystemHotspotObservations(context)
        assumeTrue("The user must enable their system hotspot first", before.isNotEmpty())
        val started = System.nanoTime()
        var phase = "launch"
        var terminalPairingStage: PairingStage? = null
        var terminalPairingCode: String? = null
        val report = JSONObject().put("kind", "system_hotspot_pairing_and_independent_discovery")
            .put("appManagedHotspot", false).put("beforeInterfaces", systemHotspotTestJson(before))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                lateinit var model: BoardViewModel
                activity.onActivity { model = (it.application as FollowingCarApplication).boardViewModel }
                withTimeout(20_000) {
                    model.state.first { !it.remoteProfileLoading }
                    model.pairing.state.first { !it.recoveryLoading }
                    model.systemHotspotProfile.first { !it.loading && !it.saving }
                }
                assertControlIdle(model.state.value)
                assumeTrue("Start with an idle disconnected shared owner",
                    !model.state.value.connected && !model.state.value.busy && !model.state.value.videoServiceBusy)
                assertTrue("Saved system hotspot credentials could not be loaded", model.systemHotspotProfile.value.configured)
                val lease = SharedOwnerSshTestLease(model)
                val controlStarted = AtomicBoolean(false)
                val monitor = launch {
                    model.state.collect { if (it.manualActive || it.followActive || it.followStopping) controlStarted.set(true) }
                }
                try {
                    phase = "reconcile_existing_journal"
                    disconnectIdle(model, lease)
                    val oldRecord = PairingRecoveryStore(context).load()
                    assertFalse("Unreadable recovery journal must be repaired before the test", oldRecord is PairingRecoveryLoad.Blocked)
                    if (oldRecord is PairingRecoveryLoad.Pending || model.pairing.state.value.confirmationRequired) {
                        withContext(Dispatchers.Main) { model.pairing.retryStatus() }
                        val recovered = withTimeout(110_000) { model.pairing.state.first { !it.busy } }
                        assertFalse("Read-only recovery did not establish the old request outcome", recovered.confirmationRequired)
                        report.put("existingJournalReconciled", true)
                        // Successful read-only recovery can schedule automatic SSH on the main queue.
                        delay(300)
                        disconnectIdle(model, lease)
                    }
                    if (model.pairing.state.value.hotspotActive || PairingRecoveryStore(context).load() is PairingRecoveryLoad.Pending) {
                        // In the system-hotspot implementation this forgets a confirmed App record only.
                        // It must never call WifiManager or disable the user's hotspot.
                        withContext(Dispatchers.Main) { model.pairing.stopHotspot() }
                        withTimeout(15_000) { model.pairing.state.first { !it.busy } }
                    }
                    assertTrue("Old journal remains unresolved", PairingRecoveryStore(context).load() is PairingRecoveryLoad.Empty)
                    assertTrue("Reconciliation changed the user's AP", sameSystemHotspotObservations(before,
                        currentSystemHotspotObservations(context)))
                    assertTrue(model.state.value.canManagePairing)
                    assertFalse(model.pairing.state.value.confirmationRequired)
                    phase = "pairing"
                    val pairingStartedMs = System.nanoTime() / 1_000_000
                    withContext(Dispatchers.Main) { model.startPairing(DEFAULT_PAIRING_BOARD_ADDRESS) }
                    val paired = withTimeout(110_000) {
                        // A definitive rollback ends in IDLE with no UI error. It is a completed
                        // unsuccessful attempt, not an operation that should wait for 110 seconds.
                        model.pairing.state.first { !it.busy }
                    }
                    terminalPairingStage = paired.stage
                    report.put("terminalPairingStage", paired.stage.name)
                    if (paired.stage != PairingStage.PAIRED) {
                        val fixedLocalCodes = setOf("cancelled", "hotspot_stopped", "state_changed",
                            "permission_denied", "bluetooth_disabled", "bluetooth_failed", "bluetooth_timeout",
                            "bluetooth_eof", "protocol_error", "address_unverified", "recovery_storage_failed", "board_busy")
                        val codes = Diagnostics.snapshot().events.mapNotNull { line ->
                            val event = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                            if (event.optLong("monotonicMs", -1) < pairingStartedMs || event.optString("event") !in
                                setOf("pairing.network_released", "pairing.failed", "pairing.recovery_trigger"))
                                return@mapNotNull null
                            val code = event.optJSONObject("fields")?.opt("code") as? String ?: return@mapNotNull null
                            if (code in fixedLocalCodes) code else pairingBoardErrorCode(code)
                        }.distinct()
                        terminalPairingCode = codes.lastOrNull() ?: "not_paired"
                        report.put("terminalPairingCode", terminalPairingCode)
                            .put("pairingFailureCodes", JSONArray(codes))
                    }
                    assertEquals("Production pairing ended: stage=${paired.stage.name} code=${terminalPairingCode ?: "none"}",
                        PairingStage.PAIRED, paired.stage)
                    assertTrue(paired.hotspotActive)
                    assertFalse(paired.confirmationRequired)
                    val boardIp = requireNotNull(paired.boardIp)
                    phase = "paired_ssh"
                    withTimeout(35_000) { model.state.first { it.connected && !it.busy } }
                    lease.rememberEstablishedConnection()
                    assertControlIdle(model.state.value)
                    report.put("pairedBoardIp", boardIp).put("pairedSshAuthenticated", true)

                    // No saved peer address or Bluetooth result is supplied to this fresh discovery.
                    phase = "independent_discovery"
                    val discovery = BoardLocalDiscovery(context)
                    val endpoints = withTimeout(10_000) { discovery.discover() }
                    val endpoint = endpoints.firstOrNull {
                        it.source == "hotspot_mdns" && it.host == boardIp && it.localAddress != null
                    }
                    assertNotNull("AP discovery must work without a saved peer address or pairing input", endpoint)
                    val chosen = requireNotNull(endpoint)
                    val username = model.state.value.username
                    val password = model.state.value.password
                    val client = BoardSshClient()
                    phase = "independent_pinned_ssh"
                    try {
                        withTimeout(12_000) {
                            client.connect(BoardConnection(chosen.host, chosen.port, username, password,
                                localNetwork = chosen.network, localAddress = chosen.localAddress))
                        }
                        assertTrue(client.isConnected)
                        report.put("independentlyDiscoveredIp", chosen.host)
                            .put("source", chosen.source).put("sourceAddressBound", true)
                            .put("independentPinnedSshAuthenticated", true)
                    } finally { client.disconnect("hotspot_discovery_integration_finished") }
                    assertFalse("Control became active during the network-only test", controlStarted.get())
                    val startEvents = systemHotspotControlStartEvents(started / 1_000_000)
                    assertTrue("Unexpected control/video start events: $startEvents", startEvents.isEmpty())
                    report.put("realMotorCommandsSent", false).put("cameraStarted", false)
                        .put("controlStartEventCount", startEvents.size)
                } finally {
                    try { lease.closeIfStillOwnedAndIdle() }
                    finally { monitor.cancelAndJoin() }
                }
            }
            // Activity disposal must leave the system AP intact; its shared owner stays alive.
            delay(300)
            val after = currentSystemHotspotObservations(context)
            report.put("afterInterfaces", systemHotspotTestJson(after))
            assertTrue("Closing the App changed the user's system hotspot",
                sameSystemHotspotObservations(before, after))
            report.put("success", true)
        } catch (failure: Throwable) {
            report.put("success", false).put("failurePhase", phase)
                .put("failureType", failure.javaClass.simpleName)
            throw AssertionError("Hotspot integration failed: phase=$phase type=${failure.javaClass.simpleName}" +
                " pairingStage=${terminalPairingStage?.name ?: "unknown"} pairingCode=${terminalPairingCode ?: "none"}")
        } finally {
            report.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            if (!report.has("afterInterfaces")) report.put("afterInterfaces",
                systemHotspotTestJson(currentSystemHotspotObservations(context)))
            File(context.getExternalFilesDir(null), "system-hotspot-pairing-validation.json")
                .writeText(report.toString(2))
        }
    }

    private fun assertControlIdle(state: BoardUiState) {
        assertFalse("End active or unconfirmed control before the test", state.manualActive ||
            state.followBlocksControl || state.driveCleanupUnconfirmed || state.motorActionUnconfirmed)
    }

    private suspend fun disconnectIdle(model: BoardViewModel, lease: SharedOwnerSshTestLease) {
        withTimeout(45_000) { model.state.first { !it.busy && !it.videoServiceBusy } }
        assertControlIdle(model.state.value)
        if (model.state.value.connected) {
            lease.rememberEstablishedConnection()
            lease.closeIfStillOwnedAndIdle()
            withTimeout(45_000) { model.state.first { !it.connected && !it.busy && !it.videoServiceBusy } }
        }
    }
}

/** Interface coordinates only: no SSID, PSK, Bluetooth frames or credentials. */
internal fun systemHotspotTestJson(observations: List<SystemHotspotObservation>) = JSONArray().also { rows ->
    observations.forEach { rows.put(JSONObject().put("interface", it.interfaceName)
        .put("index", it.interfaceIndex).put("address", it.address).put("prefixLength", it.prefixLength)) }
}

internal suspend fun systemHotspotControlStartEvents(sinceElapsedMs: Long): List<String> =
    Diagnostics.snapshot().events.mapNotNull { line ->
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
        if (event.optLong("monotonicMs", -1) < sinceElapsedMs) return@mapNotNull null
        val name = event.optString("event")
        name.takeIf { it in setOf("drive.start_requested", "drive.ready", "follow.start_requested", "follow.ready") ||
            it == "video.change_started" && event.optJSONObject("fields")?.optBoolean("enabled") == true }
    }
