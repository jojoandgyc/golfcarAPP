package com.example.followingcar

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.DiagnosticTestSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real VM/client/channel state machines; every SSH/video/motor response stays in memory. */
@RunWith(AndroidJUnit4::class)
class DriveStartupStateTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private lateinit var application: IsolatedApplication
    private val stores = mutableListOf<ViewModelStore>()
    private val fixtures = mutableListOf<Fixture>()

    @Before fun isolateStorage() {
        val id = UUID.randomUUID().toString()
        directory = File(target.cacheDir, "drive-startup-test-$id").apply { mkdirs() }
        application = IsolatedApplication(target, "drive-startup-test-$id", File(directory, "no-backup"))
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        engine = DiagnosticEngine(DiagnosticFileStore(File(directory, "diagnostics")))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreStorage() = runBlocking {
        try {
            fixtures.forEach { fixture ->
                withContext(Dispatchers.Main) {
                    fixture.vm.disableDrive("test cleanup")
                    fixture.bridge.releaseInitialAckIfPending()
                }
                withTimeout(5_000) { (field(fixture.vm, "driveJob") as? Job)?.join() }
            }
            withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
            fixtures.forEach { it.socket.close() }
            if (::engine.isInitialized) engine.flush()
        } finally {
            engineField.set(Diagnostics, originalEngine)
            if (::engine.isInitialized) engine.close()
            if (::application.isInitialized) application.deleteTestPreferences()
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun readyKeepsControlsLockedUntilInitialZeroRequestIsAcknowledged() = runBlocking {
        val fixture = startFixture()
        val first = withTimeout(3_000) { fixture.bridge.firstRequest.await() }
        assertEquals("stop", first.getString("cmd"))
        assertEquals(0, first.getInt("left"))
        assertEquals(0, first.getInt("right"))
        delay(120) // Observe a genuinely pending ACK, well within the unchanged 900 ms deadline.
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveStarting)
            assertFalse(fixture.vm.state.value.driveEnabled)
            assertFalse(fixture.vm.state.value.driveConfirmedStopped)
            assertFalse(fixture.vm.state.value.canConfigureParking)
            fixture.vm.moveJoystick(0f, 1f)
            assertEquals(0, fixture.vm.state.value.driveLeft)
            assertEquals(0, fixture.vm.state.value.driveRight)
        }
        assertFalse(fixture.updates.any { it.driveEnabled || it.canConfigureParking })
        assertEquals(1, fixture.bridge.requests.size)

        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.state.first { it.driveEnabled } }
        withContext(Dispatchers.Main) {
            assertFalse(fixture.vm.state.value.driveStarting)
            assertTrue(fixture.vm.state.value.driveConfirmedStopped)
            assertTrue(fixture.vm.state.value.canConfigureParking)
            fixture.vm.disableDrive("test stop")
        }
        awaitFinished(fixture)
        assertTrue(fixture.bridge.requests.all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        assertEquals("close", fixture.bridge.requests.last().getString("cmd"))
        assertEquals(listOf(true, false), fixture.videoActions.toList())
    }

    @Test fun userStopDuringInitialAckNeverReenablesControl() = runBlocking {
        stopWhileInitialAckPending(ControlStopSource.USER_STOP)
    }

    @Test fun focusLossDuringStartupAndActiveControlOnlyRecordsTheEvent() = runBlocking {
        val fixture = startFixture(networkGrace = true)
        withTimeout(3_000) { fixture.bridge.firstRequest.await() }
        withContext(Dispatchers.Main) {
            fixture.vm.onControlSurfaceHidden("test dialog", ControlStopSource.WINDOW_FOCUS_LOST)
            fixture.vm.disableDrive("test focus", source = ControlStopSource.WINDOW_FOCUS_LOST)
            assertTrue(fixture.vm.state.value.driveStarting)
            assertNull(field(fixture.vm, "stopRequest"))
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.state.first { it.driveEnabled } }
        withContext(Dispatchers.Main) {
            fixture.vm.onControlSurfaceHidden("test notification", ControlStopSource.WINDOW_FOCUS_LOST)
            assertTrue(fixture.vm.state.value.driveEnabled)
            assertNull(field(fixture.vm, "stopRequest"))
        }
        delay(120)
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertFalse(fixture.bridge.requests.any { it.optString("cmd") in setOf("estop", "close") })
    }

    @Test fun initialAckTimeoutRetainsSshAndLocksControlUntilReadOnlyOwnerInspectionSucceeds() = runBlocking {
        val fixture = startFixture(controlAvailable = false)
        withTimeout(3_000) { fixture.bridge.firstRequest.await() }
        awaitFinished(fixture)
        val state = fixture.vm.state.value
        assertFalse(state.driveEnabled)
        assertFalse(state.driveStarting)
        assertFalse(state.driveConfirmedStopped)
        assertTrue(state.connected)
        assertTrue(state.driveCleanupUnconfirmed)
        assertFalse(state.canEnableDrive || state.canStartFollow || state.canConfigureMotor || state.canExecuteTerminal)
        assertFalse(fixture.updates.any { it.driveEnabled || it.canConfigureParking })
        assertFalse(fixture.socket.isClosed)
        assertEquals(1, fixture.bridge.requests.size)
        assertEquals(listOf(true), fixture.videoActions.toList())
        val failure = engine.snapshot().failures.single { it.event == "drive.ack_timeout" }
        assertEquals(failure, state.driveFirstFailure)
        val events = engine.snapshot().events.map(::JSONObject)
        assertFalse(events.any { it.optString("event") == "drive.initial_stop_acknowledged" })
        assertFalse(events.any { it.optString("event") == "ssh.transport_abort" })
        withTimeout(3_000) { while (fixture.availabilityChecks.get() == 0) delay(10) }
        withContext(Dispatchers.Main) { fixture.vm.enableDrive(dryRun = true) }
        assertEquals(1, fixture.bridge.requests.size)
        fixture.controlAvailable.set(true)
        withTimeout(4_000) { fixture.vm.state.first { !it.driveCleanupUnconfirmed } }
        assertFalse(fixture.vm.state.value.manualActive)
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertEquals(failure, fixture.vm.state.value.driveFirstFailure)
        assertEquals(1, fixture.bridge.requests.size)
    }

    @Test fun graceSessionIntentionalStopsInterruptPendingAckWithoutNetworkFailure() = runBlocking {
        for (source in listOf(ControlStopSource.EMERGENCY_STOP, ControlStopSource.APP_PAUSED,
            ControlStopSource.CONTROL_SURFACE_HIDDEN,
            ControlStopSource.PAGE_CHANGED, ControlStopSource.USER_STOP, ControlStopSource.DISCONNECT)) {
            val fixture = startFixture(networkGrace = true)
            withTimeout(3_000) { fixture.bridge.heldRequest.await() }
            withContext(Dispatchers.Main) {
                fixture.vm.disableDrive("intentional test stop", emergency = source == ControlStopSource.EMERGENCY_STOP,
                    source = source)
            }
            // The synthetic board never releases this ACK. Completion must not wait for 2.5 s.
            withTimeout(1_000) {
                (field(fixture.vm, "driveJob") as Job).join()
                while (!fixture.bridge.isDisconnected) delay(5)
            }
            assertTrue(fixture.bridge.isDisconnected)
            assertFalse(fixture.vm.state.value.manualActive)
            assertFalse(fixture.vm.state.value.driveConfirmedStopped)
            assertNull(fixture.vm.state.value.driveFirstFailure)
            assertEquals(listOf("stop"), fixture.bridge.requests.map { it.getString("cmd") })
            assertFalse(fixture.updates.any { it.driveEnabled || it.canConfigureParking })
        }
        assertTrue("Intentional closures must not manufacture a first network fault", engine.snapshot().failures.isEmpty())
        val events = engine.snapshot().events.map(::JSONObject)
        assertFalse(events.any { it.optString("event") == "drive.stop_acknowledged" })
        assertFalse(events.any { it.optString("event") == "drive.ack_timeout" })
    }

    @Test fun graceEmergencyInterruptsBlockedWriteAndInvalidatesTransport() = runBlocking {
        val fixture = startFixture(networkGrace = true, blockWrite = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            fixture.vm.disableDrive("blocked write emergency", emergency = true)
        }
        withTimeout(1_000) { (field(fixture.vm, "driveJob") as Job).join() }
        assertTrue(fixture.socket.isClosed)
        assertTrue(fixture.bridge.isDisconnected)
        assertFalse(fixture.vm.state.value.connected)
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(engine.snapshot().failures.isEmpty())
        assertEquals(listOf(true), fixture.videoActions.toList())
    }

    @Test fun delayedAckKeepsVideoAndRequiresFreshNeutralEvenAfterReleaseAndRepress() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2,
            additionalHoldSequences = setOf(3), allowMovement = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, .5f) }
        withTimeout(3_000) { fixture.vm.state.first { it.driveCommunicationDelayed } }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveEnabled)
            assertEquals(0, fixture.vm.state.value.driveLeft)
            assertEquals(0, fixture.vm.state.value.driveRight)
            fixture.vm.releaseJoystick()
            fixture.vm.moveJoystick(0f, .5f)
            assertTrue(fixture.vm.state.value.driveRecoveryNeedsRelease)
            assertEquals(0, fixture.vm.state.value.driveLeft)
        }
        assertEquals(2, fixture.bridge.requests.size) // No overlapping write or token replay.
        assertEquals(listOf(true), fixture.videoActions.toList())
        assertTrue(fixture.vm.state.value.connected)
        assertFalse(fixture.socket.isClosed)
        fixture.bridge.releaseAck(2)
        withTimeout(3_000) { while (fixture.bridge.requests.size < 3) delay(10) }
        assertEquals("stop", fixture.bridge.requests[2].getString("cmd"))
        withContext(Dispatchers.Main) {
            fixture.vm.releaseJoystick()
            fixture.vm.moveJoystick(0f, .5f)
            assertTrue(fixture.vm.state.value.driveCommunicationDelayed)
            assertEquals(0, fixture.vm.state.value.driveLeft)
        }
        fixture.bridge.releaseAck(3)
        withTimeout(3_000) { fixture.vm.state.first { !it.driveCommunicationDelayed } }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveRecoveryNeedsRelease)
            assertEquals(0, fixture.vm.state.value.driveLeft)
        }
        assertTrue(fixture.bridge.requests.all { it.getString("cmd") == "stop" })
        withContext(Dispatchers.Main) { fixture.vm.releaseJoystick(); fixture.vm.moveJoystick(0f, .5f) }
        withTimeout(3_000) { while (fixture.bridge.requests.none { it.optString("cmd") == "drive" }) delay(10) }
        assertEquals(listOf(true), fixture.videoActions.toList())
        assertEquals(0, fixture.availabilityChecks.get())
        assertNull(fixture.vm.state.value.driveFirstFailure)
    }

    @Test fun consecutiveSevenHundredMillisecondNeutralAcksRecoverCommunicationWithoutClaimingFreshStandstill() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2,
            additionalHoldSequences = setOf(3, 4, 5), allowMovement = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withTimeout(3_000) { fixture.vm.state.first { it.driveCommunicationDelayed } }
        fixture.bridge.releaseAck(2)
        withTimeout(3_000) { while (fixture.bridge.requests.size < 3) delay(10) }
        assertEquals("stop", fixture.bridge.requests[2].getString("cmd"))
        delay(700)
        fixture.bridge.releaseAck(3)
        val recovered = withTimeout(3_000) { fixture.vm.state.first { !it.driveCommunicationDelayed } }
        assertTrue(recovered.driveEnabled)
        assertFalse(recovered.driveConfirmedStopped)
        assertFalse(recovered.driveFeedbackVerified)
        assertEquals(0, recovered.driveFeedbackLeftRpm)
        assertEquals(0, recovered.driveFeedbackRightRpm)
        assertTrue(recovered.driveFeedbackAgeAtReceiptMs >= 500L)

        withTimeout(3_000) { while (fixture.bridge.requests.size < 4) delay(10) }
        assertEquals("stop", fixture.bridge.requests[3].getString("cmd"))
        delay(700)
        fixture.bridge.releaseAck(4)
        val second = withTimeout(3_000) { fixture.vm.state.first {
            it.driveLastFeedbackNanos > recovered.driveLastFeedbackNanos
        } }
        assertFalse(second.driveCommunicationDelayed)
        assertFalse(second.driveConfirmedStopped)
        assertFalse(second.driveFeedbackVerified)
        assertTrue(second.driveEnabled)
        withContext(Dispatchers.Main) {
            fixture.vm.moveJoystick(0f, .5f)
            val expected = joystickToWheels(0f, .5f, fixture.vm.state.value.speedLimit)
            assertEquals(expected.left, fixture.vm.state.value.driveLeft)
            assertEquals(expected.right, fixture.vm.state.value.driveRight)
        }
        assertFalse(fixture.socket.isClosed)
        assertEquals(listOf(true), fixture.videoActions.toList())
        assertEquals(0, fixture.availabilityChecks.get())
        assertNull(fixture.vm.state.value.driveFirstFailure)
    }

    @Test fun delayedOldMotionAckCannotLetQueuedParkingPassTheFreshNeutralBarrier() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2,
            additionalHoldSequences = setOf(3, 4), allowMovement = true, unifiedParking = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, .5f) }
        fixture.bridge.releaseAck(2)
        withTimeout(3_000) { while (fixture.bridge.requests.size < 3) delay(10) }
        assertEquals("drive", fixture.bridge.requests[2].getString("cmd"))
        withContext(Dispatchers.Main) {
            fixture.vm.editParkingLeft("4")
            fixture.vm.editParkingRight("6")
            fixture.vm.saveParkingCurrents()
            assertTrue(fixture.vm.parkingPage.value.busy)
        }
        withTimeout(3_000) { fixture.vm.state.first { it.driveCommunicationDelayed } }
        fixture.bridge.releaseAck(3) // This ACK reports the old moving target, recoveryRequired=false.
        withTimeout(3_000) { while (fixture.bridge.requests.size < 4) delay(10) }
        assertEquals("stop", fixture.bridge.requests[3].getString("cmd"))
        assertFalse(fixture.bridge.requests.any { it.optString("cmd") == "parking_settings" })
        assertTrue(fixture.vm.state.value.driveCommunicationDelayed)
        fixture.bridge.releaseAck(4)
        withTimeout(3_000) { fixture.vm.parkingPage.first { !it.busy && it.leftCurrentAmps == 4.0 } }
        val setting = fixture.bridge.requests.first { it.optString("cmd") == "parking_settings" }
        assertTrue(setting.getLong("seq") > 4)
        assertTrue(fixture.bridge.requests.filter { it.getLong("seq") > 3 }
            .all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertFalse(fixture.vm.state.value.driveCommunicationDelayed)
        assertEquals(listOf(true), fixture.videoActions.toList())
        assertNull(fixture.vm.state.value.driveFirstFailure)
    }

    @Test fun intentionalStopDuringSoftAckWaitDoesNotWaitForHardTimeoutOrRestartVideo() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withTimeout(3_000) { fixture.vm.state.first { it.driveCommunicationDelayed } }
        withContext(Dispatchers.Main) { fixture.vm.disableDrive("test user stop") }
        withTimeout(1_000) { (field(fixture.vm, "driveJob") as Job).join() }
        assertFalse(fixture.vm.state.value.manualActive)
        assertFalse(fixture.vm.state.value.driveCommunicationDelayed)
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertTrue(fixture.bridge.isDisconnected)
        assertFalse(fixture.socket.isClosed)
        assertEquals(2, fixture.bridge.requests.size)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertFalse(engine.snapshot().events.map(::JSONObject).any { it.optString("event") == "drive.ack_timeout" })
    }

    @Test fun previousNeutralAckDoesNotConfirmAnInterruptedGraceSessionStop() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveEnabled)
            assertTrue(fixture.vm.state.value.driveConfirmedStopped)
            fixture.vm.onControlSurfaceHidden("test background", ControlStopSource.APP_PAUSED)
        }
        withTimeout(1_000) { (field(fixture.vm, "driveJob") as Job).join() }
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertEquals(listOf("stop", "stop"), fixture.bridge.requests.map { it.getString("cmd") })
        val events = engine.snapshot().events.map(::JSONObject)
        assertTrue(events.any { it.optString("event") == "drive.initial_stop_acknowledged" })
        assertFalse(events.any { it.optString("event") == "drive.stop_acknowledged" })
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    private suspend fun stopWhileInitialAckPending(source: ControlStopSource) {
        val fixture = startFixture()
        withTimeout(3_000) { fixture.bridge.firstRequest.await() }
        withContext(Dispatchers.Main) {
            if (source == ControlStopSource.WINDOW_FOCUS_LOST) {
                fixture.vm.onControlSurfaceHidden("test focus loss", source)
            } else fixture.vm.disableDrive("test user stop", source = source)
            assertTrue(fixture.vm.state.value.driveStopping)
            assertFalse(fixture.vm.state.value.driveEnabled)
        }
        fixture.bridge.releaseInitialAckIfPending()
        awaitFinished(fixture)
        assertFalse("Even a transient enabled state after a pending stop is forbidden",
            fixture.updates.any { it.driveEnabled || it.canConfigureParking })
        assertEquals(listOf("stop", "close"), fixture.bridge.requests.map { it.getString("cmd") })
        assertTrue(fixture.vm.state.value.driveConfirmedStopped)
        assertEquals(listOf(true, false), fixture.videoActions.toList())
        val events = engine.snapshot().events.map(::JSONObject)
        assertEquals(source.name, events.single { it.optString("event") == "drive.stop_requested" }.getString("source"))
        assertTrue(events.any { it.optString("event") == "drive.stop_acknowledged" })
    }

    @Test fun queuedParkingIsCancelledWithoutEndingControlWhenInFlightAckNeedsRecheck() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, transformAck = { request, ack ->
            if (request.getLong("seq") == 2L) ack.put("stopped", false)
                .put("feedbackLeftRpm", -1).put("feedbackSamples", 0)
                .put("feedbackRecheck", true).put("recoveryRequired", true)
        })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.canEditParking)
            fixture.vm.setParkingEnabled(true)
            assertTrue(fixture.vm.state.value.parkingUpdating)
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { while (fixture.bridge.requests.size < 4) delay(10) }
        assertTrue(fixture.updates.any { it.driveFeedbackRecheck && !it.driveConfirmedStopped })
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertFalse(fixture.vm.state.value.parkingUpdating)
        assertFalse(fixture.vm.state.value.driveFeedbackRecheck)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(fixture.bridge.requests.all { it.getString("cmd") == "stop" })
        assertTrue(fixture.vm.state.value.parkingStatus.contains("已取消"))
        val events = engine.snapshot().events.map(::JSONObject)
        assertEquals("FEEDBACK_RECHECK", events.single {
            it.optString("event") == "parking.cancelled_before_send"
        }.getString("source"))
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun queuedParkingWithExpiredAckIsCancelledInsteadOfThrowing() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) { fixture.vm.setParkingEnabled(true) }
        assertTrue(fixture.vm.state.value.parkingUpdating)
        delay(550) // Still within the negotiated ACK deadline, beyond the parking evidence budget.
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { while (fixture.bridge.requests.size < 4) delay(10) }
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertFalse(fixture.vm.state.value.parkingUpdating)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(fixture.bridge.requests.all { it.getString("cmd") == "stop" })
        assertTrue(fixture.vm.state.value.parkingStatus.contains("已取消"))
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun feedbackRecoveryClearsHeldTargetEvenDuringOlderNetworkNeutralConfirmation() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, allowMovement = true,
            transformAck = { request, ack ->
                if (request.getLong("seq") == 2L) ack.put("stopped", false)
                    .put("feedbackLeftRpm", 2).put("feedbackSamples", 0)
                    .put("feedbackRecheck", true).put("recoveryRequired", true)
            })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            // Model the existing network gate waiting for its neutral ACK. A new
            // feedback source on that ACK must not be swallowed as a duplicate.
            (field(fixture.vm, "driveRecoveryGate") as DriveRecoveryGate).recovered(neutralRequest = false)
            fixture.vm.moveJoystick(0f, 1f)
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { while (fixture.bridge.requests.size < 4) delay(10) }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveRecoveryNeedsRelease)
            assertTrue(fixture.vm.state.value.driveFeedbackRecheck)
            assertTrue(fixture.vm.state.value.driveStatus.contains("反馈已恢复"))
            assertFalse(fixture.vm.state.value.canEditParking)
            fixture.vm.moveJoystick(0f, 1f)
            assertEquals(0, fixture.vm.state.value.driveLeft)
            fixture.vm.releaseJoystick()
        }
        assertTrue(fixture.bridge.requests.all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        withTimeout(3_000) { fixture.vm.state.first { !it.driveFeedbackRecheck } }
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, 1f) }
        withTimeout(3_000) { while (fixture.bridge.requests.none { it.getString("cmd") == "drive" }) delay(10) }
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun sustainedMinorFeedbackNoiseKeepsSessionVideoAndAcceptsANewJoystickRequest() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, allowMovement = true,
            transformAck = { request, ack ->
                if (request.getLong("seq") >= 2L && request.getString("cmd") == "stop") {
                    val speed = if (request.getLong("seq") % 2 == 0L) 2 else -1
                    ack.put("feedbackLeftRpm", speed).put("feedbackRightRpm", 0)
                        .put("feedbackSamples", 0).put("stopped", false)
                }
            })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        fixture.bridge.releaseInitialAckIfPending()
        val warned = withTimeoutOrNull(3_000) { fixture.vm.state.first {
            it.driveFeedbackLeftRpm?.let { rpm -> rpm != 0 } == true || it.driveError != null
        } }
        assertNotNull("No warning ACK: status=${fixture.vm.state.value.driveStatus}, error=${fixture.vm.state.value.driveError}", warned)
        val startRequests = fixture.bridge.requests.size
        delay(900) // Sustained noisy samples, deliberately beyond the former 600 ms recheck deadline.
        val expectedWheels = joystickToWheels(0f, 1f, fixture.vm.state.value.speedLimit)
        withContext(Dispatchers.Main) {
            val state = fixture.vm.state.value
            assertTrue(state.driveEnabled)
            assertFalse(state.driveStopping)
            assertFalse(state.driveConfirmedStopped)
            assertFalse(state.driveFeedbackRecheck || state.driveStandstillPending || state.driveRecoveryNeedsRelease)
            assertTrue("status=${state.driveStatus}, error=${state.driveError}",
                state.driveStatus.contains("轮速反馈告警已记录"))
            assertFalse(state.canEditParking)
            fixture.vm.moveJoystick(0f, 1f)
            assertEquals(expectedWheels.left, fixture.vm.state.value.driveLeft)
            assertEquals(expectedWheels.right, fixture.vm.state.value.driveRight)
        }
        assertTrue(fixture.bridge.requests.size >= startRequests + 5)
        val accepted = withTimeoutOrNull(3_000) {
            while (fixture.bridge.requests.none {
                it.getString("cmd") == "drive" && it.getInt("left") == expectedWheels.left &&
                    it.getInt("right") == expectedWheels.right
            }) delay(10)
            true
        }
        assertEquals("No new drive request: status=${fixture.vm.state.value.driveStatus}, error=${fixture.vm.state.value.driveError}", true, accepted)
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertFalse(fixture.bridge.isDisconnected)
        assertFalse(fixture.socket.isClosed)
        assertEquals(listOf(true), fixture.videoActions.toList())
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(engine.snapshot().failures.isEmpty())
        withContext(Dispatchers.Main) { fixture.vm.disableDrive("test explicit stop") }
        awaitFinished(fixture)
        assertEquals(listOf(true, false), fixture.videoActions.toList())
    }

    @Test fun sustainedStandstillWaitKeepsNeutralHeartbeatsWithoutClaimingParkingCurrentWasDisabled() = runBlocking {
        val waiting = AtomicBoolean()
        val release = AtomicBoolean()
        val finalNeutral = AtomicBoolean()
        val fixture = startFixture(networkGrace = true, holdSequence = 2, transformAck = { request, ack ->
            if (request.getString("cmd") == "stop" && ack.getBoolean("parkingEnabled")) waiting.set(true)
            if (request.getString("cmd") == "stop" && waiting.get()) {
                if (release.get() && finalNeutral.get()) {
                    waiting.set(false)
                } else {
                    val zero = release.get()
                    if (zero) finalNeutral.set(true)
                    ack.put("standstillPending", true).put("feedbackRecheck", true).put("recoveryRequired", true)
                        .put("parkingEnabled", false).put("stopped", zero)
                        .put("feedbackLeftRpm", if (zero) 0 else 7).put("feedbackRightRpm", if (zero) 0 else -3)
                        .put("feedbackSamples", if (zero) 2 else 0)
                }
            }
        })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) { fixture.vm.setParkingEnabled(true) }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(5_000) { while (fixture.bridge.requests.size < 25) delay(10) }
        withContext(Dispatchers.Main) {
            val state = fixture.vm.state.value
            assertTrue(state.driveEnabled && state.driveStandstillPending)
            assertFalse(state.driveConfirmedStopped)
            assertFalse(state.canEditParking)
            assertEquals(7, state.driveFeedbackLeftRpm)
            assertEquals(-3, state.driveFeedbackRightRpm)
            assertEquals(ParkingSettings(false, 5.0, 5.0, 60.0), state.parkingSettings)
            assertTrue(state.driveStatus.contains("停车反馈待确认 · 会话保持"))
            assertTrue(state.parkingStatus.contains("停车反馈待确认 · 会话保持"))
            assertFalse(state.parkingStatus.contains("电子驻车已暂停") || state.parkingStatus.contains("保持关闭"))
            fixture.vm.releaseJoystick()
            assertTrue(fixture.vm.state.value.driveStatus.contains("等待停车确认"))
            fixture.vm.setParkingEnabled(true) // Still cannot queue a reenable while physically moving.
        }
        assertTrue(fixture.updates.any { it.parkingSettings?.enabled == true })
        assertEquals(1, fixture.bridge.requests.count { it.getString("cmd") == "parking" })
        assertTrue(fixture.bridge.requests.all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(engine.snapshot().failures.isEmpty())
        val resetEvents = engine.snapshot().events.map(::JSONObject).filter {
            it.optString("event") == "drive.recovery_input_reset" && it.optString("source") == "STANDSTILL_PENDING"
        }
        assertEquals("An unchanged pending heartbeat does not reset/log critical state again", 1, resetEvents.size)
        release.set(true)
        withTimeout(3_000) { fixture.vm.state.first { !it.driveStandstillPending && it.driveConfirmedStopped } }
        assertTrue(fixture.updates.any { it.driveStandstillPending && it.driveConfirmedStopped && !it.canEditParking })
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.canEditParking)
            assertEquals(false, fixture.vm.state.value.parkingSettings?.enabled)
            fixture.vm.disableDrive("test stop")
        }
        awaitFinished(fixture)
        assertFalse(fixture.vm.state.value.driveStandstillPending)
    }

    @Test fun standstillWaitNeverResumesAHeldTargetWhenTheWheelsBecomeZero() = runBlocking {
        val release = AtomicBoolean()
        val releasedAcks = AtomicInteger()
        val fixture = startFixture(networkGrace = true, holdSequence = 2, allowMovement = true,
            transformAck = { request, ack ->
                if (request.getString("cmd") == "stop" && request.getLong("seq") >= 2L) {
                    val pending = !release.get() || releasedAcks.getAndIncrement() == 0
                    if (pending) ack.put("standstillPending", true).put("feedbackRecheck", true)
                        .put("recoveryRequired", true).put("stopped", release.get())
                        .put("feedbackLeftRpm", if (release.get()) 0 else 7)
                        .put("feedbackRightRpm", if (release.get()) 0 else -3)
                        .put("feedbackSamples", if (release.get()) 2 else 0)
                }
            })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, 1f) }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.state.first { it.driveStandstillPending && it.driveRecoveryNeedsRelease } }
        release.set(true)
        withTimeout(3_000) { while (fixture.bridge.requests.size < 6) delay(10) }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveStandstillPending)
            assertTrue(fixture.vm.state.value.driveConfirmedStopped)
            assertFalse(fixture.vm.state.value.canEditParking)
            fixture.vm.moveJoystick(0f, 1f)
            assertEquals(0, fixture.vm.state.value.driveLeft)
            fixture.vm.releaseJoystick()
        }
        assertTrue(fixture.bridge.requests.all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        withTimeout(3_000) { fixture.vm.state.first { !it.driveStandstillPending && !it.driveFeedbackRecheck } }
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, 1f) }
        withTimeout(3_000) { while (fixture.bridge.requests.none { it.getString("cmd") == "drive" }) delay(10) }
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun inputPressedBetweenFinalPendingAckAndHealthyAckStillRequiresRelease() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 3, allowMovement = true,
            transformAck = { request, ack ->
                if (request.getLong("seq") == 2L) ack.put("standstillPending", true)
                    .put("feedbackRecheck", true).put("recoveryRequired", true)
            })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveStandstillPending)
            assertFalse(fixture.vm.state.value.driveRecoveryNeedsRelease)
            fixture.vm.moveJoystick(0f, 1f)
            assertTrue(fixture.vm.state.value.driveRecoveryNeedsRelease)
            assertEquals(0, fixture.vm.state.value.driveLeft)
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { while (fixture.bridge.requests.size < 5) delay(10) }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveStandstillPending)
            assertTrue(fixture.vm.state.value.driveRecoveryNeedsRelease)
            assertFalse(fixture.vm.state.value.canEditParking)
            fixture.vm.moveJoystick(0f, 1f)
            assertEquals(0, fixture.vm.state.value.driveLeft)
            fixture.vm.releaseJoystick()
        }
        assertTrue(fixture.bridge.requests.all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        withTimeout(3_000) { fixture.vm.state.first { !it.driveStandstillPending && !it.driveFeedbackRecheck } }
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, 1f) }
        withTimeout(3_000) { while (fixture.bridge.requests.none { it.getString("cmd") == "drive" }) delay(10) }
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun unifiedParkingSaveUsesOwnerNeutralThenReadAndWriteAndPreservesNewDraftEdits() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, allowMovement = true, unifiedParking = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            fixture.vm.moveJoystick(0f, 1f)
            fixture.vm.editParkingLeft("4.25")
            fixture.vm.editParkingRight("8")
            fixture.vm.saveParkingCurrents()
            assertTrue(fixture.vm.parkingPage.value.busy)
            fixture.vm.editParkingLeft("") // Clearing during the request is also a new draft.
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.parkingPage.first { !it.busy && it.leftCurrentAmps == 4.25 } }
        withContext(Dispatchers.Main) {
            val page = fixture.vm.parkingPage.value
            assertNull(page.error)
            assertEquals("", page.draftLeft)
            assertEquals("8", page.draftRight)
            assertEquals(8.0, page.rightCurrentAmps)
            assertTrue(fixture.vm.state.value.driveEnabled)
            assertTrue(fixture.vm.state.value.driveRecoveryNeedsRelease)
            assertEquals(listOf(true), fixture.videoActions.toList())
            fixture.vm.moveJoystick(0f, 1f)
            assertEquals(0, fixture.vm.state.value.driveLeft)
        }
        val owner = fixture.bridge.requests.filter { it.getString("cmd") == "parking_settings" }
        assertEquals(listOf("read", "currents"), owner.map { it.getString("op") })
        val readIndex = fixture.bridge.requests.indexOf(owner.first())
        assertEquals("stop", fixture.bridge.requests[readIndex - 1].getString("cmd"))
        assertTrue(fixture.bridge.requests.all { it.getInt("left") == 0 && it.getInt("right") == 0 })
        withContext(Dispatchers.Main) { fixture.vm.releaseJoystick(); fixture.vm.moveJoystick(0f, 1f) }
        withTimeout(3_000) { while (fixture.bridge.requests.none { it.getString("cmd") == "drive" }) delay(10) }
        assertNull(fixture.vm.state.value.driveFirstFailure)
    }

    @Test fun unifiedReadAcceptsUnconfirmedPhysicalStopAndSoftFailureDoesNotExitDrive() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, unifiedParking = true,
            transformAck = { request, ack ->
                if (request.getLong("seq") >= 2 && request.getString("cmd") !in setOf("close", "estop")) {
                    ack.put("stopped", false).put("feedbackLeftRpm", 2).put("feedbackRightRpm", -1).put("feedbackSamples", 0)
                }
                if (request.optString("op") == "read") ack.getJSONObject("parkingResult")
                    .put("ok", false).put("errorCode", "external_control_source")
            })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.state.first { it.driveEnabled && !it.driveConfirmedStopped } }
        withContext(Dispatchers.Main) { fixture.vm.refreshParking() }
        withTimeout(3_000) { fixture.vm.parkingPage.first { !it.busy && it.error != null } }
        assertTrue(fixture.vm.parkingPage.value.error!!.contains("通讯控制"))
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertEquals(listOf(true), fixture.videoActions.toList())
    }

    @Test fun unifiedReadPreservesClearedDraftAndFinishedSessionKeepsExplicitlyStaleLastRead() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, unifiedParking = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            fixture.vm.editParkingLeft("")
            fixture.vm.refreshParking()
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.parkingPage.first { !it.busy && it.valuesCurrent } }
        withContext(Dispatchers.Main) { fixture.vm.refreshParking() }
        withTimeout(3_000) { fixture.vm.parkingPage.first { !it.busy } }
        assertEquals("", fixture.vm.parkingPage.value.draftLeft)
        withContext(Dispatchers.Main) { fixture.vm.disableDrive("test") }
        awaitFinished(fixture)
        assertFalse(fixture.vm.parkingPage.value.valuesCurrent)
        assertEquals(5.0, fixture.vm.parkingPage.value.leftCurrentAmps)
        assertEquals(5.0, fixture.vm.parkingPage.value.rightCurrentAmps)
        assertEquals("", fixture.vm.parkingPage.value.draftLeft)
        assertEquals(listOf(true, false), fixture.videoActions.toList())
    }

    @Test fun zeroDraftDoesNotWriteUntilSaveAndSavedZerosDisableBothSidesWithoutModeCommand() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2, unifiedParking = true)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            fixture.vm.editParkingLeft("0")
            fixture.vm.editParkingRight("0")
            assertFalse(fixture.vm.parkingPage.value.busy)
            assertTrue(fixture.bridge.requests.none { it.optString("cmd") == "parking_settings" })
            fixture.vm.saveParkingCurrents()
            assertTrue(fixture.vm.parkingPage.value.busy)
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.parkingPage.first {
            !it.busy && it.valuesCurrent && it.leftCurrentAmps == 0.0 && it.rightCurrentAmps == 0.0
        } }
        assertEquals(false, fixture.vm.parkingPage.value.mode)
        assertNull(fixture.vm.parkingPage.value.error)
        assertTrue(fixture.vm.state.value.driveEnabled)
        val owner = fixture.bridge.requests.filter { it.optString("cmd") == "parking_settings" }
        assertEquals(listOf("read", "currents"), owner.map { it.getString("op") })
        assertTrue(owner.all { it.getInt("schemaVersion") == 2 && it.getString("parkingSemantics") == PARKING_CURRENT_SEMANTICS })
        assertEquals(0, owner.last().getInt("leftRaw"))
        assertEquals(0, owner.last().getInt("rightRaw"))
        assertTrue(fixture.bridge.requests.none { it.optString("op") == "mode" })
    }

    @Test fun unknownCurrentReadbackIsNotReplacedByLaterCachedHeartbeatNumbers() = runBlocking {
        var unknownFromSequence = Long.MAX_VALUE
        val fixture = startFixture(networkGrace = true, holdSequence = 2, unifiedParking = true,
            transformAck = { request, ack ->
                if (request.optString("op") == "currents") {
                    unknownFromSequence = request.getLong("seq")
                    ack.getJSONObject("parkingResult").put("ok", false).put("errorCode", "readback_mismatch")
                        .put("leftRaw", JSONObject.NULL).put("rightRaw", 500)
                        .put("mode", JSONObject.NULL).put("modeSource", "unknown")
                        .put("results", JSONArray()
                            .put(JSONObject().put("side", "left").put("status", "unknown")
                                .put("requestedRaw", 800).put("actualRaw", JSONObject.NULL))
                            .put(JSONObject().put("side", "right").put("status", "not_attempted")
                                .put("requestedRaw", 900).put("actualRaw", 500)))
                }
                if (request.getLong("seq") >= unknownFromSequence) ack
                    .put("parkingLeftCurrentAmps", 5.0).put("parkingRightCurrentAmps", 5.0)
                    .put("parkingMode", JSONObject.NULL).put("parkingModeSource", "unknown")
            })
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            fixture.vm.editParkingLeft("8"); fixture.vm.editParkingRight("9"); fixture.vm.saveParkingCurrents()
        }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.parkingPage.first { !it.busy && it.error != null } }
        withTimeout(3_000) {
            while (fixture.bridge.requests.none { it.getLong("seq") >= unknownFromSequence + 2 }) delay(10)
        }
        assertNull(fixture.vm.parkingPage.value.leftCurrentAmps)
        assertEquals(5.0, fixture.vm.parkingPage.value.rightCurrentAmps)
        assertFalse(fixture.vm.parkingPage.value.valuesCurrent)
        assertEquals("8", fixture.vm.parkingPage.value.draftLeft)
        assertTrue(fixture.vm.state.value.driveEnabled)
        assertNull(fixture.vm.state.value.driveFirstFailure)
    }

    @Test fun negotiatedEightyStartupAndTerminalWarningsDoNotFailTheSessionOrForgeZero() = runBlocking {
        val fixture = startFixture(allowMovement = true, relaxedStops = true,
            transformAck = { request, ack ->
                if (request.getString("cmd") != "drive") ack.put("stopped", false)
                    .put("feedbackLeftRpm", 80).put("feedbackRightRpm", -80).put("feedbackSamples", 0)
            })
        withTimeout(3_000) { fixture.bridge.firstRequest.await() }
        fixture.bridge.releaseInitialAckIfPending()
        withTimeout(3_000) { fixture.vm.state.first { it.driveEnabled } }
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertEquals(80, fixture.vm.state.value.driveFeedbackLeftRpm)
        withContext(Dispatchers.Main) { fixture.vm.moveJoystick(0f, .5f) }
        withTimeout(3_000) { while (fixture.bridge.requests.none { it.optString("cmd") == "drive" }) delay(10) }
        withContext(Dispatchers.Main) { fixture.vm.disableDrive("test stop") }
        awaitFinished(fixture)
        assertNull(fixture.vm.state.value.driveFirstFailure)
        assertFalse(fixture.vm.state.value.driveConfirmedStopped)
        assertTrue(engine.snapshot().events.map(::JSONObject).any { it.optString("event") == "drive.stop_acknowledged" &&
            !it.getJSONObject("fields").getBoolean("physicalStopped") &&
            it.getJSONObject("fields").getBoolean("stopCommandConfirmed") })
        assertTrue(fixture.vm.state.value.connected)
        assertFalse(fixture.socket.isClosed)
        assertEquals(listOf(true, false), fixture.videoActions.toList())
    }

    private suspend fun startFixture(networkGrace: Boolean = false, blockWrite: Boolean = false,
        holdSequence: Long = 1, allowMovement: Boolean = false,
        unifiedParking: Boolean = false, relaxedStops: Boolean = false,
        additionalHoldSequences: Set<Long> = emptySet(), controlAvailable: Boolean = true,
        transformAck: (JSONObject, JSONObject) -> Unit = { _, _ -> }): Fixture {
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also(stores::add)
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
        }
        withTimeout(3_000) { vm.state.first { !it.remoteProfileLoading } }
        val bridge = DelayedInitialAckChannel(networkGrace, blockWrite, holdSequence, additionalHoldSequences,
            allowMovement, unifiedParking, relaxedStops, transformAck)
        val videoActions = CopyOnWriteArrayList<Boolean>()
        val available = AtomicBoolean(controlAvailable)
        val availabilityChecks = AtomicInteger()
        val queued = ConcurrentLinkedQueue<ChannelExec>().apply {
            add(VideoReplyChannel(true, videoActions)); add(bridge)
        }
        val session = object : DiagnosticTestSession(bridge) {
            override fun openChannel(type: String): Channel {
                check(type == "exec")
                return queued.poll() ?: PostDriveReplyChannel(videoActions, available, availabilityChecks)
            }
        }
        val socket = Socket() // Deliberately unconnected. No operation can use a real network.
        val updates = CopyOnWriteArrayList<BoardUiState>()
        val fixture = Fixture(vm, bridge, socket, updates, videoActions, available, availabilityChecks).also(fixtures::add)
        withContext(Dispatchers.Main) {
            val client = field(vm, "client") as BoardSshClient
            attachConnection(client, session, socket)
            @Suppress("UNCHECKED_CAST")
            val original = field(vm, "mutableState") as MutableStateFlow<BoardUiState>
            // Record every successful update, not just StateFlow's conflated observations;
            // a reenable immediately followed by cleanup must still fail these tests.
            setField(vm, "mutableState", RecordingStateFlow(original, updates))
            original.value = vm.state.value.copy(connected = true, status = "synthetic connected",
                remoteMode = false, followUncertain = false, driveCleanupUnconfirmed = false,
                pairingBusy = false, pairingUnconfirmed = false)
            assertTrue(vm.state.value.canEnableDrive)
            vm.enableDrive(dryRun = true)
        }
        return fixture
    }

    private suspend fun awaitFinished(fixture: Fixture) {
        withTimeout(5_000) { (field(fixture.vm, "driveJob") as Job).join() }
        assertFalse(fixture.vm.state.value.manualActive)
    }

    private fun attachConnection(client: BoardSshClient, session: DiagnosticTestSession, socket: Socket) {
        val type = BoardSshClient::class.java.declaredClasses.single { it.simpleName == "Connection" }
        val context = DiagnosticContext("startup-fixture-${UUID.randomUUID()}", "synthetic-connection")
        val connection = type.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(session, socket, context)
        type.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(connection, true)
        BoardSshClient::class.java.getDeclaredField("connection").apply { isAccessible = true }.set(client, connection)
    }

    private fun field(vm: BoardViewModel, name: String): Any? =
        BoardViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)

    private fun setField(vm: BoardViewModel, name: String, value: Any) {
        BoardViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private data class Fixture(val vm: BoardViewModel, val bridge: DelayedInitialAckChannel,
        val socket: Socket, val updates: CopyOnWriteArrayList<BoardUiState>,
        val videoActions: CopyOnWriteArrayList<Boolean>, val controlAvailable: AtomicBoolean,
        val availabilityChecks: AtomicInteger)

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class RecordingStateFlow(private val delegate: MutableStateFlow<BoardUiState>,
        private val updates: MutableList<BoardUiState>) : MutableStateFlow<BoardUiState> by delegate {
        override var value: BoardUiState
            get() = delegate.value
            set(value) { updates.add(value); delegate.value = value }
        override fun compareAndSet(expect: BoardUiState, update: BoardUiState): Boolean =
            delegate.compareAndSet(expect, update).also { if (it) updates.add(update) }
        override suspend fun emit(value: BoardUiState) { this.value = value }
        override fun tryEmit(value: BoardUiState): Boolean { this.value = value; return true }
    }

    private class QueuedInput : InputStream() {
        private val bytes = ConcurrentLinkedQueue<Int>()
        fun append(value: JSONObject) { (value.toString() + "\n").toByteArray().forEach { bytes.add(it.toInt() and 255) } }
        override fun available(): Int = bytes.size
        override fun read(): Int = bytes.poll() ?: -1
    }

    private class DelayedInitialAckChannel(private val networkGrace: Boolean,
        private val blockWrite: Boolean, private val holdSequence: Long, private val additionalHoldSequences: Set<Long>,
        private val allowMovement: Boolean, private val unifiedParking: Boolean, private val relaxedStops: Boolean,
        private val transformAck: (JSONObject, JSONObject) -> Unit) : ChannelExec() {
        private val replies = QueuedInput()
        private val buffer = ByteArrayOutputStream()
        private val pending = ConcurrentHashMap<Long, JSONObject>()
        private val writeReleased = CountDownLatch(1)
        private var expectedSequence = 1L
        private var token = tokenFor(1)
        private var parkingEnabled = false
        private var leftCurrentRaw = 500
        private var rightCurrentRaw = 500
        @Volatile private var connected = false
        @Volatile private var disconnected = false
        val requests = CopyOnWriteArrayList<JSONObject>()
        val firstRequest = CompletableDeferred<JSONObject>()
        val heldRequest = CompletableDeferred<JSONObject>()
        val isDisconnected: Boolean get() = disconnected
        override fun setCommand(command: String) {
            check(command == driveBridgeCommand(dryRun = true))
        }
        override fun connect(timeout: Int) {
            check(timeout == 5_000)
            connected = true
            val ready = JSONObject().put("type", "ready").put("token", token)
                .put("dryRun", true).put("port", "synthetic-only").put("watchdogMs", 600).put("maxRpm", 60)
                .withDriveSafetyFixture()
            if (networkGrace) ready.put("networkGraceMs", 5_000)
            if (relaxedStops) ready.put("stopConfirmationWarningSupported", true)
                .put("stopCommandConfirmed", true).put("feedbackNoiseMaxRpm", 80)
            if (unifiedParking) ready.put("unifiedParkingSupported", true)
                .put("parkingSemantics", PARKING_CURRENT_SEMANTICS).put("parkingMode", true)
                .put("parkingModeSource", "current_readback")
            replies.append(ready)
        }
        override fun getInputStream(): InputStream = replies
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { buffer.write(value) }
            override fun flush() {
                val request = JSONObject(buffer.toString(Charsets.UTF_8.name()).trim())
                buffer.reset()
                check(request.getLong("seq") == expectedSequence++)
                check(request.getString("token") == token)
                check(allowMovement || request.getInt("left") == 0 && request.getInt("right") == 0)
                requests.add(request)
                if (request.getLong("seq") == holdSequence || request.getLong("seq") in additionalHoldSequences) {
                    pending[request.getLong("seq")] = request
                    if (request.getLong("seq") == 1L) firstRequest.complete(request)
                    if (request.getLong("seq") == holdSequence) heldRequest.complete(request)
                    if (blockWrite) {
                        check(writeReleased.await(4, TimeUnit.SECONDS)) { "Synthetic write was not interrupted" }
                        throw IOException("Synthetic channel closed during write")
                    }
                } else {
                    if (request.getLong("seq") == 1L) firstRequest.complete(request)
                    acknowledge(request)
                }
            }
        }
        fun releaseInitialAckIfPending() {
            val sequence = pending.keys.minOrNull() ?: return
            releaseAck(sequence)
        }
        fun releaseAck(sequence: Long) {
            if (disconnected) return
            pending.remove(sequence)?.let(::acknowledge)
        }
        private fun acknowledge(request: JSONObject) {
            when (request.getString("cmd")) {
                "parking" -> { parkingEnabled = request.getBoolean("parkingEnabled") }
                "close", "estop" -> { parkingEnabled = false }
                "parking_settings" -> when (request.getString("op")) {
                    "currents" -> { leftCurrentRaw = request.getInt("leftRaw"); rightCurrentRaw = request.getInt("rightRaw") }
                }
            }
            token = tokenFor(request.getLong("seq") + 1)
            val ack = JSONObject().put("type", "ack").put("seq", request.getLong("seq"))
                .put("token", token).put("left", request.getInt("left")).put("right", request.getInt("right"))
                .withDriveSafetyFixture()
                .put("parkingEnabled", parkingEnabled)
                .put("parkingLeftCurrentAmps", leftCurrentRaw / 100.0)
                .put("parkingRightCurrentAmps", rightCurrentRaw / 100.0)
            if (networkGrace) ack.put("recoveryRequired", false)
            val parkingMode = parkingCurrentMode(leftCurrentRaw, rightCurrentRaw)
            if (unifiedParking) ack.put("parkingMode", parkingMode ?: JSONObject.NULL)
                .put("parkingSemantics", PARKING_CURRENT_SEMANTICS).put("parkingModeSource", "current_readback")
            if (request.getString("cmd") == "parking_settings") {
                val op = request.getString("op")
                check(request.getInt("schemaVersion") == 2 && request.getString("parkingSemantics") == PARKING_CURRENT_SEMANTICS)
                val result = JSONObject().put("type", "parking").put("schemaVersion", 2)
                    .put("parkingSemantics", PARKING_CURRENT_SEMANTICS)
                    .put("requestId", request.getLong("seq").toString()).put("op", op).put("ok", true)
                    .put("simulation", true).put("mode", parkingMode ?: JSONObject.NULL)
                    .put("modeSource", "current_readback")
                    .put("leftRaw", leftCurrentRaw).put("rightRaw", rightCurrentRaw).put("maxRaw", 6000)
                    .put("leftRpm", 0).put("rightRpm", 0).put("leftError", 0).put("rightError", 0)
                    .put("writeMayHaveOccurred", op != "read").put("errorCode", JSONObject.NULL)
                    .put("results", JSONArray().apply { if (op == "currents") {
                        for ((side, raw) in listOf("left" to leftCurrentRaw, "right" to rightCurrentRaw))
                            put(JSONObject().put("side", side).put("status", "verified").put("requestedRaw", raw).put("actualRaw", raw))
                    } })
                ack.put("parkingResult", result)
            }
            if (relaxedStops) ack.put("stopCommandConfirmed", request.getInt("left") == 0 && request.getInt("right") == 0)
            transformAck(request, ack)
            parkingEnabled = ack.getBoolean("parkingEnabled")
            replies.append(ack)
        }
        override fun isConnected(): Boolean = connected && !disconnected
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = disconnected
        override fun disconnect() { disconnected = true; writeReleased.countDown() }
        companion object { private fun tokenFor(value: Long) = value.toString(16).padStart(32, '0') }
    }

    /** Read-only recovery has its own channel; it cannot consume a queued video action. */
    private class PostDriveReplyChannel(private val videoActions: MutableList<Boolean>,
        private val available: AtomicBoolean, private val checks: AtomicInteger) : ChannelExec() {
        private lateinit var sink: OutputStream
        private var inspect = false
        private var closed = false
        override fun setCommand(command: String) {
            inspect = command.endsWith("app_follow_bridge.py --inspect")
            check(inspect || command == videoServiceCommand(false)) { "Unexpected synthetic command" }
        }
        override fun setOutputStream(output: OutputStream, dontclose: Boolean) { sink = output }
        override fun connect(timeout: Int) {
            val output = if (inspect) {
                checks.incrementAndGet()
                JSONObject().put("type", "availability").put("controlAvailable", available.get()).toString()
            } else {
                videoActions.add(false)
                "VIDEO_SERVICE_V1\tloaded\tinactive\t0\t0\tempty\tloaded\tinactive\t0\t0\tempty\tnot-ready\n"
            }
            sink.write(output.toByteArray())
            closed = true
        }
        override fun isConnected(): Boolean = !closed
        override fun isClosed(): Boolean = closed
        override fun getExitStatus(): Int = if (closed) 0 else -1
        override fun disconnect() { closed = true }
    }

    private class VideoReplyChannel(private val enabled: Boolean,
        private val actions: MutableList<Boolean>) : ChannelExec() {
        private lateinit var sink: OutputStream
        @Volatile private var closed = false
        override fun setCommand(command: String) { check(command == videoServiceCommand(enabled)) }
        override fun setOutputStream(output: OutputStream, dontclose: Boolean) { sink = output }
        override fun connect(timeout: Int) {
            val unit = if (enabled) "loaded\tactive\t123\t0\tpopulated" else "loaded\tinactive\t0\t0\tempty"
            val health = if (enabled) "ready" else "not-ready"
            sink.write("VIDEO_SERVICE_V1\t$unit\t$unit\t$health\n".toByteArray())
            actions.add(enabled)
            closed = true
        }
        override fun isConnected(): Boolean = !closed
        override fun isClosed(): Boolean = closed
        override fun getExitStatus(): Int = if (closed) 0 else -1
        override fun disconnect() { closed = true }
    }

    private class IsolatedApplication(base: Context, private val prefix: String,
        private val privateDirectory: File) : Application() {
        private val preferenceNames = ConcurrentLinkedQueue<String>()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = privateDirectory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun deleteTestPreferences() { preferenceNames.distinct().forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
