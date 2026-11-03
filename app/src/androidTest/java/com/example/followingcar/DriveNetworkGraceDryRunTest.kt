package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in bridge simulation only. Never calls a ViewModel, video service, camera or real motor path. */
@RunWith(AndroidJUnit4::class)
class DriveNetworkGraceDryRunTest {
    @Test fun bidirectional1200msHoldKeepsDryRunSessionAndRejectsLateMovement() =
        verifyGrace(HoldDirection.BOTH, 1_200L, expectTimeout = false)

    @Test fun returnPath1800msHoldKeepsDryRunSessionAndRequiresNeutralBeforeNewMovement() =
        verifyGrace(HoldDirection.RETURN_ONLY, 1_800L, expectTimeout = false)

    @Test fun prolongedHoldStillInvalidatesAppSshAndExpiresBoardGrace() =
        verifyGrace(HoldDirection.BOTH, 6_500L, expectTimeout = true)

    private fun verifyGrace(direction: HoldDirection, holdMs: Long, expectTimeout: Boolean) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit network-grace dry-run and idle confirmation required",
            args.getString("runDriveNetworkGraceDryRun") == "true" &&
                args.getString("confirmedControlIdle") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = context.getSharedPreferences("board_connection", 0)
        val route = args.getString("driveGraceRoute") ?: "local"
        require(route == "local" || route == "remote") { "driveGraceRoute must be local or remote" }
        val credentials = if (route == "remote") RemoteCredentials(context) else null
        val profile = credentials?.let { requireNotNull(it.load()) { "Import a remote profile before using the remote route" } }
        val tls = credentials?.createTlsConfig()
        val host = profile?.host ?: (args.getString("boardHost") ?: saved.getString("host", null)).orEmpty().trim()
        val port = profile?.controlPort ?: (args.getString("boardPort") ?: saved.getString("port", "22")).orEmpty().toInt()
        val username = saved.getString("username", "topeet").orEmpty()
        val password = context.getSharedPreferences("board_credentials", 0).getString("password", "").orEmpty()
        assumeTrue("A board endpoint and saved SSH credentials are required",
            host.isNotBlank() && host != "127.0.0.1" && username.isNotBlank() && password.isNotEmpty())
        require(port in 1..65535)
        val observer = BoardSshClient()
        val client = BoardSshClient()
        var relay: HoldingDriveSshRelay? = null
        var channel: BoardDriveChannel? = null
        val report = JSONObject().put("dryRun", true).put("execute", false)
            .put("cameraOpened", false).put("serialOpened", false)
            .put("route", route).put("remoteHost", profile?.host ?: JSONObject.NULL)
            .put("holdDirection", direction.name).put("holdMs", holdMs)
        try {
            withTimeout(45_000) {
                observer.connect(BoardConnection(host, port, username, password, tls))
                assertTrue("Never run a fault test while board control is occupied",
                    observer.readFollowAvailability().controlAvailable)
                val proxy = HoldingDriveSshRelay(host, port, tls).also { relay = it }
                // TLS terminates only at this test-owned proxy socket. SSH still authenticates
                // the pinned board key end to end, including the loopback client connection.
                client.connect(BoardConnection("127.0.0.1", proxy.port, username, password))
                val active = client.openDriveChannel(dryRun = true).also { channel = it }
                assertTrue(active.ready.dryRun)
                assertEquals(600, active.ready.watchdogMs)
                assertEquals("Deploy the negotiated grace bridge before running this test", 5_000, active.ready.networkGraceMs)
                val runId = requireNotNull(active.diagnosticContext.runId)
                require(runId.matches(Regex("[a-f0-9]{32}")))
                report.put("runId", runId).put("watchdogMs", active.ready.watchdogMs)
                    .put("networkGraceMs", active.ready.networkGraceMs)
                assertTrue(active.exchange(0, 0).stopped)
                assertFalse(active.exchange(30, -30).stopped)

                // Hold bytes without discarding them: dropping decrypted TCP-stream bytes
                // would permanently corrupt SSH and cannot model a recoverable network pause.
                proxy.holdExistingConnections(direction, holdMs)
                val started = System.nanoTime()
                val delayed = runCatching { active.exchange(30, -30) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                report.put("delayedExchangeMs", elapsedMs)
                if (expectTimeout) {
                    assertTrue("A prolonged hold must still hit the negotiated ACK deadline: ${delayed.exceptionOrNull()?.javaClass?.simpleName}",
                        delayed.exceptionOrNull() is SocketTimeoutException)
                    assertTrue("Grace must not silently fall back to the old 900 ms deadline", elapsedMs >= 2_200L)
                    assertTrue("ACK timeout must remain bounded", elapsedMs < 4_000L)
                    assertFalse("An ACK timeout invalidates the stale transport", client.isConnected)
                    assertFalse(active.isOpen)
                    assertTrue("No movement may be replayed on the failed channel",
                        runCatching { active.exchange(30, -30) }.isFailure)
                    // Keep the proxy's board connection open and its bytes held until the
                    // board's own five-second deadline fires; do not manufacture an EOF.
                    val events = awaitBoardFinished(observer, runId)
                    assertTrue(events.any { it.optString("event") == "drive.network_paused" })
                    assertTrue(events.any { it.optString("event") == "drive.failure" &&
                        it.optString("stopSource") == "network_grace_expired" })
                    assertTrue(events.last { it.optString("event") == "drive.finished" }.getBoolean("cleanupConfirmed"))
                    report.put("appSshInvalidated", true).put("boardGraceExpired", true)
                    report.put("boardEvents", eventSummary(events))
                } else {
                    val oldAck = delayed.getOrThrow()
                    assertTrue("The proxy must actually hold traffic for the requested duration", elapsedMs >= holdMs - 100L)
                    assertTrue(client.isConnected)
                    assertTrue(active.isOpen)
                    report.put("delayedAckRecoveryRequired", oldAck.recoveryRequired)
                    // A held return ACK may predate the board's soft stop. The next request
                    // must expose the latch even though its requested target is nonzero.
                    val blocked = active.exchange(30, -30)
                    assertRecoveryStop(blocked)
                    assertRecoveryStop(active.exchange(30, -30))
                    val neutral = active.exchange(0, 0)
                    assertRecoveryStop(neutral)
                    val fresh = active.exchange(30, -30)
                    assertFalse(fresh.recoveryRequired)
                    assertFalse(fresh.stopped)
                    assertEquals(30, fresh.left)
                    assertEquals(-30, fresh.right)
                    assertTrue("Recovery stays on the original SSH/control session", client.isConnected && active.isOpen)
                    active.stopAndClose()
                    assertTrue(client.isConnected)
                    val events = awaitBoardFinished(observer, runId)
                    assertTrue(events.any { it.optString("event") == "drive.network_paused" })
                    assertTrue(events.any { it.optString("event") == "drive.network_recovery_ack" &&
                        it.optBoolean("neutralAccepted") })
                    report.put("sameSessionRecovered", true).put("lateMotionSuppressed", true)
                        .put("neutralRequired", true).put("boardEvents", eventSummary(events))
                }
                assertTrue(observer.readFollowAvailability().controlAvailable)
                report.put("passed", true)
            }
        } catch (failure: Throwable) {
            report.put("passed", false).put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            // Closing the private proxy never changes system networking or a different SSH session.
            relay?.close()
            channel?.close("network_grace_dry_run_complete")
            client.disconnect("network_grace_dry_run_complete")
            observer.disconnect("network_grace_observer_complete")
            File(context.getExternalFilesDir(null), "drive-network-grace-${direction.name.lowercase()}-$holdMs.json")
                .writeText(report.toString(2))
        }
    }

