package com.example.followingcar

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in authenticated status probe. Never bonds, creates a hotspot, sends
 * credentials, changes Wi-Fi, or starts any SSH/control session. */
@RunWith(AndroidJUnit4::class)
class BluetoothStatusIntegrationTest {
    @Test fun authenticatedStatusWithoutChangingNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runBluetoothStatus") == "true")
        assumeTrue(args.getString("confirmedControlIdle") == "true")
        val context = instrumentation.targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter != null && adapter.isEnabled)
        val device = adapter.getRemoteDevice(DEFAULT_PAIRING_BOARD_ADDRESS)
        assertEquals("Existing authenticated bond required", BluetoothDevice.BOND_BONDED, device.bondState)
        adapter.cancelDiscovery()
        val link = device.createRfcommSocketToServiceRecord(UUID.fromString(PAIRING_SERVICE_UUID))
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val expiry = watchdog.schedule({ runCatching { link.close() } }, 40, TimeUnit.SECONDS)
        val start = SystemClock.elapsedRealtime()
        var phase = "connect"
        try {
            link.connect()
            val connected = SystemClock.elapsedRealtime()
            phase = "status"
            val requestId = UUID.randomUUID().toString()
            val request = JSONObject().put("v", 1).put("requestId", requestId).put("cmd", "status")
            link.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
            link.outputStream.flush()
            val reply = JSONObject(readPairingFrame(link.inputStream))
            assertEquals("status", reply.optString("type"))
            assertEquals(requestId, reply.optString("requestId"))
            assertTrue(reply.opt("controlAvailable") is Boolean)
            assertEquals("2.4GHz", reply.optString("band"))
            instrumentation.sendStatus(0, Bundle().apply { putString("bluetoothStatus", JSONObject()
                .put("connected", true).put("connectMs", connected - start)
                .put("statusMs", SystemClock.elapsedRealtime() - connected)
                .put("controlAvailable", reply.getBoolean("controlAvailable"))
                .put("operationInProgress", reply.optBoolean("operationInProgress"))
                .put("networkChanged", false).toString()) })
        } catch (failure: Exception) {
            // This probe handles status only; do not print frames or arbitrary exception messages.
            throw AssertionError("Bluetooth status failed: phase=$phase type=${failure.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - start}")
        } finally {
            expiry.cancel(false)
            runCatching { link.close() }
            watchdog.shutdownNow()
        }
    }
}
