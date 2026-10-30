package com.example.followingcar

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production UI recovery of a real historical request. Only checkPairing is clicked.
 * Never submits provision, starts a hotspot, opens SSH or invokes any motor command. */
@RunWith(AndroidJUnit4::class)
class BluetoothRecoveryUiTest {
    // The activity must not exist until the recovery record has been durably prepared.
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun persistedHistoricalRequestRecoversThroughProductionButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires runBluetoothRecoveryUi=true", args.getString("runBluetoothRecoveryUi") == "true")
        assumeTrue("Requires confirmedControlIdle=true", args.getString("confirmedControlIdle") == "true")
        assumeTrue("Requires a real phone", Build.HARDWARE !in setOf("ranchu", "goldfish"))
        val requestId = args.getString("recoveryRequestId").orEmpty()
        assertTrue("A canonical real historical request UUID is required",
            runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false))
        val context = instrumentation.targetContext
        // An unresolved operation must not force a mode change; prepare this prerequisite before opting in.
        assumeTrue("Select local connection mode before running this read-only test",
            !context.getSharedPreferences("board_connection", 0).getBoolean("remote_mode", false))
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)) {
            assertTrue("Nearby-device permissions must already be granted",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        assertTrue("Bluetooth must already be enabled", adapter != null && adapter.isEnabled)
        assertTrue("The board must already be authenticated",
            adapter?.getRemoteDevice(DEFAULT_PAIRING_BOARD_ADDRESS)?.bondState == BluetoothDevice.BOND_BONDED)
        val store = PairingRecoveryStore(context)
        assumeTrue("An existing recovery record must not be overwritten",
            runBlocking { store.load() } == PairingRecoveryLoad.Empty)

        val started = SystemClock.elapsedRealtime()
        var scenario: ActivityScenario<MainActivity>? = null
        var completed = false
        var failureCode = "recovery_ui_prepare_failed"
        try {
            assertTrue("Could not durably prepare the historical recovery record", runBlocking {
                store.save(PairingRecoveryRecord(requestId, DEFAULT_PAIRING_BOARD_ADDRESS,
                    PairingRecoveryPhase.UNKNOWN, firstFailureCode = "outcome_unknown_after_restart"))
            })
            failureCode = "recovery_ui_launch_failed"
            lateinit var model: BoardViewModel
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[BoardViewModel::class.java] }
            failureCode = "recovery_ui_restore_failed"
            compose.waitUntil(15_000) {
                !model.state.value.remoteProfileLoading && !model.pairing.state.value.recoveryLoading &&
                    model.pairing.state.value.confirmationRequired
            }
            assertFalse(model.pairing.state.value.hotspotActive)
            assertNull(model.pairing.state.value.boardIp)
            assertNoControlOrSsh(model)
            assertTrue("Recovery should remain available", model.state.value.canManagePairing)
            assertFalse("Existing Wi-Fi Direct group must remain untouched", model.wifiDirect.state.value.groupExists)
            assertFalse("Existing wireless uncertainty must remain untouched", model.wifiDirect.state.value.confirmationRequired)
            val originalHost = model.state.value.host
            val originalPort = model.state.value.port

            compose.onNodeWithTag("pageConnection").performClick()
            // This is the production recovery button, not a direct call into controller helpers.
            failureCode = "recovery_ui_query_failed"
            compose.onNodeWithTag("checkPairing").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(120_000) {
                !model.pairing.state.value.busy && !model.pairing.state.value.confirmationRequired
            }
            failureCode = "recovery_ui_not_released"
            assertNull("Recovery should finish without an operation error", model.pairing.state.value.error)
            assertFalse(model.pairing.state.value.hotspotActive)
            assertNull(model.pairing.state.value.boardIp)
            assertNoControlOrSsh(model)
            assertTrue("Recovery must not replace saved connection coordinates",
                model.state.value.host == originalHost && model.state.value.port == originalPort)
            assertTrue("Only a confirmed historical outcome may clear the journal",
                runBlocking { store.load() } == PairingRecoveryLoad.Empty)
            compose.onNodeWithTag("checkPairing").assertDoesNotExist()
            // Only assert the new pairing action is available. Never click it in this test.
            compose.onNodeWithTag("startPairing").performScrollTo().assertIsEnabled()
            completed = true
        } catch (_: Throwable) {
            throw AssertionError("Read-only Bluetooth UI recovery failed: $failureCode")
        } finally {
            // Closing the observer cancels pending reads. Never clear an unconfirmed record on failure.
            scenario?.close()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("bluetoothRecoveryUi", JSONObject()
                    .put("code", if (completed) "recovery_ui_confirmed" else failureCode)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started).toString())
            })
        }
    }

    private fun assertNoControlOrSsh(model: BoardViewModel) {
        val state = model.state.value
        assertFalse("Recovery must not open SSH", state.connected)
        assertFalse("Recovery must not initiate SSH or another operation", state.busy)
        assertFalse("Recovery must not start manual control", state.manualActive)
        assertFalse("Recovery must not start follow", state.followActive || state.followStopping)
        assertFalse("Recovery must not start video operations", state.videoServiceBusy)
        assertFalse("Recovery must not start a motor action", state.motorActionCancellable)
    }
}
