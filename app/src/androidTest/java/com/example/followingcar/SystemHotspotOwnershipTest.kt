package com.example.followingcar

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Existing AP only. No provision, SSH, settings launch, hotspot toggle or motor operation. */
@RunWith(AndroidJUnit4::class)
class SystemHotspotOwnershipTest {
    @Test fun closingAndCancellingObserversLeaveTheUsersExistingSystemHotspotRunning() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runSystemHotspotOwnership") == "true")
        assumeTrue(args.getString("confirmedControlIdle") == "true")
        val context = instrumentation.targetContext
        val before = currentSystemHotspotObservations(context)
        assumeTrue("Enable a system hotspot before this read-only lifecycle check", before.isNotEmpty())
        val started = System.nanoTime()
        val directory = File(context.cacheDir, "system-hotspot-ownership-${UUID.randomUUID()}")
        check(directory.mkdir())
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val unexpectedOperations = AtomicInteger()
        val report = JSONObject().put("kind", "system_hotspot_observer_lifecycle")
            .put("beforeInterfaces", systemHotspotTestJson(before))
            .put("provisionRequests", 0).put("sshConnections", 0).put("realMotorCommandsSent", false)
        try {
            repeat(3) {
                val controller = withContext(Dispatchers.Main) {
                    BluetoothHotspotController(isolated, canMutate = { false },
                        onHotspotLost = {}, onPaired = { unexpectedOperations.incrementAndGet() },
                        onHotspotLeaseChanged = { busy -> if (busy) unexpectedOperations.incrementAndGet() })
                }
                try {
                    withTimeout(10_000) { controller.state.first { !it.recoveryLoading } }
                    withContext(Dispatchers.Main) {
                        controller.refresh()
                        controller.cancel()
                    }
                    assertFalse(controller.state.value.busy)
                } finally { withContext(Dispatchers.Main) { controller.close() } }
                delay(150)
                assertTrue("Observer disposal changed an externally owned hotspot",
                    sameSystemHotspotObservations(before, currentSystemHotspotObservations(context)))
            }
            assertEquals("Observer lifecycle must not start a pairing operation", 0, unexpectedOperations.get())
            assertTrue("Observer lifecycle unexpectedly started control/video",
                systemHotspotControlStartEvents(started / 1_000_000).isEmpty())
            assertTrue("Observer lifecycle must not create a pairing journal",
                PairingRecoveryStore(isolated).load() is PairingRecoveryLoad.Empty)
            report.put("success", true).put("observerCycles", 3)
        } catch (failure: Throwable) {
            report.put("success", false).put("failureType", failure.javaClass.simpleName)
            throw AssertionError("System hotspot ownership check failed: type=${failure.javaClass.simpleName}")
        } finally {
            report.put("afterInterfaces", systemHotspotTestJson(currentSystemHotspotObservations(context)))
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            File(context.getExternalFilesDir(null), "system-hotspot-ownership-validation.json")
                .writeText(report.toString(2))
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
