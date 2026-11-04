package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.DiagnosticTestSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real channel parsing and timeout clocks, with in-memory streams only; no network or motors. */
@RunWith(AndroidJUnit4::class)
class DriveAckRecoveryProtocolTest {
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private val controls = mutableListOf<BoardDriveChannel>()
    private val clients = mutableListOf<Pair<BoardSshClient, Socket>>()

    @Before fun isolateDiagnostics() {
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "drive-ack-recovery-${UUID.randomUUID()}")
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreDiagnostics() {
        try {
            controls.forEach { it.close("ack_recovery_test_cleanup") }
            clients.forEach { (client, socket) -> client.disconnect("test_cleanup"); socket.close() }
            engine.flush()
        } finally {
            engineField.set(Diagnostics, originalEngine)
            engine.close()
            directory.deleteRecursively()
        }
    }

    @Test fun partialAckAfterThreeSecondsRetainsItsBytesAndAdvancesSequenceAndTokenExactlyOnce() = runBlocking {
        val memory = RecoveryMemoryChannel(ready())
        val control = open(memory)
        val delayed = AtomicInteger()
        control.configureAckDelayHandler { elapsed ->
            assertTrue(elapsed >= 1_200)
            delayed.incrementAndGet()
        }
        val producer = launch(Dispatchers.Default) {
            memory.awaitRequests(1)
            val response = ack(1, recovery = true).toString() + "\n"
            // An unfinished JSON line already exists when the soft warning fires. Restarting
            // the reader at that point would lose this prefix and fail the completed response.
            memory.feed(response.substring(0, response.length / 2))
            delay(3_000)
            memory.feed(response.substring(response.length / 2))
        }
        try {
            val recovered = withTimeout(7_000) { control.exchange(30, -30) }
            assertTrue(recovered.communicationDelayed)
            assertTrue(recovered.recoveryRequired && recovered.targetStopped)
            assertEquals(1, delayed.get())
            assertEquals("A soft warning must not resend the outstanding request", 1, memory.requests.size)

            memory.feed(ack(2, recovery = true))
            val neutral = control.exchange(0, 0)
            assertTrue(neutral.recoveryRequired)
            assertFalse(neutral.communicationDelayed)
            memory.feed(ack(3, left = 30, right = -30))
            val fresh = control.exchange(30, -30)
            assertEquals(30, fresh.left)
            assertEquals(-30, fresh.right)
            assertFalse(fresh.communicationDelayed)
            assertTrue(control.isOpen)
            val sent = memory.requests
            assertEquals(listOf("drive", "stop", "drive"), sent.map { it.getString("cmd") })
            assertEquals(listOf(1, 2, 3), sent.map { it.getInt("seq") })
            assertEquals(listOf(token(1), token(2), token(3)), sent.map { it.getString("token") })
            assertEquals(1, events().count { it.optString("event") == "drive.ack_delayed" })
            assertEquals(1, events().count { it.optString("event") == "drive.ack_delay_recovered" })
            assertTrue("Soft delay and recovery are not first faults", engine.snapshot().failures.isEmpty())
        } finally { producer.cancelAndJoin() }
    }

    @Test fun previousFiveSecondGraceAcceptsAckLaterThanTheOldTwoPointFiveSecondLimit() = runBlocking {
        val memory = RecoveryMemoryChannel(ready(grace = 5_000))
        val control = open(memory)
        assertEquals(4_250L, driveAckTimeoutMillis(control.ready.networkGraceMs))
        val delayed = AtomicInteger()
        control.configureAckDelayHandler { delayed.incrementAndGet() }
        val producer = launch(Dispatchers.Default) {
            memory.awaitRequests(1)
            delay(2_800)
            memory.feed(ack(1))
        }
        try {
            val reply = withTimeout(6_000) { control.exchange(0, 0) }
            assertTrue(reply.communicationDelayed)
            assertTrue(reply.stopped)
            assertTrue(control.isOpen)
            assertEquals(1, delayed.get())
            assertEquals(1, memory.requests.size)
            assertTrue(engine.snapshot().failures.isEmpty())
        } finally { producer.cancelAndJoin() }
    }

