package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.DiagnosticTestSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real protocol over memory streams: no network, camera, motor, or board processes. */
@RunWith(AndroidJUnit4::class)
class FollowProtocolLifecycleTest {
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private val controls = mutableListOf<BoardFollowChannel>()
    private val runId = "c".repeat(32)

    @Before fun isolateLogs() {
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "follow-protocol-${UUID.randomUUID()}")
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreLogs() {
        controls.forEach { it.close("test_cleanup") }
        engine.flush()
        engineField.set(Diagnostics, originalEngine)
        engine.close()
        directory.deleteRecursively()
    }

    @Test fun readyHeartbeatDoesNotStartWorkerAndRequestsRotateExactlyFourFields() = runBlocking {
        val fake = FakeChannel(wire(ready(), ack(1, status("STOPPED", exited = true)),
            ack(2, status("STARTING")), ack(3, status("STOPPED", exited = true), terminal = true)))
        val control = open(fake)
        assertEquals("STOPPED", control.lastStatus?.runState)
        assertTrue(control.lastStatus?.processExited == true)
        assertTrue(control.lastReplyReceivedNanos > 0)
        assertFalse(control.heartbeat().terminal)
        assertEquals("STARTING", control.start().status.runState)
        assertTrue(control.requestStop().terminal)
        val requests = fake.requests()
        assertEquals(listOf("heartbeat", "start", "stop"), requests.map { it.getString("cmd") })
        requests.forEachIndexed { index, request ->
            assertEquals(setOf("seq", "token", "runId", "cmd"), request.keys().asSequence().toSet())
            assertEquals(index + 1, request.getInt("seq"))
            assertEquals(token(index), request.getString("token"))
            assertEquals(runId, request.getString("runId"))
        }
    }

    @Test fun startupCancelFaultAndFinalDrainContinueUntilTerminalThenExpectedEofKeepsFault() = runBlocking {
        val pending = status("FAULT", exited = true, fault = null).put("finalStatusPending", true)
        val fault = status("FAULT", fault = "关闭设备失败")
        val fake = FakeChannel(wire(ready(), ack(1, status("STARTING").put("startup", startup())),
            ack(2, fault), ack(3, pending), ack(4, status("STOPPED", exited = true), terminal = true)))
        val control = open(fake)
        assertEquals("camera", control.start().status.startup?.stage)
        val stopping = control.requestStop()
        assertFalse(stopping.terminal)
        assertFalse(stopping.status.processExited)
        val draining = control.heartbeat()
        assertTrue(draining.status.finalStatusPending)
        assertFalse(draining.terminal)
        assertEquals("关闭设备失败", draining.status.fault)
        val terminal = control.heartbeat()
        assertTrue(terminal.terminal)
        assertEquals("关闭设备失败", terminal.status.fault)
        val bytesSent = fake.sent.size()
        fake.eof = true
        assertEquals(terminal, control.heartbeat())
        assertEquals(terminal, control.requestStop())
        assertEquals(bytesSent, fake.sent.size())
        assertFalse(control.isOpen)
        assertFalse(events().any { it.optString("event") == "follow.eof" })
        val ackFields = events().filter { it.optString("event") == "follow.ack" }
            .map { it.getJSONObject("fields") }
        assertEquals("camera", ackFields.first().getString("startupStage"))
        assertTrue(ackFields[2].getBoolean("finalStatusPending"))
    }

    @Test fun minimalErrorEndsSessionWithoutDiscardingLastValidatedState() = runBlocking {
        val fake = FakeChannel(wire(ready(), ack(1, status("STARTING")),
            JSONObject().put("type", "error").put("error", "protocol rejected")))
        val control = open(fake)
        control.start()
        val lastValid = control.lastReplyReceivedNanos
        val failure = runCatching { control.heartbeat() }.exceptionOrNull()
        assertTrue(failure is FollowSessionException)
        assertNull((failure as FollowSessionException).status)
        assertEquals("STARTING", control.lastStatus?.runState)
        assertEquals(lastValid, control.lastReplyReceivedNanos)
        assertFalse(control.isOpen)
        assertNull(control.terminalReply)
    }

    @Test fun errorSnapshotIsPreservedButDoesNotBecomeTerminalOrFreshAck() = runBlocking {
        val error = JSONObject().put("type", "error").put("error", "lease expired")
            .put("terminal", true).put("status", status("FAULT", exited = true, fault = "关闭失败"))
        val fake = FakeChannel(wire(ready(), error))
        val control = open(fake)
        val lastValid = control.lastReplyReceivedNanos
        val failure = runCatching { control.start() }.exceptionOrNull() as FollowSessionException
        assertEquals("关闭失败", failure.status?.fault)
        assertEquals(failure.status, control.lastStatus)
        assertEquals(lastValid, control.lastReplyReceivedNanos)
        assertNull(control.terminalReply)
        assertFalse(control.isOpen)
        assertEquals("follow.session_error", engine.snapshot().failures.single().event)
    }

