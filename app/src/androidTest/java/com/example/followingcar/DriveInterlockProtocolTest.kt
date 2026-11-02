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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual channel/JSON validation with memory streams: no Bluetooth, SSH or motor device. */
@RunWith(AndroidJUnit4::class)
class DriveInterlockProtocolTest {
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var previousEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    private val channels = mutableListOf<BoardDriveChannel>()

    @Before fun isolateDiagnosticHistory() {
        previousEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        previousEngine?.flush()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "drive-interlock-test-${UUID.randomUUID()}")
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)
    }

    @After fun restoreDiagnosticHistory() {
        try {
            channels.forEach { it.close("test_cleanup") }
            engine.flush()
        } finally {
            engineField.set(Diagnostics, previousEngine)
            engine.close()
            directory.deleteRecursively()
        }
    }

    @Test fun protectionStopKeepsChannelAliveAndNeutralReleasesTheReport() = runBlocking {
        val control = open(ready(), ack(), ack(seq = 2, blocked = false, directions = JSONArray()))
        val blocked = control.exchange(40, -40)
        assertEquals(DriveInterlock(true, setOf("forward")), blocked.interlock)
        assertTrue(blocked.stopped)
        assertTrue(control.isOpen)
        val neutral = control.exchange(0, 0)
        assertEquals(DriveInterlock(false, emptySet<String>()), neutral.interlock)
        assertTrue(neutral.stopped)
        assertTrue(control.isOpen)
        assertTrue(engine.snapshot().failures.isEmpty())
    }

    @Test fun legacyReadyAndAckRemainSupportedWithExactTargets() = runBlocking {
        val control = open(ready(capability = null), ack(left = 40, right = -40,
            blocked = null, directions = null))
        assertFalse(control.ready.infraredSupported)
        assertNull(control.diagnosticContext.runId)
        assertNull(control.exchange(40, -40).interlock)
        assertTrue(control.isOpen)
    }

    @Test fun legacyBoardCannotAuthorizeAChangedTargetWithInfraredFields() = runBlocking {
        val control = open(ready(capability = null), ack())
        assertTrue(runCatching { control.exchange(40, -40) }.exceptionOrNull() is IOException)
        assertFalse(control.isOpen)
    }

    @Test fun supportedBoardMustSupplyBothFieldsOnEveryAck() = runBlocking {
        for (reply in listOf(ack(blocked = null), ack(directions = null), ack(blocked = null, directions = null))) {
            val control = open(ready(), reply)
            assertTrue(runCatching { control.exchange(40, -40) }.exceptionOrNull() is IOException)
            assertFalse(control.isOpen)
        }
    }

    @Test fun infraredMetadataUsesStrictJsonTypesAndKnownUniqueDirections() = runBlocking {
        for (reply in listOf(ack(blocked = "true"), ack(blocked = 1), ack(directions = "forward"),
            ack(directions = JSONArray().put(1)), ack(directions = JSONArray().put("backward")),
            ack(directions = JSONArray().put("forward").put("forward")),
            ack(blocked = JSONObject.NULL), ack(directions = JSONObject.NULL))) {
            val control = open(ready(), reply)
            assertTrue(runCatching { control.exchange(40, -40) }.exceptionOrNull() is IOException)
            assertFalse(control.isOpen)
        }
    }

    @Test fun falseProtectionFlagCannotHideUnexpectedZeroSpeed() = runBlocking {
        val control = open(ready(), ack(blocked = false, directions = JSONArray()))
        assertTrue(runCatching { control.exchange(40, -40) }.exceptionOrNull() is IOException)
        assertFalse(control.isOpen)
    }

    @Test fun neutralCommandCannotClaimAnInfraredOverride() = runBlocking {
        val control = open(ready(), ack())
        assertTrue(runCatching { control.exchange(0, 0) }.exceptionOrNull() is IOException)
        assertFalse(control.isOpen)
    }

    @Test fun malformedCapabilityIsRejectedBeforeControlRequests() = runBlocking {
        for (value in listOf("true", 1, JSONObject.NULL)) {
            assertTrue(runCatching { open(ready(capability = value)) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun overrideCannotCoerceFractionalOrStringMotorValuesIntoZero() = runBlocking {
        for (reply in listOf(ack().put("left", 0.1), ack().put("left", "0"),
            ack().put("stopped", "true"))) {
            val control = open(ready(), reply)
            assertTrue(runCatching { control.exchange(40, -40) }.exceptionOrNull() is IOException)
            assertFalse(control.isOpen)
        }
    }

    @Test fun boardRunIdCorrelatesTimingSummaryAndLaterFailureWithoutTokens() = runBlocking {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val control = open(ready(runId = runId), ack(),
            ack(seq = 2, blocked = false, directions = JSONArray()),
            ack(seq = 3, left = 40, right = -40, blocked = null, directions = null))
        assertEquals(runId, control.diagnosticContext.runId)
        control.exchange(40, -40)
        control.exchange(0, 0)
        assertTrue(runCatching { control.exchange(40, -40) }.exceptionOrNull() is IOException)
        val records = engine.snapshot().events.map(::JSONObject)
        val ready = records.single { it.getString("event") == "drive.ready" }
        val summaries = records.filter { it.getString("event") == "drive.ack_summary" }
        val failure = records.single { it.getString("event") == "drive.transaction_failed" }
        assertEquals(runId, ready.getString("runId"))
        assertTrue(summaries.isNotEmpty())
        for (summary in summaries) {
            assertEquals(runId, summary.getString("runId"))
            assertTrue(summary.getJSONObject("fields").getLong("lastAckRttMs") >= 0L)
        }
        assertEquals(runId, failure.getString("runId"))
        assertEquals(2L, failure.getJSONObject("fields").getLong("lastAckSeq"))
        assertTrue(failure.getJSONObject("fields").getLong("lastAckRttMs") >= 0L)
        assertTrue(failure.getJSONObject("fields").getLong("sendGapMs") >= 0L)
        assertFalse(records.joinToString().contains("1".repeat(32)))
        assertFalse(records.joinToString().contains("2".repeat(32)))
        assertFalse(records.joinToString().contains("3".repeat(32)))
    }

    @Test fun malformedBoardRunIdIsRejectedBeforeControlRequests() = runBlocking {
        for (value in listOf("", "x".repeat(32), "a".repeat(31), "a".repeat(33),
            "A".repeat(32), 123, JSONObject.NULL)) {
            assertTrue(runCatching { open(ready(runId = value)) }.exceptionOrNull() is IOException)
        }
    }

    private suspend fun open(vararg messages: JSONObject): BoardDriveChannel {
        val channel = MemoryChannel(messages.joinToString("\n", postfix = "\n"))
        return BoardDriveChannel.open(DiagnosticTestSession(channel), true, Diagnostics.newContext()) { _, _ ->
            fail("In-memory protocol validation should not abort a transport")
        }.also(channels::add)
    }

    private fun ready(capability: Any? = true, runId: Any? = null) = JSONObject().put("type", "ready")
        .put("token", "1".repeat(32)).put("dryRun", true).put("port", "in-memory-test")
        .put("maxRpm", 100).put("watchdogMs", 600).put("parkingSupported", false).apply {
            if (capability != null) put("infraredSupported", capability)
            if (runId != null) put("runId", runId)
        }

    private fun ack(seq: Int = 1, left: Int = 0, right: Int = 0, blocked: Any? = true,
        directions: Any? = JSONArray().put("forward")) = JSONObject().put("type", "ack")
        .put("seq", seq).put("token", (seq + 1).toString().repeat(32)).put("left", left)
        .put("right", right).put("stopped", left == 0 && right == 0).apply {
            if (blocked != null) put("infraredBlocked", blocked)
            if (directions != null) put("infraredDirections", directions)
        }

    private class MemoryChannel(response: String) : ChannelExec() {
        private val input = ByteArrayInputStream(response.toByteArray())
        private val output = ByteArrayOutputStream()
        @Volatile private var connected = false
        @Volatile private var disconnected = false
        override fun connect(timeout: Int) { connected = true }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun isConnected(): Boolean = connected && !disconnected
        override fun isClosed(): Boolean = disconnected
        override fun isEOF(): Boolean = false
        override fun disconnect() { disconnected = true }
    }
}