    @Test fun finalEightSecondAckTimeoutClosesOnlyOwnedChannelAndPreservesSshTransport() = runBlocking {
        val context = Diagnostics.newContext()
        val memory = RecoveryMemoryChannel(ready())
        val client = BoardSshClient()
        val socket = Socket()
        attachConnection(client, DiagnosticTestSession(memory), socket, context)
        clients.add(client to socket)
        val control = client.openDriveChannel(dryRun = true, diagnostic = context).also(controls::add)
        assertEquals(8_000L, driveAckTimeoutMillis(control.ready.networkGraceMs))
        val delayed = AtomicInteger()
        control.configureAckDelayHandler { delayed.incrementAndGet() }
        val started = System.nanoTime()
        val failure = runCatching { withTimeout(11_000) { control.exchange(0, 0) } }.exceptionOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("Expected the channel ACK deadline, received $failure", failure is SocketTimeoutException)
        assertTrue("Hard ACK deadline must remain bounded: $elapsed ms", elapsed in 7_800L..10_500L)
        assertEquals(1, delayed.get())
        assertEquals(1, memory.requests.size)
        assertFalse(control.isOpen)
        assertTrue(client.isConnected)
        assertFalse(socket.isClosed)
        assertFalse(events().any { it.optString("event") == "ssh.transport_abort" })
        val closed = events().first { it.optString("event") == "drive.channel_close" }
        assertEquals("channel", closed.getJSONObject("fields").getString("closeScope"))
        assertEquals("drive.ack_timeout", engine.snapshot().failures.single().event)
    }

