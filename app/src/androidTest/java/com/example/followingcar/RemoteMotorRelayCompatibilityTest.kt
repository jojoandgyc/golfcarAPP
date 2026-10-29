package com.example.followingcar

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in emulator audit of the existing mTLS -> frp -> pinned board SSH path.
 * Fixed Python commands only read stdin, echo memory, or sleep. They never invoke
 * a control bridge, sudo, serial/camera APIs, file writes, or network mutations.
 * No credential, command body, or command output is included in the report.
 */
@RunWith(AndroidJUnit4::class)
class RemoteMotorRelayCompatibilityTest {
    @Test fun completePayloadAndLongIdleCommandUseExistingRelayWithoutHardwareAccess() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires remoteMotorRelayReadOnly=true",
            args.getString("remoteMotorRelayReadOnly") == "true")
        assumeTrue("This audit only runs on an Android emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish"))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = RemoteCredentials(context)
        val profile = requireNotNull(credentials.load()) { "Saved remote credentials are required" }
        require(profile.host.equals(DEFAULT_REMOTE_HOST, ignoreCase = true) &&
            profile.controlPort == DEFAULT_REMOTE_CONTROL_PORT) { "Unexpected relay endpoint" }
        val username = context.getSharedPreferences("board_connection", 0)
            .getString("username", "topeet").orEmpty()
        val password = context.getSharedPreferences("board_credentials", 0)
            .getString("password", null).orEmpty()
        require(username.isNotBlank() && password.isNotEmpty()) { "Saved board credentials are required" }
        val connection = BoardConnection(DEFAULT_REMOTE_HOST, DEFAULT_REMOTE_CONTROL_PORT,
            username, password, credentials.createTlsConfig())
        val client = BoardSshClient()
        val report = JSONObject().put("host", DEFAULT_REMOTE_HOST).put("testPassed", false)
        val totalStarted = System.nanoTime()
        var stage = "connect"
        try {
            withTimeout(180_000L) {
                val connectStarted = System.nanoTime()
                client.connect(connection)
                report.put("connectMs", elapsed(connectStarted))
                assertTrue("Read-only relay connection did not become ready", client.isConnected)

                stage = "payload"
                val input = ByteArray(8192) { (65 + it % 26).toByte() }
                val expected = String(input, Charsets.US_ASCII).repeat(16)
                val payloadStarted = System.nanoTime()
                val payload = client.execute(PAYLOAD_COMMAND, timeoutMillis = 45_000L,
                    kind = "terminal", input = input, outputLimitBytes = 128 * 1024)
                report.put("inputBytes", input.size).put("outputBytes", payload.stdout.toByteArray(Charsets.UTF_8).size)
                    .put("payloadElapsedMs", elapsed(payloadStarted))
                assertEquals("Memory echo returned a nonzero exit status", 0, payload.exitCode)
                assertFalse("Full motor-response buffer was truncated", payload.truncated)
                assertTrue("Memory echo returned unexpected stderr", payload.stderr.isEmpty())
                // Boolean assertions deliberately avoid dumping the 128 KiB payload on failure.
                assertTrue("Memory echo response bytes did not match", payload.stdout == expected)
                assertEquals(128 * 1024, payload.stdout.toByteArray(Charsets.UTF_8).size)

                stage = "long_idle"
                val idleStarted = System.nanoTime()
                val idle = client.execute(LONG_IDLE_COMMAND, timeoutMillis = 90_000L, kind = "terminal")
                val idleElapsed = elapsed(idleStarted)
                report.put("idleElapsedMs", idleElapsed)
                assertEquals("Long idle command returned a nonzero exit status", 0, idle.exitCode)
                assertFalse("Long idle response was truncated", idle.truncated)
                assertTrue("Long idle response differed from its fixed marker",
                    idle.stdout == "relay-idle-ok\n" && idle.stderr.isEmpty())
                assertTrue("Long idle command did not span the requested 65 seconds", idleElapsed >= 65_000L)
                assertTrue("SSH did not remain connected across long idle command", client.isConnected)

                if (args.getString("remoteMotorRelayCancel") == "true") {
                    stage = "cancel"
                    coroutineScope {
                        // Cancel only this child, never the test's parent scope. Depending on RTT,
                        // cancellation may occur during channel-open or command-wait; both must close SSH.
                        val waiting = launch {
                            client.execute(CANCELLABLE_COMMAND, timeoutMillis = 20_000L, kind = "terminal")
                        }
                        delay(200L)
                        assertTrue("Fixed sleeping command was no longer pending", waiting.isActive)
                        val cancelStarted = System.nanoTime()
                        withTimeout(5_000L) { waiting.cancelAndJoin() }
                        report.put("cancelElapsedMs", elapsed(cancelStarted))
                        assertTrue("The read-only sleeping request was not cancelled", waiting.isCancelled)
                        assertFalse("Cancelled SSH execution must invalidate the connection", client.isConnected)
                    }

                    stage = "reconnect"
                    val reconnectStarted = System.nanoTime()
                    client.connect(connection)
                    report.put("reconnectMs", elapsed(reconnectStarted))
                    val resumed = client.execute(RECONNECTED_COMMAND, timeoutMillis = 15_000L, kind = "terminal")
                    assertEquals("Read-only reconnect returned a nonzero exit status", 0, resumed.exitCode)
                    assertFalse("Read-only reconnect response was truncated", resumed.truncated)
                    assertTrue("Read-only reconnect did not return its fixed marker",
                        resumed.stdout == "relay-reconnected-ok\n" && resumed.stderr.isEmpty())
                    assertTrue("Reconnected SSH did not remain ready", client.isConnected)
                }
                report.put("testPassed", true)
            }
        } catch (failure: Throwable) {
            // No exception message/cause: failures must not print command bodies, data, or credentials.
            throw AssertionError("Read-only relay audit failed at $stage (${failure.javaClass.simpleName})")
        } finally {
            withContext(NonCancellable) {
                client.disconnect("remote_motor_readonly_audit_complete")
                report.put("elapsedMs", elapsed(totalStarted))
                File(requireNotNull(context.getExternalFilesDir(null)),
                    "remote-motor-relay-compatibility.json").writeText(report.toString(2))
            }
        }
    }

    private fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private companion object {
        const val PAYLOAD_COMMAND = "python3 -B -c 'import sys; data=sys.stdin.buffer.read(8193); " +
            "assert len(data)==8192; sys.stdout.buffer.write(data*16)'"
        const val LONG_IDLE_COMMAND = "python3 -B -c 'import time; time.sleep(65); print(\"relay-idle-ok\")'"
        const val CANCELLABLE_COMMAND = "python3 -B -c 'import time; time.sleep(10); print(\"relay-cancel-probe\")'"
        const val RECONNECTED_COMMAND = "python3 -B -c 'print(\"relay-reconnected-ok\")'"
    }
}
