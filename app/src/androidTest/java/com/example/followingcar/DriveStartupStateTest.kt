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
import org.json.JSONObject
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

    @Test fun focusLossDuringInitialAckNeverReenablesControl() = runBlocking {
        stopWhileInitialAckPending(ControlStopSource.WINDOW_FOCUS_LOST)
    }

    @Test fun initialAckTimeoutKeepsInputLockedAndPublishesTransportLoss() = runBlocking {
        val fixture = startFixture()
        withTimeout(3_000) { fixture.bridge.firstRequest.await() }
        awaitFinished(fixture)
        val state = fixture.vm.state.value
        assertFalse(state.driveEnabled)
        assertFalse(state.driveStarting)
        assertFalse(state.driveConfirmedStopped)
        assertFalse(state.connected)
        assertFalse(fixture.updates.any { it.driveEnabled || it.canConfigureParking })
        assertTrue(fixture.socket.isClosed)
        assertEquals(1, fixture.bridge.requests.size)
        assertEquals(listOf(true), fixture.videoActions.toList()) // Never pretend cleanup reached a lost SSH session.
        val failure = engine.snapshot().failures.single { it.event == "drive.ack_timeout" }
        assertEquals(failure, state.driveFirstFailure)
        val events = engine.snapshot().events.map(::JSONObject)
        assertFalse(events.any { it.optString("event") == "drive.initial_stop_acknowledged" })
        assertTrue(events.any { it.optString("event") == "ssh.transport_abort" })
    }

    @Test fun graceSessionIntentionalStopsInterruptPendingAckWithoutNetworkFailure() = runBlocking {
        for (source in listOf(ControlStopSource.EMERGENCY_STOP, ControlStopSource.APP_PAUSED,
            ControlStopSource.WINDOW_FOCUS_LOST, ControlStopSource.CONTROL_SURFACE_HIDDEN,
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

    @Test fun previousNeutralAckDoesNotConfirmAnInterruptedGraceSessionStop() = runBlocking {
        val fixture = startFixture(networkGrace = true, holdSequence = 2)
        withTimeout(3_000) { fixture.bridge.heldRequest.await() }
        withContext(Dispatchers.Main) {
            assertTrue(fixture.vm.state.value.driveEnabled)
            assertTrue(fixture.vm.state.value.driveConfirmedStopped)
            fixture.vm.onControlSurfaceHidden("test focus loss", ControlStopSource.WINDOW_FOCUS_LOST)
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

    @Test fun sustainedStandstillWaitKeepsNeutralHeartbeatsAndShowsActualParkingRelease() = runBlocking {
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
            assertTrue(state.driveStatus.contains("正在减速"))
            assertTrue(state.parkingStatus.contains("电子驻车已暂停"))
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

    private suspend fun startFixture(networkGrace: Boolean = false, blockWrite: Boolean = false,
        holdSequence: Long = 1, allowMovement: Boolean = false,
        transformAck: (JSONObject, JSONObject) -> Unit = { _, _ -> }): Fixture {
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also(stores::add)
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
        }
        withTimeout(3_000) { vm.state.first { !it.remoteProfileLoading } }
        val bridge = DelayedInitialAckChannel(networkGrace, blockWrite, holdSequence, allowMovement, transformAck)
        val videoActions = CopyOnWriteArrayList<Boolean>()
        val queued = ConcurrentLinkedQueue<ChannelExec>().apply {
            add(VideoReplyChannel(true, videoActions)); add(bridge); add(VideoReplyChannel(false, videoActions))
        }
        val session = object : DiagnosticTestSession(bridge) {
            override fun openChannel(type: String): Channel {
                check(type == "exec")
                return queued.poll() ?: throw IOException("Unexpected synthetic channel request")
            }
        }
        val socket = Socket() // Deliberately unconnected. No operation can use a real network.
        val updates = CopyOnWriteArrayList<BoardUiState>()
        val fixture = Fixture(vm, bridge, socket, updates, videoActions).also(fixtures::add)
        withContext(Dispatchers.Main) {
            val client = field(vm, "client") as BoardSshClient
            attachConnection(client, session, socket)
            @Suppress("UNCHECKED_CAST")
            val original = field(vm, "mutableState") as MutableStateFlow<BoardUiState>
            // Record every successful update, not just StateFlow's conflated observations;
            // a reenable immediately followed by cleanup must still fail these tests.
            setField(vm, "mutableState", RecordingStateFlow(original, updates))
            original.value = vm.state.value.copy(connected = true, status = "synthetic connected",
                remoteMode = false, followUncertain = false, pairingBusy = false, pairingUnconfirmed = false)
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
        val videoActions: CopyOnWriteArrayList<Boolean>)

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
        private val blockWrite: Boolean, private val holdSequence: Long,
        private val allowMovement: Boolean, private val transformAck: (JSONObject, JSONObject) -> Unit) : ChannelExec() {
        private val replies = QueuedInput()
        private val buffer = ByteArrayOutputStream()
        private val released = AtomicBoolean()
        private val writeReleased = CountDownLatch(1)
        private var expectedSequence = 1L
        private var token = tokenFor(1)
        private var parkingEnabled = false
        @Volatile private var connected = false
        @Volatile private var disconnected = false
        @Volatile private var pending: JSONObject? = null
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
                if (request.getLong("seq") == holdSequence) {
                    pending = request
                    if (request.getLong("seq") == 1L) firstRequest.complete(request)
                    heldRequest.complete(request)
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
            val request = pending ?: return
            if (disconnected) return
            if (released.compareAndSet(false, true)) acknowledge(request)
        }
        private fun acknowledge(request: JSONObject) {
            when (request.getString("cmd")) {
                "parking" -> parkingEnabled = request.getBoolean("parkingEnabled")
                "close", "estop" -> parkingEnabled = false
            }
            token = tokenFor(request.getLong("seq") + 1)
            val ack = JSONObject().put("type", "ack").put("seq", request.getLong("seq"))
                .put("token", token).put("left", request.getInt("left")).put("right", request.getInt("right"))
                .withDriveSafetyFixture()
                .put("parkingEnabled", parkingEnabled)
            if (networkGrace) ack.put("recoveryRequired", false)
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
