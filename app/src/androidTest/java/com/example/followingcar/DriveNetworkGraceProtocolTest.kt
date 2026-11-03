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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Drive channel parsing with memory streams only. No SSH socket, camera or serial device. */
@RunWith(AndroidJUnit4::class)
class DriveNetworkGraceProtocolTest {
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private val channels = mutableListOf<BoardDriveChannel>()

    @Before fun isolateDiagnostics() {
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "drive-grace-protocol-${UUID.randomUUID()}")
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreDiagnostics() {
        try { channels.forEach { it.close("grace_protocol_test_cleanup") }; engine.flush() }
        finally {
            engineField.set(Diagnostics, originalEngine)
            engine.close()
            directory.deleteRecursively()
        }
    }

    @Test fun negotiatedRecoverySuppressesMotionAndNeutralPrecedesFreshMovement() = runBlocking {
        val control = open(ready(), ack(recovery = true), ack(seq = 2, recovery = true),
            ack(seq = 3, left = 30, right = -30, recovery = false))
        assertEquals(5_000, control.ready.networkGraceMs)
        assertEquals(2_500L, driveAckTimeoutMillis(control.ready.networkGraceMs))
        val blocked = control.exchange(30, -30)
        assertTrue(blocked.recoveryRequired)
        assertTrue(blocked.stopped)
        assertEquals(0, blocked.left)
        assertEquals(0, blocked.right)
        assertNull("Network recovery is independent of infrared support", blocked.interlock)
        val neutral = control.exchange(0, 0)
        assertTrue(neutral.recoveryRequired)
        assertTrue(neutral.stopped)
        val fresh = control.exchange(30, -30)
        assertFalse(fresh.recoveryRequired)
        assertFalse(fresh.stopped)
        assertEquals(30, fresh.left)
        assertEquals(-30, fresh.right)
        assertTrue(control.isOpen)
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun unnegotiatedBoardCannotClaimRecoveryToChangeTheRequestedTarget() = runBlocking {
        val control = open(ready(grace = null), ack(recovery = true))
        assertTrue(runCatching { control.exchange(30, -30) }.exceptionOrNull() is IOException)
        assertFalse(control.isOpen)
    }

    @Test fun negotiatedBoardMustSendBooleanRecoveryOnEveryAck() = runBlocking {
        for (value in listOf(null, "true", 1, JSONObject.NULL, JSONObject())) {
            val control = open(ready(), ack(recovery = value))
            assertTrue("Invalid recovery metadata must reject the ACK", runCatching {
                control.exchange(0, 0)
            }.exceptionOrNull() is IOException)
            assertFalse(control.isOpen)
        }
    }

    @Test fun recoveryCannotAuthorizeNonzeroWheelsOrAnUnconfirmedStop() = runBlocking {
        for (reply in listOf(ack(left = 30, right = -30, recovery = true),
            ack(recovery = true).put("stopped", false),
            ack(recovery = true).put("left", 0.5))) {
            val control = open(ready(), reply)
            assertTrue(runCatching { control.exchange(30, -30) }.exceptionOrNull() is IOException)
            assertFalse(control.isOpen)
        }
    }

    @Test fun falseRecoveryCannotHideAnUnexpectedZeroTarget() = runBlocking {
        val control = open(ready(), ack(recovery = false))
        assertTrue(runCatching { control.exchange(30, -30) }.exceptionOrNull() is IOException)
        assertFalse(control.isOpen)
    }

    @Test fun malformedGraceIsRejectedBeforeControlRequests() = runBlocking {
        for (value in listOf("5000", true, JSONObject.NULL, -1, 0, 600, 2_500, 5_001, 5_000.5)) {
            assertTrue("Invalid networkGraceMs must fail READY", runCatching { open(ready(grace = value)) }
                .exceptionOrNull() is IOException)
        }
    }

    @Test fun legacyReadyAndAckKeepTheOriginal900msDeadline() = runBlocking {
        val control = open(ready(grace = null), ack(left = 30, right = -30, recovery = null))
        assertNull(control.ready.networkGraceMs)
        assertEquals(900L, driveAckTimeoutMillis(control.ready.networkGraceMs))
        val reply = control.exchange(30, -30)
        assertFalse(reply.recoveryRequired)
        assertFalse(reply.stopped)
        assertEquals(30, reply.left)
        assertEquals(-30, reply.right)
        assertTrue(control.isOpen)
    }

    private suspend fun open(vararg replies: JSONObject): BoardDriveChannel {
        val memory = GraceMemoryChannel(replies.joinToString("\n", postfix = "\n"))
        return BoardDriveChannel.open(DiagnosticTestSession(memory), true, Diagnostics.newContext()) { _, _ ->
            fail("Protocol rejection closes its channel, not the whole synthetic transport")
        }.also(channels::add)
    }

    private fun ready(grace: Any? = 5_000) = JSONObject().put("type", "ready")
        .put("token", token(1)).put("dryRun", true).put("port", "memory-only")
        .put("watchdogMs", 600).put("maxRpm", 100).put("parkingSupported", false).apply {
            if (grace != null) put("networkGraceMs", grace)
        }

    private fun ack(seq: Int = 1, left: Int = 0, right: Int = 0, recovery: Any? = true) = JSONObject()
        .put("type", "ack").put("seq", seq).put("token", token(seq + 1))
        .put("left", left).put("right", right).put("stopped", left == 0 && right == 0).apply {
            if (recovery != null) put("recoveryRequired", recovery)
        }

    private fun token(seq: Int) = seq.toString(16).padStart(32, '0')

    private class GraceMemoryChannel(replies: String) : ChannelExec() {
        private val input = ByteArrayInputStream(replies.toByteArray())
        private val output = ByteArrayOutputStream()
        @Volatile private var connected = false
        @Volatile private var closed = false
        override fun setCommand(command: String) {
            check(command == "python3 -u /home/topeet/lianzhan/scripts/app_joystick_bridge.py --dry-run")
        }
        override fun connect(timeout: Int) { connected = true }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun isConnected(): Boolean = connected && !closed
        override fun isClosed(): Boolean = closed
        override fun isEOF(): Boolean = false
        override fun disconnect() { closed = true }
    }
}
