package com.example.followingcar

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Per-test TCP relay affects only this App connection. No Wi-Fi/firewall/sshd changes. */
@RunWith(AndroidJUnit4::class)
class FollowNetworkRecoveryIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun blackholedNetworkEndsDryRunAndReconnectChecksBeforeUnlocking() = verifyRecovery(blackhole = true)

    @Test fun sshTransportEofEndsDryRunAndReconnectNeverRestartsIt() = verifyRecovery(blackhole = false)

    private fun verifyRecovery(blackhole: Boolean) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit dry-run fault injection opt-in",
            args.getString("useSavedBoardCredentials") == "true" && args.getString("injectFollowNetworkFaults") == "true")
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[BoardViewModel::class.java] }
        val saved = vm.state.value
        assumeTrue("Saved direct board connection required", saved.password.isNotEmpty() && saved.host != "127.0.0.1")
        val reader = BoardSshClient()
        val relay = IsolatedSshRelay(saved.host, saved.port.toInt())
        try {
            reader.connect(BoardConnection(saved.host, saved.port.toInt(), saved.username, saved.password))
            assertTrue("Never interfere with an occupied board", reader.readFollowAvailability().controlAvailable)
            compose.runOnUiThread {
                vm.updateHost("127.0.0.1")
                vm.updatePort(relay.port.toString())
                vm.connect()
            }
            compose.waitUntil(40_000) { !vm.state.value.busy }
            assertTrue(vm.state.value.error, vm.state.value.connected)
            assertFalse("Reconnect must inspect any previous pending run", vm.state.value.followUncertain)
            compose.runOnUiThread { vm.setFollowExecute(false); vm.inspectFollowConfiguration() }
            compose.waitUntil(35_000) { !vm.state.value.followConfigurationLoading }
            assertTrue(vm.state.value.followConfigurationError, vm.state.value.canStartFollow)
            compose.runOnUiThread { vm.startFollow(dryRun = true) }
            compose.waitUntil(65_000) { vm.state.value.followStatus?.runState == "RUNNING" || !vm.state.value.followActive }
            assertTrue(vm.state.value.followError, vm.state.value.followActive)
            assertEquals(false, vm.state.value.followStatus?.execute)
            val ready = Diagnostics.snapshot().events.map(::JSONObject).last { it.optString("event") == "follow.ready" }
            val runId = ready.getString("runId")
            assertTrue(runId.matches(Regex("[a-f0-9]{32}")))

            Diagnostics.event("test.network_fault_injected",
                DiagnosticContext(ready.getString("operationId"), ready.getString("connectionId"), runId),
                source = if (blackhole) "TEST_NETWORK_BLACKHOLE" else "TEST_SSH_EOF",
                fields = mapOf("dryRun" to true, "execute" to false), critical = true)
            if (blackhole) relay.blackholeExistingConnections() else relay.dropExistingServerConnections()
            compose.waitUntil(8_000) { !vm.state.value.followActive }
            val failed = vm.state.value
            assertFalse("Lost ACK must invalidate stale SSH immediately", failed.connected)
            assertEquals("连接已断开", failed.status)
            assertTrue("Transport loss does not confirm worker exit or physical stop", failed.followUncertain)
            assertFalse(failed.canEnableDrive)
            assertFalse(failed.canStartFollow)
            assertFalse(failed.canToggleVideoService)
            assertNull(failed.followConfiguration)
            assertEquals(VideoServiceState.UNKNOWN, failed.videoServiceState)
            val first = requireNotNull(failed.followFirstFailure)
            assertEquals(runId, first.context.runId)
            if (blackhole) assertEquals("follow.ack_timeout", first.event)
            ZipFile(Diagnostics.export()).use { archive ->
                val exported = JSONObject(archive.getInputStream(archive.getEntry("metadata.json")).bufferedReader().readText())
                assertEquals(runId, exported.getString("pendingFollowRunId"))
                assertEquals(first.event, exported.getString("pendingFollowFirstFailureEvent"))
            }

            val boardEvents = withTimeout(20_000) {
                var result: List<JSONObject>? = null
                while (result == null) {
                    val read = reader.execute("cat /home/topeet/Desktop/rk_car_runtime_module/app_diagnostic_logs/*_${runId}_*.jsonl")
                    assertEquals(read.stderr, 0, read.exitCode)
                    val records = read.stdout.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
                    if (records.any { it.optString("event") == "session_finished" }) result = records
                    if (result == null) delay(250)
                }
                result
            }
            val finished = boardEvents.last { it.optString("event") == "session_finished" }
            assertEquals(runId, finished.getString("runId"))
            val trigger = finished.getJSONObject("firstStop").getString("source")
            if (blackhole) assertEquals("heartbeat_timeout", trigger)
            else assertTrue("Unexpected SSH close trigger: $trigger", trigger in setOf("input_eof", "signal", "output_failure"))
            val pid = finished.getInt("workerPid")
            assertTrue(pid > 0)
            val exitCode = finished.getInt("workerExitCode")
            assertTrue("Unexpected worker failure: $exitCode", exitCode == 0 || exitCode == -15)
            if (exitCode == -15) {
                // Existing supervision also sends SIGTERM. A signal exit confirms process
                // termination only, never a successful STOP ACK or mechanical standstill.
                val exited = boardEvents.last { it.optString("event") == "worker_exited" }
                val beforeExit = boardEvents.takeWhile { it !== exited }
                assertTrue(beforeExit.any { it.optString("event") == "worker_stop_sent" &&
                    it.optInt("workerPid") == pid && it.optInt("bytesWritten") == 2 })
                assertTrue(beforeExit.any { it.optString("event") == "worker_signal_sent" &&
                    it.optInt("workerPid") == pid && it.optString("signal") == "SIGTERM" })
                assertTrue(beforeExit.any { it.optString("event") == "worker_state" &&
                    it.optInt("workerPid") == pid && it.optString("runState") == "STOPPING" })
                assertEquals("FAULT", exited.getString("runState"))
                assertTrue(exited.isNull("stopAcknowledged"))
                assertTrue(exited.isNull("physicalStopped"))
            }
            assertTrue(boardEvents.any { it.optString("event") == "cleanup_completed" })
            assertEquals("Dry-run worker must be gone", 0, reader.execute("test ! -d /proc/$pid").exitCode)
            assertTrue("Read-only observer must not release App's pending lock", vm.state.value.followUncertain)

            // New relay connections are healthy; there is no automatic follow restart.
            compose.runOnUiThread { vm.connect() }
            compose.waitUntil(45_000) { !vm.state.value.busy }
            val recovered = vm.state.value
            assertTrue(recovered.error, recovered.connected)
            assertFalse(recovered.followError, recovered.followUncertain)
            assertFalse(recovered.followActive)
            assertFalse(recovered.followExecute)
            assertTrue("Recovery allows an explicit user start without fetching configuration; it never starts automatically", recovered.canStartFollow)
            assertEquals(first, recovered.followFirstFailure)
            assertTrue(reader.readFollowAvailability().controlAvailable)
            assertTrue(Diagnostics.snapshot().events.map(::JSONObject).any {
                it.optString("event") == "follow.availability_result" &&
                    it.optJSONObject("fields")?.optBoolean("controlAvailable") == true
            })
        } finally {
            // Restore packet flow before normal cleanup, including an assertion failure.
            relay.restoreExistingConnections()
            try {
                compose.runOnUiThread { vm.stopFollow("网络故障模拟清理"); vm.disconnect() }
                compose.waitUntil(150_000) { !vm.state.value.connected && !vm.state.value.busy && !vm.state.value.followActive }
            } finally {
                relay.close()
                reader.disconnect("network_recovery_test_complete")
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                target.getSharedPreferences("board_connection", 0).edit()
                    .putString("host", saved.host).putString("port", saved.port).putString("username", saved.username).commit()
                compose.runOnUiThread { vm.updateHost(saved.host); vm.updatePort(saved.port) }
            }
        }
    }
}

