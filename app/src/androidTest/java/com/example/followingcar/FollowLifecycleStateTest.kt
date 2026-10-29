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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as CoroutineChannel
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

/** Production VM/client/protocol, with isolated storage and memory-only SSH responses. */
@RunWith(AndroidJUnit4::class)
class FollowLifecycleStateTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private lateinit var application: IsolatedApplication
    private val stores = mutableListOf<ViewModelStore>()
    private val fixtures = mutableListOf<Fixture>()

    @Before fun isolateStorage() {
        val prefix = "follow-lifecycle-test-${UUID.randomUUID()}"
        directory = File(target.cacheDir, prefix).apply { mkdirs() }
        application = IsolatedApplication(target, prefix, File(directory, "no-backup"))
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        engine = DiagnosticEngine(DiagnosticFileStore(File(directory, "diagnostics")))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreStorage() = runBlocking {
        try {
            fixtures.forEach { fixture ->
                withContext(Dispatchers.Main) { fixture.vm.stopFollow("memory fixture cleanup") }
                fixture.bridge.finishForCleanup()
                withTimeout(5_000) { (field(fixture.vm, "followJob") as? Job)?.join() }
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

    @Test fun stoppingBeforeReadySendsCloseWithoutStartAndWaitsForTerminal() = runBlocking {
        val fixture = startFixture(holdReady = true)
        withTimeout(3_000) { fixture.bridge.connectedSignal.await() }
        withContext(Dispatchers.Main) { fixture.vm.stopFollow("cancel during ready") }
        fixture.bridge.releaseReady()
        val close = fixture.bridge.nextRequest()
        assertEquals("close", close.getString("cmd"))
        delay(60)
        assertTrue("Closing an unstarted bridge still requires terminal confirmation", fixture.vm.state.value.followActive)
        assertTrue(fixture.vm.state.value.followStopping)
        assertFalse(fixture.vm.state.value.canStartFollow)
        fixture.bridge.respond(status("STOPPED", exited = true), terminal = true)
        awaitFinished(fixture)
        val state = fixture.vm.state.value
        assertTrue(state.followTerminal)
        assertFalse(state.followUncertain)
        assertNull(state.followFirstFailure)
        assertEquals(listOf("close"), fixture.bridge.commands())
        assertTrue("Expected EOF after terminal must not invalidate SSH", state.connected)
    }

    @Test fun stopDuringStartAckWaitUsesNextTokenAndNeverSendsConcurrentRequest() = runBlocking {
        val fixture = startFixture()
        val start = fixture.bridge.nextRequest()
        assertEquals("start", start.getString("cmd"))
        withContext(Dispatchers.Main) {
            fixture.vm.stopFollow("first stop", ControlStopSource.USER_STOP)
            fixture.vm.onControlSurfaceHidden("later focus loss", ControlStopSource.WINDOW_FOCUS_LOST)
            fixture.vm.startFollow(dryRun = true)
        }
        delay(70)
        assertEquals("No stop may reuse the token of an in-flight start", 1, fixture.bridge.requests.size)
        fixture.bridge.respond(status("STARTING"))
        val stop = fixture.bridge.nextRequest()
        assertEquals("stop", stop.getString("cmd"))
        assertEquals(2L, stop.getLong("seq"))
        assertNotEquals(start.getString("token"), stop.getString("token"))
        fixture.bridge.respond(status("STOPPING", stop = true))
        assertEquals("heartbeat", fixture.bridge.nextRequest().getString("cmd"))
        fixture.bridge.respond(status("STOPPED", exited = true, stop = true), terminal = true)
        awaitFinished(fixture)
        assertEquals(1, fixture.bridge.commands().count { it == "start" })
        assertFalse(fixture.vm.state.value.followUncertain)
        val stopSending = engine.snapshot().events.map(::JSONObject)
            .single { it.optString("event") == "follow.stop_sending" }
        assertEquals(ControlStopSource.USER_STOP.name, stopSending.getString("source"))
    }

    @Test fun faultWithLiveWorkerAndPendingFinalStatusKeepHeartbeatAndControlLocked() = runBlocking {
        val fixture = startFixture()
        assertEquals("start", fixture.bridge.nextRequest().getString("cmd"))
        fixture.bridge.respond(status("FAULT", fault = "camera startup deadline", stop = true)
            .put("startup", startup("camera")))
        assertEquals("heartbeat", fixture.bridge.nextRequest().getString("cmd"))
        val liveFault = fixture.vm.state.value
        assertTrue(liveFault.followActive)
        assertFalse(liveFault.followTerminal)
        assertFalse(liveFault.canStartFollow)
        assertFalse(liveFault.canEnableDrive)
        assertFalse(requireNotNull(liveFault.followStatus).processExited)
        assertEquals("camera", liveFault.followStatus?.startup?.stage)

        fixture.bridge.respond(status("FAULT", exited = true, fault = "camera startup deadline", stop = true)
            .put("finalStatusPending", true))
        assertEquals("heartbeat", fixture.bridge.nextRequest().getString("cmd"))
        val pending = fixture.vm.state.value
        assertTrue(pending.followActive)
        assertFalse(pending.followTerminal)
        assertTrue(requireNotNull(pending.followStatus).finalStatusPending)
        assertFalse(pending.canStartFollow)
        assertFalse(followPresentation(pending).title.contains("已结束"))

        fixture.bridge.respond(status("FAULT", exited = true, fault = "camera startup deadline", stop = true), terminal = true)
        awaitFinished(fixture)
        assertTrue(fixture.vm.state.value.followTerminal)
        assertFalse(fixture.vm.state.value.followUncertain)
        assertEquals("camera startup deadline", fixture.vm.state.value.followStatus?.fault)
    }

    @Test fun laterSnapshotsAndSuccessfulExitCannotEraseFirstBoardFault() = runBlocking {
        val fixture = startFixture()
        fixture.bridge.nextRequest()
        fixture.bridge.respond(status("FAULT", fault = "original shutdown failure", stop = true))
        fixture.bridge.nextRequest()
        val first = requireNotNull(fixture.vm.state.value.followFirstFailure)
        fixture.bridge.respond(status("STOPPING", stop = true))
        fixture.bridge.nextRequest()
        assertEquals(first, fixture.vm.state.value.followFirstFailure)
        fixture.bridge.respond(status("STOPPED", exited = true, stop = true).put("exitCode", 0), terminal = true)
        awaitFinished(fixture)
        val ended = fixture.vm.state.value
        assertEquals(first, ended.followFirstFailure)
        assertEquals("original shutdown failure", ended.followStatus?.fault)
        assertTrue(ended.followTerminal)
        assertFalse(ended.followUncertain)
        assertNull(ended.followStatus?.physicalStopped)
        assertTrue(followPresentation(ended).title.contains("异常"))
    }

    @Test fun errorReplyRetainsItsStatusButDoesNotTreatItAsTerminalOrContinueHeartbeat() = runBlocking {
        val fixture = startFixture()
        fixture.bridge.nextRequest()
        fixture.bridge.respond(status("RUNNING"))
        fixture.bridge.nextRequest()
        fixture.bridge.failWithStatus(status("FAULT", fault = "worker close failed", stop = true))
        awaitFinished(fixture)
        val failed = fixture.vm.state.value
        assertTrue(failed.followUncertain)
        assertFalse(failed.followTerminal)
        assertFalse(failed.canStartFollow)
        assertEquals("FAULT", failed.followStatus?.runState)
        assertEquals("worker close failed", failed.followStatus?.fault)
        assertNotNull(failed.followFirstFailure)
        assertFalse(followPresentation(failed).fresh)
        assertEquals(listOf("start", "heartbeat"), fixture.bridge.commands())
    }

    @Test fun eofBeforeTerminalRetainsLastSnapshotOnlyAsUnknownDiagnostics() = runBlocking {
        val fixture = startFixture()
        fixture.bridge.nextRequest()
        fixture.bridge.respond(status("RUNNING")
            .put("target", JSONObject().put("trackId", 7).put("detected", true).put("ageMs", 0))
            .put("range", JSONObject().put("distanceM", 1.5).put("ageMs", 0)))
        fixture.bridge.nextRequest()
        fixture.bridge.endOutput()
        awaitFinished(fixture)
        val failed = fixture.vm.state.value
        assertTrue(failed.followUncertain)
        assertFalse(failed.followTerminal)
        assertEquals("RUNNING", failed.followStatus?.runState)
        assertEquals(1.5, requireNotNull(failed.followStatus?.distanceMeters), 0.0)
        assertFalse(followPresentation(failed).rangeFresh)
        assertFalse(followPresentation(failed).targetFresh)
        assertNotNull(failed.followFirstFailure)
        assertFalse(failed.canEnableDrive)
    }

    @Test fun closeFailureAfterCancelledReadyDoesNotPretendControlOwnershipReleased() = runBlocking {
        val fixture = startFixture(holdReady = true)
        withTimeout(3_000) { fixture.bridge.connectedSignal.await() }
        withContext(Dispatchers.Main) { fixture.vm.stopFollow("cancel opening") }
        fixture.bridge.releaseReady()
        assertEquals("close", fixture.bridge.nextRequest().getString("cmd"))
        fixture.bridge.endOutput()
        awaitFinished(fixture)
        val failed = fixture.vm.state.value
        assertTrue(failed.followUncertain)
        assertFalse(failed.followTerminal)
        assertFalse(failed.canStartFollow)
        assertEquals(listOf("close"), fixture.bridge.commands())
        assertNotNull(failed.followFirstFailure)
    }

    @Test fun realModePreflightRejectsOpenedHardwareBeforeVideoOrFollowSession() = runBlocking {
        val fixture = startPreflightFixture()
        withTimeout(3_000) { fixture.preflight.connectedSignal.await() }
        fixture.preflight.respond(JSONObject().put("chain", "continuous")
            .put("execute", true).put("hardware_opened", true))
        awaitFinished(fixture.control)
        assertEquals("Only the read-only preflight exec may be created", 1, fixture.opened.size)
        assertTrue(fixture.preflight.commandText.contains(" --execute"))
        assertTrue(fixture.preflight.commandText.contains(" --check-config"))
        assertFalse(fixture.preflight.commandText.contains(" --dry-run"))
        assertTrue(fixture.control.bridge.requests.isEmpty())
        val failed = fixture.control.vm.state.value
        assertFalse(failed.followUncertain) // A rejected preflight never acquired a control session.
        assertFalse(failed.followTerminal)
        assertNotNull(failed.followError)
        assertNotNull(failed.followFirstFailure)
        val events = engine.snapshot().events.map(::JSONObject)
        assertFalse(events.any { it.optString("event") in setOf("follow.start_sending", "follow.channel_open_requested") })
        assertFalse(events.any { it.optString("event") == "ssh.exec_started" &&
            it.optJSONObject("fields")?.optString("kind") == "video_stop" })
    }

    @Test fun userStopWhileSuccessfulRealModePreflightIsPendingCancelsBeforeOpeningAnything() = runBlocking {
        val fixture = startPreflightFixture()
        withTimeout(3_000) { fixture.preflight.connectedSignal.await() }
        withContext(Dispatchers.Main) { fixture.control.vm.stopFollow("cancel static preflight") }
        val configurationText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("follow_config/follow-config-config.txt").bufferedReader().use { it.readText() }
        fixture.preflight.respond(JSONObject(configurationText).put("execute", true))
        awaitFinished(fixture.control)
        val cancelled = fixture.control.vm.state.value
        assertEquals(true, cancelled.followConfiguration?.execute)
        assertEquals(1, fixture.opened.size)
        assertTrue(fixture.control.bridge.requests.isEmpty())
        assertFalse(cancelled.followUncertain)
        assertFalse(cancelled.followTerminal)
        assertNull(cancelled.followFirstFailure)
        assertNull(cancelled.followError)
        assertTrue(cancelled.followMessage.contains("取消"))
        assertTrue(engine.snapshot().events.map(::JSONObject).any { it.optString("event") == "follow.preflight_confirmed" })
    }

    private suspend fun startFixture(holdReady: Boolean = false): Fixture {
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also(stores::add)
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
        }
        withTimeout(3_000) { vm.state.first { !it.remoteProfileLoading } }
        val bridge = ScriptedFollowChannel(holdReady)
        val channels = ConcurrentLinkedQueue<ChannelExec>().apply { add(VideoStoppedChannel()); add(bridge) }
        val session = object : DiagnosticTestSession(bridge) {
            override fun openChannel(type: String): Channel {
                check(type == "exec")
                return channels.poll() ?: throw IOException("Unexpected memory fixture SSH exec")
            }
        }
        val socket = Socket() // Never connected: this suite cannot send network traffic.
        val fixture = Fixture(vm, bridge, socket).also(fixtures::add)
        withContext(Dispatchers.Main) {
            attachConnection(field(vm, "client") as BoardSshClient, session, socket)
            @Suppress("UNCHECKED_CAST")
            val flow = field(vm, "mutableState") as MutableStateFlow<BoardUiState>
            flow.value = flow.value.copy(connected = true, status = "memory fixture connected",
                remoteMode = false, followUncertain = false, followCamera = FollowCamera.CONFIG,
                followExecute = false, followLaunchOptions = FollowLaunchOptions(),
                pairingBusy = false, pairingUnconfirmed = false)
            assertTrue(vm.state.value.canStartFollow)
            vm.startFollow(dryRun = true)
        }
        return fixture
    }

    /** execute=true is only a string inspected by this in-memory channel, never a real command. */
    private suspend fun startPreflightFixture(): PreflightFixture {
        val vm = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also(stores::add)
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[BoardViewModel::class.java]
        }
        withTimeout(3_000) { vm.state.first { !it.remoteProfileLoading } }
        val preflight = DelayedPreflightChannel()
        val opened = CopyOnWriteArrayList<String>()
        val session = object : DiagnosticTestSession(preflight) {
            override fun openChannel(type: String): Channel {
                opened.add(type)
                check(type == "exec")
                if (opened.size != 1) throw IOException("Preflight test forbids video/follow channels")
                return preflight
            }
        }
        val socket = Socket()
        val control = Fixture(vm, ScriptedFollowChannel(true), socket).also(fixtures::add)
        withContext(Dispatchers.Main) {
            attachConnection(field(vm, "client") as BoardSshClient, session, socket)
            @Suppress("UNCHECKED_CAST")
            val flow = field(vm, "mutableState") as MutableStateFlow<BoardUiState>
            flow.value = flow.value.copy(connected = true, status = "memory preflight connected",
                remoteMode = false, followUncertain = false, followCamera = FollowCamera.CONFIG,
                followExecute = true, followLaunchOptions = FollowLaunchOptions(),
                pairingBusy = false, pairingUnconfirmed = false)
            assertTrue(vm.state.value.canStartFollow)
            vm.startFollow()
        }
        return PreflightFixture(control, preflight, opened)
    }

    private suspend fun awaitFinished(fixture: Fixture) {
        withTimeout(5_000) { (field(fixture.vm, "followJob") as Job).join() }
        assertFalse(fixture.vm.state.value.followActive)
        assertFalse(fixture.vm.state.value.followStopping)
    }

    private fun attachConnection(client: BoardSshClient, session: DiagnosticTestSession, socket: Socket) {
        val type = BoardSshClient::class.java.declaredClasses.single { it.simpleName == "Connection" }
        val context = DiagnosticContext("lifecycle-fixture-${UUID.randomUUID()}", "memory-connection")
        val connection = type.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(session, socket, context)
        type.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(connection, true)
        BoardSshClient::class.java.getDeclaredField("connection").apply { isAccessible = true }.set(client, connection)
    }

    private fun field(vm: BoardViewModel, name: String): Any? =
        BoardViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)

    private data class Fixture(val vm: BoardViewModel, val bridge: ScriptedFollowChannel, val socket: Socket)
    private data class PreflightFixture(val control: Fixture, val preflight: DelayedPreflightChannel,
        val opened: CopyOnWriteArrayList<String>)

    private class QueuedInput : InputStream() {
        private val bytes = ConcurrentLinkedQueue<Int>()
        fun append(value: JSONObject) { (value.toString() + "\n").toByteArray().forEach { bytes.add(it.toInt() and 255) } }
        override fun available(): Int = bytes.size
        override fun read(): Int = bytes.poll() ?: -1
    }

    private class ScriptedFollowChannel(private val holdReady: Boolean) : ChannelExec() {
        private val input = QueuedInput()
        private val buffer = ByteArrayOutputStream()
        private val lock = Any()
        private val incoming = CoroutineChannel<JSONObject>(CoroutineChannel.UNLIMITED)
        private var expectedSequence = 1L
        private var token = tokenFor(1)
        private var pending: JSONObject? = null
        private var readySent = false
        private var cleanup = false
        private val runId = UUID.randomUUID().toString().replace("-", "")
        @Volatile private var connected = false
        @Volatile private var closed = false
        @Volatile private var eof = false
        val requests = CopyOnWriteArrayList<JSONObject>()
        val connectedSignal = CompletableDeferred<Unit>()

        override fun setCommand(command: String) {
            check(command.contains("app_follow_bridge.py"))
            check(command.contains(" --dry-run") && !command.contains(" --execute"))
        }
        override fun connect(timeout: Int) {
            connected = true
            connectedSignal.complete(Unit)
            if (!holdReady) releaseReady()
        }
        fun releaseReady() = synchronized(lock) {
            if (readySent || closed) return@synchronized
            readySent = true
            input.append(JSONObject().put("type", "ready").put("runId", runId).put("token", token)
                .put("dryRun", true).put("execute", false).put("camera", "config")
                .put("heartbeatMs", 500).put("watchdogMs", 2000)
                .put("options", JSONObject().put("targetDistanceM", JSONObject.NULL)
                    .put("gesturesEnabled", false).put("resumeGesture", "Closed_Fist")
                    .put("avoidance", "off").put("reverseEnabled", false).put("reverseDistanceM", JSONObject.NULL))
                .put("status", status("STOPPED", exited = true)))
        }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { buffer.write(value) }
            override fun flush() = synchronized(lock) {
                check(pending == null) { "Concurrent in-flight request" }
                val request = JSONObject(buffer.toString(Charsets.UTF_8.name()).trim())
                buffer.reset()
                check(request.length() == 4)
                check(request.getLong("seq") == expectedSequence++)
                check(request.getString("token") == token)
                check(request.getString("runId") == runId)
                requests.add(request)
                pending = request
                check(incoming.trySend(request).isSuccess)
                if (cleanup) respond(status("STOPPED", exited = true, stop = true), true)
            }
        }
        suspend fun nextRequest(): JSONObject = withTimeout(3_000) { incoming.receive() }
        fun commands(): List<String> = requests.map { it.getString("cmd") }
        fun respond(snapshot: JSONObject, terminal: Boolean = false) = synchronized(lock) {
            val request = checkNotNull(pending) { "No request awaiting a response" }
            pending = null
            token = tokenFor(request.getLong("seq") + 1)
            input.append(JSONObject().put("type", "ack").put("seq", request.getLong("seq"))
                .put("runId", runId).put("token", token).put("status", snapshot).put("terminal", terminal))
            if (terminal) eof = true
        }
        fun failWithStatus(snapshot: JSONObject) = synchronized(lock) {
            checkNotNull(pending)
            pending = null
            input.append(JSONObject().put("type", "error").put("error", "bridge session failed")
                .put("status", snapshot).put("terminal", false))
            eof = true
        }
        fun endOutput() { eof = true }
        fun finishForCleanup() = synchronized(lock) {
            cleanup = true
            releaseReady()
            if (!closed && !eof && pending != null) respond(status("STOPPED", exited = true, stop = true), true)
        }
        override fun isConnected(): Boolean = connected && !closed
        override fun isClosed(): Boolean = closed
        override fun isEOF(): Boolean = eof
        override fun disconnect() { closed = true }
        companion object { private fun tokenFor(value: Long) = value.toString(16).padStart(32, '0') }
    }

    private class VideoStoppedChannel : ChannelExec() {
        private lateinit var sink: OutputStream
        @Volatile private var closed = false
        override fun setCommand(command: String) { check(command == videoServiceCommand(false)) }
        override fun setOutputStream(output: OutputStream, dontclose: Boolean) { sink = output }
        override fun connect(timeout: Int) {
            val unit = "loaded\tinactive\t0\t0\tempty"
            sink.write("VIDEO_SERVICE_V1\t$unit\t$unit\tnot-ready\n".toByteArray())
            closed = true
        }
        override fun isConnected(): Boolean = !closed
        override fun isClosed(): Boolean = closed
        override fun getExitStatus(): Int = if (closed) 0 else -1
        override fun disconnect() { closed = true }
    }

    private class DelayedPreflightChannel : ChannelExec() {
        private lateinit var sink: OutputStream
        @Volatile private var closed = false
        var commandText: String = ""
            private set
        val connectedSignal = CompletableDeferred<Unit>()
        override fun setCommand(command: String) {
            check(command.contains(" --check-config") && command.contains(" --execute"))
            check(!command.contains(" --dry-run"))
            commandText = command
        }
        override fun setOutputStream(output: OutputStream, dontclose: Boolean) { sink = output }
        override fun connect(timeout: Int) { connectedSignal.complete(Unit) }
        fun respond(configuration: JSONObject) {
            sink.write((configuration.toString() + "\n").toByteArray())
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

    companion object {
        private fun status(runState: String, exited: Boolean = false, fault: String? = null,
            stop: Boolean = false): JSONObject = JSONObject()
            .put("runState", runState).put("execute", false).put("camera", "config")
            .put("processExited", exited).put("finalStatusPending", false)
            .put("statusAgeMs", 0).put("fault", fault ?: JSONObject.NULL)
            .put("stop", JSONObject().put("requested", stop).put("acknowledged", JSONObject.NULL)
                .put("physicalStopped", JSONObject.NULL))

        private fun startup(stage: String): JSONObject = JSONObject().put("stage", stage)
            .put("stageElapsedMs", 15_000).put("totalElapsedMs", 20_000)
            .put("stageTimeoutMs", 15_000).put("totalTimeoutMs", 30_000).put("completed", false)
    }
}