    @Test fun blockedWriteHasItsOwnTwoPointFiveSecondDeadlineAndNeverWaitsForEightSecondAckBudget() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborts = AtomicInteger()
        val memory = RecoveryMemoryChannel(ready(), beforeWrite = {
            entered.countDown()
            if (!released.await(7, TimeUnit.SECONDS)) throw IOException("Synthetic write deadline did not interrupt")
        })
        val control = open(memory) { aborts.incrementAndGet(); released.countDown() }
        val delayed = AtomicInteger()
        control.configureAckDelayHandler { delayed.incrementAndGet() }
        try {
            val started = System.nanoTime()
            // No caller cancellation: only the production write deadline can release the latch.
            val failure = runCatching { withTimeout(6_000) { control.exchange(30, -30) } }.exceptionOrNull()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertEquals(0L, entered.count)
            assertTrue("Expected the write deadline, received $failure", failure is SocketTimeoutException)
            assertTrue("Writing must retain its 2500 ms bound: $elapsed ms", elapsed in 2_300L..4_500L)
            assertEquals(1, aborts.get())
            assertEquals(0, delayed.get())
            assertFalse(control.isOpen)
            assertTrue(events().any { it.optString("event") == "drive.blocking_io_cancelled" })
            assertFalse(events().any { it.optString("event") == "drive.ack_delayed" })
            val closed = events().first { it.optString("event") == "drive.channel_close" }
            assertEquals("transport", closed.getJSONObject("fields").getString("closeScope"))
            assertEquals("write", closed.getJSONObject("fields").getString("phase"))
        } finally { released.countDown() }
    }

    @Test fun cancellingAnActuallyBlockedWriteStillAbortsItsTransportWithoutSoftAckWarning() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborts = AtomicInteger()
        val memory = RecoveryMemoryChannel(ready(), beforeWrite = {
            entered.countDown()
            if (!released.await(6, TimeUnit.SECONDS)) throw IOException("Synthetic write was not interrupted")
        })
        val control = open(memory) { aborts.incrementAndGet(); released.countDown() }
        val delayed = AtomicInteger()
        control.configureAckDelayHandler { delayed.incrementAndGet() }
        val task = launch(Dispatchers.IO) { control.exchange(30, -30) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(1_500) { task.cancelAndJoin() }
            assertEquals(1, aborts.get())
            assertEquals(0, delayed.get())
            assertFalse(control.isOpen)
            assertTrue(events().any { it.optString("event") == "drive.blocking_io_cancelled" })
            assertFalse(events().any { it.optString("event") == "drive.ack_delayed" })
        } finally { released.countDown(); task.cancelAndJoin() }
    }

    @Test fun explicitStopInterruptsSoftAckWaitingPromptlyWithoutWaitingForTheHardDeadline() = runBlocking {
        val memory = RecoveryMemoryChannel(ready())
        val aborts = AtomicInteger()
        val control = open(memory) { aborts.incrementAndGet() }
        val delayed = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Result<DriveAck>>()
        control.configureAckDelayHandler { delayed.complete(Unit) }
        val task = launch(Dispatchers.IO) { result.complete(runCatching { control.exchange(30, -30) }) }
        try {
            withTimeout(3_000) { delayed.await() }
            val started = System.nanoTime()
            assertTrue(control.interruptForActiveStop(ControlStopSource.USER_STOP))
            val failure = withTimeout(700) { result.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 700)
            assertEquals(0, aborts.get())
            assertFalse(control.isOpen)
            assertEquals(1, memory.requests.size)
            assertTrue(engine.snapshot().failures.isEmpty())
        } finally { task.cancelAndJoin() }
    }

    @Test fun aLateAckWithWrongSequenceRemainsAProtocolFailureInsteadOfRecovering() = runBlocking {
        val memory = RecoveryMemoryChannel(ready())
        val control = open(memory)
        val delayed = CompletableDeferred<Unit>()
        control.configureAckDelayHandler { delayed.complete(Unit) }
        val producer = launch(Dispatchers.Default) {
            withTimeout(3_000) { delayed.await() }
            memory.feed(ack(2))
        }
        try {
            val failure = runCatching { withTimeout(5_000) { control.exchange(0, 0) } }.exceptionOrNull()
            assertTrue(failure is IOException && failure !is SocketTimeoutException)
            assertFalse(control.isOpen)
            assertEquals(1, memory.requests.size)
            assertEquals("drive.transaction_failed", engine.snapshot().failures.single().event)
            assertFalse(events().any { it.optString("event") == "drive.ack_delay_recovered" })
        } finally { producer.cancelAndJoin() }
    }

    @Test fun eofDuringSoftWaitIsReportedImmediatelyAndNeverTreatedAsRecoveredAck() = runBlocking {
        val memory = RecoveryMemoryChannel(ready())
        val control = open(memory)
        val delayed = CompletableDeferred<Unit>()
        control.configureAckDelayHandler { delayed.complete(Unit) }
        val producer = launch(Dispatchers.Default) {
            withTimeout(3_000) { delayed.await() }
            memory.eof = true
        }
        try {
            val started = System.nanoTime()
            val failure = runCatching { withTimeout(4_000) { control.exchange(0, 0) } }.exceptionOrNull()
            assertTrue(failure is IOException && failure !is SocketTimeoutException)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_500)
            assertFalse(control.isOpen)
            assertTrue(events().any { it.optString("event") == "drive.eof" })
            assertFalse(events().any { it.optString("event") == "drive.ack_delay_recovered" })
        } finally { producer.cancelAndJoin() }
    }

    private suspend fun open(memory: RecoveryMemoryChannel, abort: () -> Unit = {
        fail("An ACK-read/protocol failure must not abort the unrelated SSH transport")
    }): BoardDriveChannel = BoardDriveChannel.open(DiagnosticTestSession(memory), true,
        Diagnostics.newContext()) { _, _ -> abort() }.also(controls::add)

    private fun events(): List<JSONObject> = engine.snapshot().events.map(::JSONObject)

    private fun attachConnection(client: BoardSshClient, session: DiagnosticTestSession,
        socket: Socket, context: DiagnosticContext) {
        val type = BoardSshClient::class.java.declaredClasses.single { it.simpleName == "Connection" }
        val constructor = type.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
        val connection = constructor.newInstance(session, socket, context)
        type.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(connection, true)
        BoardSshClient::class.java.getDeclaredField("connection").apply { isAccessible = true }.set(client, connection)
    }

    private fun ready(grace: Int = 15_000): JSONObject = JSONObject().put("type", "ready")
        .put("token", token(1)).put("dryRun", true).put("port", "memory-only")
        .put("watchdogMs", 600).put("maxRpm", 100).put("networkGraceMs", grace).withDriveSafetyFixture()

    private fun ack(seq: Int, left: Int = 0, right: Int = 0, recovery: Boolean = false): JSONObject =
        JSONObject().put("type", "ack").put("seq", seq).put("token", token(seq + 1))
            .put("left", left).put("right", right).put("stopped", left == 0 && right == 0)
            .put("recoveryRequired", recovery).withDriveSafetyFixture()

    private fun token(seq: Int): String = seq.toString(16).padStart(32, '0')

    private class RecoveryMemoryChannel(ready: JSONObject, private val beforeWrite: (() -> Unit)? = null) : ChannelExec() {
        private val incoming = ConcurrentLinkedQueue<Int>()
        private val sent = ByteArrayOutputStream()
        private val input = object : InputStream() {
            override fun available(): Int = incoming.size
            override fun read(): Int = incoming.poll() ?: -1
        }
        private val output = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                beforeWrite?.invoke()
                synchronized(sent) { sent.write(bytes, offset, length) }
            }
        }
        @Volatile private var connected = false
        @Volatile private var disconnected = false
        @Volatile var eof = false
        init { feed(ready) }
        val requests: List<JSONObject> get() = synchronized(sent) {
            sent.toString(Charsets.UTF_8.name()).lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
        }
        fun feed(message: JSONObject) = feed(message.toString() + "\n")
        fun feed(text: String) { text.toByteArray(Charsets.UTF_8).forEach { incoming.add(it.toInt() and 0xff) } }
        suspend fun awaitRequests(count: Int) = withTimeout(3_000) { while (requests.size < count) delay(5) }
        override fun setCommand(command: String) { check(command == driveBridgeCommand(dryRun = true)) }
        override fun connect(timeout: Int) { connected = true }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun isConnected(): Boolean = connected && !disconnected
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = eof
        override fun disconnect() { disconnected = true }
    }
}