    private fun assertRecoveryStop(ack: DriveAck) {
        assertTrue(ack.recoveryRequired)
        assertTrue(ack.targetStopped)
        assertTrue(ack.feedback != null)
        assertEquals(0, ack.left)
        assertEquals(0, ack.right)
    }

    private suspend fun awaitBoardFinished(observer: BoardSshClient, runId: String): List<JSONObject> = withTimeout(12_000) {
        while (true) {
            val reply = observer.execute("cat /home/topeet/lianzhan/app_drive_diagnostic_logs/*_${runId}_*.jsonl",
                timeoutMillis = 5_000L, kind = "drive_test_diagnostics")
            if (reply.exitCode == 0) {
                val events = reply.stdout.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                if (events.any { it.optString("event") == "drive.finished" }) return@withTimeout events
            }
            delay(150L)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    private fun eventSummary(events: List<JSONObject>): JSONArray = JSONArray(events.filter {
        it.optString("event") in setOf("drive.network_paused", "drive.network_recovery_ack", "drive.failure", "drive.finished")
    }.map { row -> JSONObject().put("event", row.optString("event")).put("monotonicMs", row.optLong("monotonicMs"))
        .put("stopSource", row.optString("stopSource")).put("recoveryRequired", row.optBoolean("recoveryRequired"))
        .put("neutralAccepted", row.optBoolean("neutralAccepted")) })
}

private enum class HoldDirection { BOTH, RETURN_ONLY }

/** Two independent pumps preserve order and bytes; only these test-owned sockets are delayed. */
private class HoldingDriveSshRelay(
    private val host: String,
    private val boardPort: Int,
    private val tls: RemoteTlsConfig? = null,
) : Closeable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    private val closed = AtomicBoolean()
    private val links = CopyOnWriteArrayList<Link>()

    init {
        daemon("drive-grace-relay-accept") {
            while (!closed.get()) {
                val local = runCatching { listener.accept().apply { tcpNoDelay = true } }.getOrNull() ?: break
                val rawSocket = Socket()
                val remote = try {
                    // Use the production transport, including client identity, CA validation,
                    // hostname verification and SNI. The pumps only see encrypted SSH bytes.
                    openBoardSocket(rawSocket, host, boardPort, 5_000, tls)
                } catch (_: Exception) { local.close(); rawSocket.close(); continue }
                val link = Link(local, remote)
                links += link
                daemon("drive-grace-relay-out") { link.pump(local, remote, link.outboundHoldUntil) }
                daemon("drive-grace-relay-in") { link.pump(remote, local, link.returnHoldUntil) }
            }
        }
    }

    fun holdExistingConnections(direction: HoldDirection, durationMs: Long) {
        check(links.isNotEmpty())
        require(durationMs in 1L..10_000L)
        val until = System.nanoTime() + durationMs * 1_000_000L
        links.forEach {
            if (direction == HoldDirection.BOTH) it.outboundHoldUntil.set(until)
            it.returnHoldUntil.set(until)
        }
    }

    override fun close() {
        closed.set(true)
        runCatching { listener.close() }
        links.forEach(Link::close)
    }

    private class Link(private val local: Socket, private val remote: Socket) : Closeable {
        val outboundHoldUntil = AtomicLong()
        val returnHoldUntil = AtomicLong()
        private val closed = AtomicBoolean()
        fun pump(source: Socket, destination: Socket, holdUntil: AtomicLong) {
            try {
                val bytes = ByteArray(8_192)
                while (!closed.get()) {
                    val count = source.getInputStream().read(bytes)
                    if (count < 0) break
                    while (!closed.get() && System.nanoTime() < holdUntil.get()) Thread.sleep(10)
                    if (closed.get()) break
                    destination.getOutputStream().write(bytes, 0, count)
                    destination.getOutputStream().flush()
                }
            } catch (_: Exception) { /* Faults and cleanup are local to this pair. */ }
            finally {
                // App timeout must not be mistaken for the board grace expiring from EOF.
                val holding = System.nanoTime() < maxOf(outboundHoldUntil.get(), returnHoldUntil.get())
                if (!holding) close()
            }
        }
        override fun close() {
            closed.set(true)
            runCatching { local.close() }
            runCatching { remote.close() }
        }
    }

    private fun daemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }
}
