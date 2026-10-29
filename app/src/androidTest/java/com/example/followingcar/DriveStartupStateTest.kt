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
import java.util.concurrent.atomic.AtomicBoolean
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

    private suspend fun startFixture(): Fixture {
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also(stores::add)
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
        }
        withTimeout(3_000) { vm.state.first { !it.remoteProfileLoading } }
        val bridge = DelayedInitialAckChannel()
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

    private class DelayedInitialAckChannel : ChannelExec() {
        private val replies = QueuedInput()
        private val buffer = ByteArrayOutputStream()
        private val released = AtomicBoolean()
        private var expectedSequence = 1L
        private var token = tokenFor(1)
        @Volatile private var connected = false
        @Volatile private var disconnected = false
        @Volatile private var pending: JSONObject? = null
        val requests = CopyOnWriteArrayList<JSONObject>()
        val firstRequest = CompletableDeferred<JSONObject>()
        override fun setCommand(command: String) {
            check(command == "python3 -u /home/topeet/lianzhan/scripts/app_joystick_bridge.py --dry-run")
        }
        override fun connect(timeout: Int) {
            check(timeout == 5_000)
            connected = true
            replies.append(JSONObject().put("type", "ready").put("token", token)
                .put("dryRun", true).put("port", "synthetic-only").put("watchdogMs", 600).put("maxRpm", 60)
                .put("parkingSupported", true).put("parkingEnabled", false).put("parkingCurrent", 5))
        }
        override fun getInputStream(): InputStream = replies
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { buffer.write(value) }
            override fun flush() {
                val request = JSONObject(buffer.toString(Charsets.UTF_8.name()).trim())
                buffer.reset()
                check(request.getLong("seq") == expectedSequence++)
                check(request.getString("token") == token)
                check(request.getInt("left") == 0 && request.getInt("right") == 0)
                requests.add(request)
                if (request.getLong("seq") == 1L) {
                    pending = request
                    firstRequest.complete(request)
                } else acknowledge(request)
            }
        }
        fun releaseInitialAckIfPending() {
            val request = pending ?: return
            if (released.compareAndSet(false, true)) acknowledge(request)
        }
        private fun acknowledge(request: JSONObject) {
            token = tokenFor(request.getLong("seq") + 1)
            replies.append(JSONObject().put("type", "ack").put("seq", request.getLong("seq"))
                .put("token", token).put("left", 0).put("right", 0).put("stopped", true)
                .put("parkingEnabled", false).put("parkingCurrent", 5))
        }
        override fun isConnected(): Boolean = connected && !disconnected
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = disconnected
        override fun disconnect() { disconnected = true }
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