/** A blackhole drains bytes without forwarding, so only the bridge's own lease ends the run. */
private class IsolatedSshRelay(private val boardHost: String, private val boardPort: Int) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val closed = AtomicBoolean(false)
    private val links = CopyOnWriteArrayList<Link>()

    init {
        daemon("test-ssh-relay-accept") {
            while (!closed.get()) {
                val local = runCatching { server.accept() }.getOrNull() ?: break
                val remote = try { Socket(boardHost, boardPort) }
                    catch (_: Exception) { local.close(); continue }
                val link = Link(local, remote)
                links += link
                daemon("test-ssh-relay-out") { link.pump(local, remote) }
                daemon("test-ssh-relay-in") { link.pump(remote, local) }
            }
        }
    }

    fun blackholeExistingConnections() { links.forEach { it.blackhole.set(true) } }
    fun dropExistingServerConnections() { links.forEach { it.remote.close() } }
    fun restoreExistingConnections() { links.forEach { it.blackhole.set(false) } }
    override fun close() {
        closed.set(true)
        server.close()
        links.forEach { it.close() }
    }

    private class Link(val local: Socket, val remote: Socket) : Closeable {
        val blackhole = AtomicBoolean(false)
        fun pump(source: Socket, destination: Socket) {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = source.getInputStream().read(buffer)
                    if (count < 0) break
                    if (!blackhole.get()) {
                        destination.getOutputStream().write(buffer, 0, count)
                        destination.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) { /* A test-injected EOF closes only this pair. */ }
            finally {
                // Keep the board SSH transport alive during a blackhole even when App closes its
                // stale local socket. The board must independently stop from missing heartbeats.
                if (!blackhole.get()) close()
            }
        }
        override fun close() {
            runCatching { local.close() }
            runCatching { remote.close() }
        }
    }

    private fun daemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }
}
