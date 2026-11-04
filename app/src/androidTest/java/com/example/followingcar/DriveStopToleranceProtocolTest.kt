package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.DiagnosticTestSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/** Negotiation and terminal acknowledgement use memory only; no SSH/camera/motor I/O. */
@RunWith(AndroidJUnit4::class)
class DriveStopToleranceProtocolTest {
    private val engineField = Diagnostics::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private var originalEngine: DiagnosticEngine? = null
    private lateinit var engine: DiagnosticEngine
    private lateinit var directory: File
    @Before fun isolateDiagnostics() {
        originalEngine = engineField.get(Diagnostics) as DiagnosticEngine?
        originalEngine?.flush()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stop-tolerance-protocol-${UUID.randomUUID()}")
        engine = DiagnosticEngine(DiagnosticFileStore(directory))
        engineField.set(Diagnostics, engine)
    }
    @After fun restoreDiagnostics() {
        try { engine.flush() }
        finally {
            engineField.set(Diagnostics, originalEngine)
            engine.close()
            directory.deleteRecursively()
        }
    }

    private fun ready(relaxed: Boolean = true, rpm: Int = 0) = JSONObject()
        .put("type", "ready").put("token", "1".padStart(32, '0'))
        .put("dryRun", true).put("port", "memory").put("maxRpm", 100)
        .put("watchdogMs", 600).put("networkGraceMs", 5000).withDriveSafetyFixture().apply {
            if (relaxed) put("stopConfirmationWarningSupported", true)
                .put("stopCommandConfirmed", true).put("feedbackNoiseMaxRpm", 80)
            if (rpm != 0) put("stopped", false).put("feedbackLeftRpm", rpm).put("feedbackSamples", 0)
        }

    private fun terminal(rpm: Int = 60) = JSONObject().put("type", "ack")
        .put("seq", 1).put("token", "2".padStart(32, '0')).put("left", 0).put("right", 0)
        .put("recoveryRequired", false).withDriveSafetyFixture()
        .put("stopCommandConfirmed", true).put("stopped", false)
        .put("feedbackLeftRpm", rpm).put("feedbackSamples", 0)

    private suspend fun open(initial: JSONObject, ack: JSONObject? = null): BoardDriveChannel {
        val memory = MemoryChannel(initial.toString() + "\n" + (ack?.let { "$it\n" } ?: ""))
        return BoardDriveChannel.open(DiagnosticTestSession(memory), true, Diagnostics.newContext()) { _, _ ->
            fail("Memory-only protocol tests must not invalidate an actual transport")
        }
    }

    @Test fun negotiatedStartupPreservesEightyRpmRatherThanClaimingPhysicalZero() = runBlocking {
        val control = open(ready(rpm = 80))
        try {
            assertTrue(control.ready.stopConfirmationWarningSupported)
            assertEquals(80, control.ready.feedbackNoiseMaxRpm)
            assertEquals(80, control.ready.feedback!!.leftRpm)
            assertFalse(control.ready.feedback!!.zeroSpeedConfirmed)
            assertTrue(control.isOpen)
        } finally { control.close() }
    }

    @Test fun largerStartupFeedbackCanRemainConnectedWhileTheVmWaits() = runBlocking {
        val control = open(ready(rpm = 81))
        try { assertTrue(control.isOpen); assertEquals(81, control.ready.feedback!!.leftRpm) }
        finally { control.close() }
    }

    @Test fun oldBridgeStillCannotClaimUnconfirmedReadyOrTerminalCompletion() = runBlocking {
        assertTrue(runCatching { open(ready(relaxed = false, rpm = 1)) }.exceptionOrNull() is IOException)
        val control = open(ready(relaxed = false), terminal())
        assertTrue(runCatching { control.stopAndClose() }.exceptionOrNull() is IOException)
        assertFalse(control.isOpen)
    }

    @Test fun closeAndEmergencyConfirmCommandWithoutFalselyConfirmingStandstill() = runBlocking {
        for (emergency in listOf(false, true)) {
            val control = open(ready(), terminal(60))
            val ack = if (emergency) control.exchange(0, 0, emergency = true) else control.stopAndClose()
            assertTrue(ack.stopCommandConfirmed)
            assertTrue(ack.targetStopped)
            assertFalse(ack.stopped)
            assertEquals(60, ack.feedback!!.leftRpm)
            assertFalse(control.isOpen)
        }
    }

    @Test fun terminalWithoutAcknowledgedZeroStopWritesStillFails() = runBlocking {
        for (bad in listOf(false, "true", JSONObject.NULL)) {
            val control = open(ready(), terminal().put("stopCommandConfirmed", bad))
            assertTrue(runCatching { control.stopAndClose() }.exceptionOrNull() is IOException)
        }
    }

    @Test fun faultAndExpiredFeedbackAreNotHiddenByStopCommandAcknowledgement() = runBlocking {
        for (bad in listOf(terminal().put("feedbackLeftError", 3), terminal().put("feedbackAgeMs", 251))) {
            val control = open(ready(), bad)
            assertTrue(runCatching { control.stopAndClose() }.exceptionOrNull() is IOException)
        }
    }

    @Test fun malformedCapabilityOrToleranceCannotBeUsedToRelaxStartup() = runBlocking {
        for (bad in listOf(ready(rpm = 1).put("stopConfirmationWarningSupported", "true"),
            ready(rpm = 1).put("stopCommandConfirmed", false),
            ready().put("feedbackNoiseMaxRpm", 81), ready().put("feedbackNoiseMaxRpm", 80.5))) {
            assertTrue(runCatching { open(bad) }.exceptionOrNull() is IOException)
        }
    }

    private class MemoryChannel(replies: String) : ChannelExec() {
        private val input = ByteArrayInputStream(replies.toByteArray())
        private val output = ByteArrayOutputStream()
        private var open = false
        override fun setCommand(command: String) { check(command == driveBridgeCommand(true)) }
        override fun connect(timeout: Int) { open = true }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun isConnected() = open
        override fun isClosed() = !open
        override fun isEOF() = false
        override fun disconnect() { open = false }
    }
}
