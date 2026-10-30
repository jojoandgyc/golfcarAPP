package com.example.followingcar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit real-device status probe. Never bonds, provisions, starts a hotspot or opens SSH.
 * The board may reconcile its own historical journal; no network/control mutation is requested. */
@SuppressLint("MissingPermission")
@RunWith(AndroidJUnit4::class)
class BluetoothRecoveryIntegrationTest {
    @Test fun authenticatedHistoricalRequestIsDefinitivelyResolvedWithoutChangingNetwork() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires runBluetoothRecovery=true", arguments.getString("runBluetoothRecovery") == "true")
        assumeTrue("Requires confirmedControlIdle=true", arguments.getString("confirmedControlIdle") == "true")
        assumeTrue("Requires a real phone", Build.HARDWARE !in setOf("ranchu", "goldfish"))
        val queryId = arguments.getString("recoveryRequestId").orEmpty()
        assertTrue("A canonical historical request UUID is required",
            runCatching { UUID.fromString(queryId).toString() == queryId }.getOrDefault(false))
        val expectedCode = arguments.getString("expectedRecoveryCode") ?: "network_superseded"
        assertTrue("Unsupported expected recovery code", expectedCode in EXPECTED_CODES)

        val context = instrumentation.targetContext
        assertTrue("Bluetooth connect permission is required",
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        assertTrue("Bluetooth scan permission is required",
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        assertTrue("Bluetooth must already be enabled", adapter != null && adapter.isEnabled)
        val device = requireNotNull(adapter).getRemoteDevice(DEFAULT_PAIRING_BOARD_ADDRESS)
        assertTrue("An existing authenticated bond is required", device.bondState == BluetoothDevice.BOND_BONDED)
        adapter.cancelDiscovery()

        val started = SystemClock.elapsedRealtime()
        val workers = Executors.newCachedThreadPool { task ->
            Thread(task, "pairing-recovery-readonly-test").apply { isDaemon = true }
        }
        var completed = false
        var failureCode = "recovery_transport_failed"
        try {
            val response = withTimeout(130_000) {
                withPairingReadRetry {
                    queryHistory(device, queryId, workers)
                }
            }
            failureCode = "recovery_status_invalid"
            assertTrue("Status type is invalid", response.opt("type") == "status")
            failureCode = "recovery_capability_invalid"
            assertTrue("Board capability must be a supported typed value",
                response.opt("recoveryState") is String && response.opt("recoveryState") in RECOVERY_STATES)
            failureCode = "recovery_status_invalid"
            assertTrue("Control availability must be a boolean", response.opt("controlAvailable") is Boolean)
            assertTrue("Operation availability must be a boolean", response.opt("operationInProgress") is Boolean)
            failureCode = "recovery_board_not_idle"
            assertTrue("Board is no longer idle", response.opt("controlAvailable") == true &&
                response.opt("operationInProgress") == false)
            assertTrue("Board band capability is invalid", response.opt("band") == "2.4GHz")
            failureCode = "recovery_result_missing"
            val result = response.optJSONObject("lastResult")
            assertTrue("Historical result is missing", result != null)
            requireNotNull(result)
            failureCode = "recovery_identity_invalid"
            assertTrue("Historical response identity is invalid", result.opt("v") == 1 &&
                result.opt("type") == "result" && result.opt("requestId") == queryId)
            failureCode = "recovery_not_definitive"
            assertTrue("Historical request is not a definitive negative outcome", result.opt("ok") == false &&
                result.opt("definitive") == true && result.opt("networkChanged") == false)
            // Boolean assertions deliberately avoid dumping untrusted response fields on failure.
            failureCode = "recovery_code_mismatch"
            assertTrue("Historical recovery code did not match", result.opt("error") == expectedCode)
            completed = true
        } catch (_: Throwable) {
            throw AssertionError("Read-only Bluetooth recovery verification failed: $failureCode")
        } finally {
            workers.shutdown()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("bluetoothRecovery", JSONObject()
                    .put("code", if (completed) expectedCode else failureCode)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started).toString())
            })
        }
    }

    private suspend fun queryHistory(device: BluetoothDevice, queryId: String,
        workers: ExecutorService): JSONObject {
        // A retry owns a fresh authenticated socket, never a partially consumed old stream.
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(PAIRING_SERVICE_UUID))
        try {
            val response = withTimeout(40_000) {
                suspendCancellableCoroutine<String> { continuation ->
                    continuation.invokeOnCancellation {
                        workers.execute { runCatching { socket.close() } }
                    }
                    workers.execute {
                        try {
                            socket.connect()
                            val requestId = UUID.randomUUID().toString()
                            val request = JSONObject().put("v", 1).put("requestId", requestId)
                                .put("cmd", "status").put("queryRequestId", queryId)
                            socket.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                            socket.outputStream.flush()
                            val text = readPairingFrame(socket.inputStream)
                            val parsed = try { JSONObject(text) }
                                catch (_: Exception) { throw PairingFailure("protocol_error") }
                            if (parsed.opt("v") != 1 || parsed.opt("requestId") != requestId)
                                throw PairingFailure("protocol_error")
                            if (continuation.isActive) continuation.resume(text)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (failure is PairingFailure) failure else PairingFailure(
                                    if (failure is SecurityException) "permission_denied" else "bluetooth_failed"))
                        }
                    }
                }
            }
            return JSONObject(response)
        } finally {
            // Closing on another worker also releases blocked connect/read/write on cancellation.
            workers.execute { runCatching { socket.close() } }
        }
    }

    private companion object {
        val EXPECTED_CODES = setOf("network_superseded", "network_restored", "request_expired")
        val RECOVERY_STATES = setOf("clear", "pending", "unavailable")
    }
}