    @Test fun malformedOptionalErrorStatusDoesNotHideOriginalErrorOrLastState() = runBlocking {
        val error = JSONObject().put("type", "error").put("error", "original error")
            .put("status", JSONObject().put("secret", "must not serialize"))
        val control = open(FakeChannel(wire(ready(), error)))
        val failure = runCatching { control.heartbeat() }.exceptionOrNull() as FollowSessionException
        assertEquals("original error", failure.message)
        assertNull(failure.status)
        assertEquals("STOPPED", control.lastStatus?.runState)
        assertFalse(engine.snapshot().events.joinToString().contains("must not serialize"))
    }

    @Test fun terminalWhileFinalStatusIsPendingIsProtocolFailure() = runBlocking {
        val fake = FakeChannel(wire(ready(), ack(1,
            status("STOPPED", exited = true).put("finalStatusPending", true), terminal = true)))
        val control = open(fake)
        assertTrue(runCatching { control.heartbeat() }.exceptionOrNull() is IOException)
        assertNull(control.terminalReply)
        assertFalse(control.isOpen)
    }

    @Test fun closeBeforeStartSendsNoWorkerStartAndCompletesNormally() = runBlocking {
        val fake = FakeChannel(wire(ready(), ack(1, status("STOPPED", exited = true), terminal = true)))
        val control = open(fake)
        assertTrue(control.requestClose().terminal)
        assertEquals(listOf("close"), fake.requests().map { it.getString("cmd") })
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun cancellationAtCallerResumeCannotEraseAlreadyValidatedTerminal() = runBlocking {
        val fake = FakeChannel(wire(ready(), ack(1, status("STOPPED", exited = true), terminal = true)))
        val control = open(fake)
        // Hold the continuation at the same IO-to-UI dispatcher boundary as the real owner loop.
        val queued = ConcurrentLinkedQueue<Runnable>()
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val job = async(caller) { control.requestClose() }
        requireNotNull(queued.poll()).run()
        withTimeout(2_000) {
            while (control.terminalReply == null || queued.isEmpty()) delay(5)
        }
        job.cancel()
        while (true) (queued.poll() ?: break).run()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(control.terminalReply?.terminal == true)
        assertEquals("STOPPED", control.terminalReply?.status?.runState)
        assertEquals(listOf("close"), fake.requests().map { it.getString("cmd") })
        assertTrue(engine.snapshot().failures.isEmpty())
        assertFalse(events().any { it.optString("event") in setOf("follow.ack_timeout", "follow.transaction_failed") })
    }

    @Test fun fragmentedUtf8AndMultipleFramesKeepUnknownFieldsCompatible() = runBlocking {
        val reply = ack(1, status("STARTING").put("startup", startup().put("stage", "future_stage"))
            .put("reason", "加载中")).put("futureField", JSONObject().put("nested", true))
        val fake = FakeChannel(wire(ready(), reply), fragmented = true)
        val control = open(fake)
        assertEquals("future_stage", control.start().status.startup?.stage)
        assertEquals("加载中", control.lastStatus?.reason)
    }

    @Test fun invalidFramingNeverLeaksRawPayloadAndClosesChannel() = runBlocking {
        val privateText = "untrusted-${UUID.randomUUID()}"
        val valid = ack(1, status("RUNNING")).toString()
        val invalid = listOf(
            "$valid $privateText\n".toByteArray(),
            valid.replace("\"seq\":1", "\"seq\":1,\"seq\":2").plus("\n").toByteArray(),
            "{'type':'ack','private':'$privateText'}\n".toByteArray(),
            "{\"private\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}\n".toByteArray(),
            (" ".repeat(16 * 1024 + 1) + "$privateText\n").toByteArray(),
        )
        invalid.forEach { bytes ->
            val control = open(FakeChannel(wire(ready()) + bytes))
            val failure = runCatching { control.heartbeat() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(failure.toString().contains(privateText))
            assertFalse(control.isOpen)
        }
        assertFalse(engine.snapshot().events.joinToString().contains(privateText))
    }

    @Test fun wrongSequenceRunIdAndUnchangedTokenCannotAdvanceFreshness() = runBlocking {
        listOf(
            ack(1, status("RUNNING")).put("seq", 2),
            ack(1, status("RUNNING")).put("runId", "d".repeat(32)),
            ack(1, status("RUNNING")).put("token", token(0)),
        ).forEach { reply ->
            val control = open(FakeChannel(wire(ready(), reply)))
            val before = control.lastReplyReceivedNanos
            assertTrue(runCatching { control.start() }.exceptionOrNull() is IOException)
            assertEquals(before, control.lastReplyReceivedNanos)
            assertEquals("STOPPED", control.lastStatus?.runState)
        }
    }

    @Test fun errorAndEofTextRedactRegisteredSecretsBeforeUiReceivesThem() = runBlocking {
        val secret = "protected-${UUID.randomUUID()}"
        Diagnostics.protectSecret(secret)
        val error = JSONObject().put("type", "error").put("error", "failed: $secret token=another-value")
        val control = open(FakeChannel(wire(ready(), error)))
        val failure = runCatching { control.heartbeat() }.exceptionOrNull()
        assertFalse(failure?.message.orEmpty().contains(secret))
        assertFalse(failure?.message.orEmpty().contains("another-value"))
        val fake = FakeChannel(wire(ready()))
        val eofControl = open(fake)
        fake.errorOutput?.write("failed: $secret token=eof-value".toByteArray())
        fake.eof = true
        val eof = runCatching { eofControl.heartbeat() }.exceptionOrNull()
        assertFalse(eof?.message.orEmpty().contains(secret))
        assertFalse(eof?.message.orEmpty().contains("eof-value"))
    }

    @Test fun dryRunExecuteCombinationIsRejectedBeforeChannelOpen() = runBlocking {
        val fake = FakeChannel(wire(ready()))
        val failure = runCatching {
            BoardFollowChannel.open(DiagnosticTestSession(fake), FollowCamera.CONFIG, true, true,
                FollowLaunchOptions(), Diagnostics.newContext()) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(fake.connected)
        assertEquals(0, fake.sent.size())
    }

    private suspend fun open(fake: FakeChannel): BoardFollowChannel = BoardFollowChannel.open(
        DiagnosticTestSession(fake), FollowCamera.CONFIG, false, true, FollowLaunchOptions(),
        Diagnostics.newContext(),
    ) { _, _ -> }.also { controls.add(it) }

    private fun events() = engine.snapshot().events.map(::JSONObject)
    private fun token(index: Int) = index.toString(16).padStart(32, '0')
    private fun wire(vararg objects: JSONObject): ByteArray =
        objects.joinToString("", transform = { it.toString() + "\n" }).toByteArray()

    private fun ready() = JSONObject().put("type", "ready").put("runId", runId).put("token", token(0))
        .put("dryRun", true).put("execute", false).put("camera", "config")
        .put("heartbeatMs", 500).put("watchdogMs", 2000).put("status", status("STOPPED", exited = true))
        .put("options", JSONObject().put("targetDistanceM", JSONObject.NULL)
            .put("gesturesEnabled", false).put("resumeGesture", "Closed_Fist")
            .put("avoidance", "off").put("reverseEnabled", false).put("reverseDistanceM", JSONObject.NULL))

    private fun ack(seq: Int, status: JSONObject, terminal: Boolean = false) = JSONObject()
        .put("type", "ack").put("seq", seq).put("runId", runId).put("token", token(seq))
        .put("status", status).put("terminal", terminal)

    private fun status(runState: String, exited: Boolean = false, fault: String? = null) = JSONObject()
        .put("runState", runState).put("execute", false).put("camera", "config")
        .put("processExited", exited).put("fault", fault ?: JSONObject.NULL)
        .put("stop", JSONObject().put("requested", runState in setOf("STOPPING", "FAULT"))
            .put("acknowledged", JSONObject.NULL).put("physicalStopped", JSONObject.NULL))

    private fun startup() = JSONObject().put("stage", "camera").put("stageElapsedMs", 20)
        .put("totalElapsedMs", 100).put("stageTimeoutMs", 15_000).put("totalTimeoutMs", 30_000)
        .put("completed", false)

    private class FakeChannel(response: ByteArray, fragmented: Boolean = false) : ChannelExec() {
        private val data = ByteArrayInputStream(response)
        private var polls = 0
        private val replies = if (!fragmented) data else object : InputStream() {
            override fun available(): Int = if (++polls % 73 == 0) 0 else minOf(3, data.available())
            override fun read(): Int = data.read()
        }
        val sent = ByteArrayOutputStream()
        var errorOutput: OutputStream? = null
        @Volatile var connected = false
        @Volatile var eof = false
        @Volatile private var disconnected = false
        override fun connect(timeout: Int) { connected = true }
        override fun getInputStream(): InputStream = replies
        override fun getOutputStream(): OutputStream = sent
        override fun setErrStream(output: OutputStream, dontClose: Boolean) { errorOutput = output }
        override fun isConnected(): Boolean = connected && !disconnected
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = eof
        override fun disconnect() { disconnected = true }
        fun requests(): List<JSONObject> = sent.toString(Charsets.UTF_8.name()).lineSequence()
            .filter(String::isNotBlank).map(::JSONObject).toList()
    }
}
