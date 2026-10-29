package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.DiagnosticTestSession
import com.jcraft.jsch.JSchException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real channel state machine with in-memory streams; never opens SSH or motors. */
@RunWith(AndroidJUnit4::class)
class BoardTransportDiagnosticsTest {
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var testEngine: DiagnosticEngine
    private lateinit var testDirectory: File
    private val controls = mutableListOf<BoardFollowChannel>()

    @Before fun isolateDiagnosticStorage() {
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        testDirectory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "transport-diagnostics-test-${UUID.randomUUID()}")
        // Empty callbacks keep the application's visible failure history and status untouched.
        testEngine = DiagnosticEngine(DiagnosticFileStore(testDirectory))
        engineField.set(Diagnostics, testEngine)
    }

    @After fun restoreDiagnosticStorage() {
        try {
            controls.forEach { it.close("test_cleanup") }
            if (::testEngine.isInitialized) testEngine.flush()
        } finally {
            engineField.set(Diagnostics, originalEngine)
            if (::testEngine.isInitialized) testEngine.close()
            if (::testDirectory.isInitialized) testDirectory.deleteRecursively()
        }
    }

    @Test fun ackTimeoutKeepsFirstFailureAfterRecoveryCheckFails() = runBlocking {
        val context = testContext()
        val aborts = AtomicInteger()
        val control = control(context, abort = { aborts.incrementAndGet() })

        val failure = runCatching { control.heartbeat() }.exceptionOrNull()
        assertTrue(failure is SocketTimeoutException)
        val first = testEngine.snapshot().failures.single { it.context.operationId == context.operationId }
        assertEquals("follow.ack_timeout", first.event)

        Diagnostics.failure(context, "recovery.check_failed", IOException("channel is not opened."))
        val after = testEngine.snapshot()
        assertEquals(first, after.failures.single { it.context.operationId == context.operationId })
        assertEquals(0, aborts.get()) // Waiting for an ACK only closes the owned channel.
        val records = events(context)
        assertEquals("ack_wait", record(records, "follow.ack_timeout").getJSONObject("fields").getString("phase"))
        assertEquals("ack_timeout", record(records, "follow.channel_close").getString("source"))
        assertFalse(record(records, "recovery.check_failed").getJSONObject("fields").getBoolean("firstFailure"))
        assertTrue(records.indexOfFirst { it.getString("event") == "follow.ack_timeout" } <
            records.indexOfFirst { it.getString("event") == "follow.channel_close" })
    }

    @Test fun eofIsReportedBeforeCleanupWithoutBeingClassifiedAsTimeout() = runBlocking {
        val context = testContext()
        val control = control(context, fake = FakeChannel(eof = true))
        assertTrue(runCatching { control.heartbeat() }.exceptionOrNull() is IOException)

        val records = events(context)
        assertTrue(record(records, "follow.eof").getJSONObject("fields").getBoolean("channelEof"))
        assertEquals("ack_wait", record(records, "follow.transaction_failed").getJSONObject("fields").getString("phase"))
        assertFalse(records.any { it.getString("event") == "follow.ack_timeout" })
        assertEquals("follow.transaction_failed", testEngine.snapshot().failures.single().event)
    }

    @Test fun invalidJsonIsDistinctFromEofAndDoesNotLeakProtocolText() = runBlocking {
        val context = testContext()
        val privateText = "private-wire-payload-${UUID.randomUUID()}"
        val control = control(context, input = ByteArrayInputStream("not-json-$privateText\n".toByteArray()))
        val failure = runCatching { control.heartbeat() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure.toString().contains(privateText))

        val records = events(context)
        assertEquals("invalid_json", record(records, "follow.protocol_error").getJSONObject("fields").getString("protocolStage"))
        assertFalse(records.any { it.getString("event") in setOf("follow.eof", "follow.ack_timeout") })
        assertFalse(testEngine.snapshot().events.joinToString().contains(privateText))
        assertFalse(testEngine.snapshot().failures.toString().contains(privateText))
    }

    @Test fun wrongAckSequenceIdentifiesValidationPhaseAndLastSuccessfulAck() = runBlocking {
        val context = testContext()
        val response = JSONObject().put("type", "ack").put("seq", 999)
            .put("runId", context.runId).put("token", "2".repeat(32)).toString() + "\n"
        val control = control(context, input = ByteArrayInputStream(response.toByteArray()))
        assertTrue(runCatching { control.heartbeat() }.exceptionOrNull() is IOException)

        val records = events(context)
        val fields = record(records, "follow.transaction_failed").getJSONObject("fields")
        assertEquals("ack_validate", fields.getString("phase"))
        assertEquals(1L, fields.getLong("seq"))
        assertEquals(0L, fields.getLong("lastAckSeq"))
        assertEquals("validation", record(records, "follow.protocol_error").getJSONObject("fields").getString("protocolStage"))
        assertFalse(testEngine.snapshot().events.joinToString().contains("2".repeat(32)))
    }

    @Test fun cancellingBlockedWriteAbortsTransportAndRecordsOriginalPhase() = runBlocking {
        val context = testContext()
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val aborts = AtomicInteger()
        val output = object : OutputStream() {
            override fun write(value: Int) = blockWrite()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = blockWrite()
            private fun blockWrite() {
                entered.complete(Unit)
                if (!released.await(5, TimeUnit.SECONDS)) throw IOException("test write was not cancelled")
            }
        }
        val control = control(context, output = output, abort = {
            aborts.incrementAndGet()
            released.countDown()
        })
        val request = launch(Dispatchers.IO) { control.heartbeat() }
        try {
            withTimeout(3_000) { entered.await() }
            withTimeout(3_000) { request.cancelAndJoin() }
        } finally {
            released.countDown()
            request.cancelAndJoin()
        }

        assertTrue(aborts.get() > 0)
        assertFalse(control.isOpen)
        val records = events(context)
        val cancelled = record(records, "follow.blocking_io_cancelled").getJSONObject("fields")
        assertEquals("write", cancelled.getString("phase"))
        assertEquals("transport", cancelled.getString("closeScope"))
        assertEquals("blocking_io_cancelled", record(records, "follow.channel_close").getString("source"))
        assertFalse(records.any { it.getString("event") == "follow.ack" })
    }

    @Test fun repeatedCloseKeepsEarliestCloseSource() {
        val context = testContext()
        val control = control(context)
        control.close("focus_lost")
        control.close("cleanup")
        val closes = events(context).filter { it.getString("event") == "follow.channel_close" }
        assertEquals(1, closes.size)
        assertEquals("focus_lost", closes.single().getString("source"))
    }

    @Test fun channelOpenFailureRetainsOriginalErrorAndClosesOnlyOwnedChannel() = runBlocking {
        val context = testContext()
        val fake = OpenFailureChannel()
        val session = DiagnosticTestSession(fake)
        val aborts = AtomicInteger()
        val failure = runCatching {
            BoardFollowChannel.open(session, FollowCamera.CONFIG, false, true, FollowLaunchOptions(), context) {
                aborts.incrementAndGet()
            }
        }.exceptionOrNull()

        assertTrue(failure is JSchException)
        assertEquals("channel is not opened.", failure?.message)
        val records = events(context)
        val fields = record(records, "follow.open_failed").getJSONObject("fields")
        assertEquals("channel_open", fields.getString("phase"))
        assertEquals(JSchException::class.java.name, fields.getString("originalExceptionClass"))
        assertEquals("channel", record(records, "follow.channel_close").getJSONObject("fields").getString("closeScope"))
        assertEquals(0, aborts.get())
        assertTrue(session.isConnected)
        Diagnostics.failure(context, "recovery.check_failed", IOException("later inspection failed"))
        assertEquals("follow.open_failed", testEngine.snapshot().failures.single().event)
    }

    @Test fun execChannelOpenFailureClosesTransportAndReportsDisconnected() = runBlocking {
        val context = testContext()
        val session = DiagnosticTestSession(OpenFailureChannel())
        val socket = Socket()
        val client = BoardSshClient()
        val connectionClass = BoardSshClient::class.java.declaredClasses.single { it.simpleName == "Connection" }
        val constructor = connectionClass.declaredConstructors.single { it.parameterCount == 3 }
            .apply { isAccessible = true }
        val connection = constructor.newInstance(session, socket, context)
        connectionClass.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(connection, true)
        BoardSshClient::class.java.getDeclaredField("connection").apply { isAccessible = true }.set(client, connection)
        assertTrue(client.isConnected)

        try {
            val failure = runCatching {
                client.execute("synthetic-test-command", kind = "follow_availability", diagnostic = context)
            }.exceptionOrNull()
            assertTrue(failure is JSchException)
            assertFalse(client.isConnected)
            assertTrue(socket.isClosed)
            val records = events(context)
            assertEquals("channel_open", record(records, "ssh.exec_failed").getJSONObject("fields").getString("phase"))
            assertEquals("exec_failed", record(records, "ssh.transport_close").getString("source"))
            assertFalse(testEngine.snapshot().events.joinToString().contains("synthetic-test-command"))
        } finally {
            client.disconnect("test_cleanup", context)
            socket.close()
        }
    }

    @Test fun saturatedSlowAndFailingLogStorageCannotDelayEmergencyAck() = runBlocking {
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val failWrites = AtomicBoolean(false)
        val failedWrites = AtomicInteger()
        val status = AtomicReference<DiagnosticStatus>()
        val backing = DiagnosticFileStore(testDirectory)
        val storage = object : DiagnosticStorage by backing {
            override fun append(line: String) {
                writerEntered.countDown()
                if (!releaseWriter.await(10, TimeUnit.SECONDS)) throw IOException("synthetic slow disk timed out")
                if (failWrites.get()) {
                    failedWrites.incrementAndGet()
                    throw IOException("synthetic disk write failure")
                }
                backing.append(line)
            }
        }
        testEngine.flush()
        testEngine.close()
        testEngine = DiagnosticEngine(storage, capacity = 8, onStatus = status::set)
        engineField.set(Diagnostics, testEngine)

        suspend fun emergencyStop(context: DiagnosticContext) {
            val output = ByteArrayOutputStream()
            val response = JSONObject().put("type", "ack").put("seq", 1)
                .put("token", "2".repeat(32)).put("left", 0).put("right", 0)
                .put("stopped", true).toString() + "\n"
            val constructor = BoardDriveChannel::class.java.declaredConstructors.single { it.parameterCount == 6 }
                .apply { isAccessible = true }
            val aborts = AtomicInteger()
            val abort: () -> Unit = { aborts.incrementAndGet() }
            val channel = constructor.newInstance(FakeChannel(), ByteArrayInputStream(response.toByteArray()),
                output, BoundedOutputStream(4096), abort, context) as BoardDriveChannel
            BoardDriveChannel::class.java.getDeclaredField("token").apply { isAccessible = true }
                .set(channel, "1".repeat(32))
            BoardDriveChannel::class.java.getDeclaredField("ready").apply { isAccessible = true }
                .set(channel, DriveReady("synthetic-test", true, 600, 60))
            try {
                val started = System.nanoTime()
                // This is the production terminal ACK deadline, not an extended test allowance.
                val ack = withTimeout(2_500L) { channel.exchange(0, 0, emergency = true) }
                assertTrue("logging delayed emergency ACK", elapsedMillis(started) < 2_500L)
                assertTrue(ack.stopped)
                assertEquals(0, ack.left)
                assertEquals(0, ack.right)
                assertFalse(channel.isOpen)
                assertEquals(0, aborts.get())
                val sent = JSONObject(output.toString(Charsets.UTF_8.name()).trim())
                assertEquals("estop", sent.getString("cmd"))
                assertEquals(0, sent.getInt("left"))
                assertEquals(0, sent.getInt("right"))
            } finally {
                channel.close("test_cleanup")
            }
        }

        try {
            Diagnostics.event("test.block_writer")
            assertTrue("writer did not enter simulated slow disk", writerEntered.await(2, TimeUnit.SECONDS))
            repeat(64) { Diagnostics.event("test.sample", fields = mapOf("sampleNumber" to it)) }
            assertTrue("queue was not saturated", status.get().droppedEvents > 0)
            val slowContext = testContext()
            emergencyStop(slowContext)
            assertEquals("writer must stay blocked throughout emergency stop", 1L, releaseWriter.count)

            releaseWriter.countDown()
            assertTrue(testEngine.flush())
            val recorded = events(slowContext)
            assertTrue(record(recorded, "drive.ack_summary").getJSONObject("fields").getBoolean("stopped"))
            assertEquals("emergency_ack", record(recorded, "drive.channel_close").getString("source"))

            failWrites.set(true)
            Diagnostics.event("test.disk_failure", critical = true)
            assertTrue(testEngine.flush())
            assertTrue(failedWrites.get() > 0)
            assertFalse(status.get().available)
            emergencyStop(testContext())
            assertTrue(testEngine.flush())
            assertFalse(testEngine.snapshot().status.available)
        } finally {
            releaseWriter.countDown()
            // Release the artificial failure before @After flushes and restores the user's sink.
            failWrites.set(false)
        }
    }

    private fun testContext() = DiagnosticContext("test-operation-${UUID.randomUUID()}",
        "test-connection-${UUID.randomUUID()}", "test-run-${UUID.randomUUID()}")

    private fun control(
        context: DiagnosticContext,
        input: InputStream = ByteArrayInputStream(byteArrayOf()),
        output: OutputStream = ByteArrayOutputStream(),
        fake: FakeChannel = FakeChannel(),
        abort: () -> Unit = {},
    ): BoardFollowChannel {
        val constructor = BoardFollowChannel::class.java.declaredConstructors.single { it.parameterCount == 6 }
            .apply { isAccessible = true }
        val channel = constructor.newInstance(fake, input, output, BoundedOutputStream(4096), abort, context) as BoardFollowChannel
        BoardFollowChannel::class.java.getDeclaredField("token").apply { isAccessible = true }
            .set(channel, "1".repeat(32))
        BoardFollowChannel::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .set(channel, FollowReady(requireNotNull(context.runId), true, false, FollowCamera.CONFIG))
        controls.add(channel)
        return channel
    }

    private fun events(context: DiagnosticContext): List<JSONObject> = testEngine.snapshot().events
        .map(::JSONObject).filter { it.optString("operationId") == context.operationId }

    private fun record(records: List<JSONObject>, event: String): JSONObject =
        requireNotNull(records.firstOrNull { it.getString("event") == event }) { "Missing event $event" }

    private class FakeChannel(private val eof: Boolean = false) : ChannelExec() {
        @Volatile private var disconnected = false
        override fun isConnected(): Boolean = !disconnected
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = eof
        override fun disconnect() { disconnected = true }
    }

    private class OpenFailureChannel : ChannelExec() {
        @Volatile private var disconnected = false
        override fun connect(timeout: Int) { throw JSchException("channel is not opened.") }
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun isConnected(): Boolean = false
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = false
        override fun disconnect() { disconnected = true }
    }
}
