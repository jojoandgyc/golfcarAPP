package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.File
import java.io.IOException
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in persistent cat round trips, without opening any control bridge or hardware. */
@RunWith(AndroidJUnit4::class)
class RemoteRelayReadOnlyLatencyTest {
    @Test fun measureRemoteEchoWithoutStartingControl() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit read-only relay measurement opt-in required",
            args.getString("useSavedBoardCredentials") == "true" && args.getString("remoteRelayReadOnly") == "true")
        val relayTransport = args.getString("relayTransport") ?: "unknown"
        require(relayTransport in setOf("tcp", "quic", "unknown"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = RemoteCredentials(app)
        val profile = requireNotNull(credentials.load())
        val username = app.getSharedPreferences("board_connection", 0).getString("username", "topeet")!!
        val password = app.getSharedPreferences("board_credentials", 0).getString("password", null).orEmpty()
        assumeTrue("Saved board credential required", password.isNotEmpty())
        val client = BoardSshClient()
        var channel: ChannelExec? = null
        val rtt = mutableListOf<Long>()
        try {
            val resolved = withContext(Dispatchers.IO) { InetAddress.getAllByName(profile.host).map { it.hostAddress } }
            val started = System.nanoTime()
            client.connect(BoardConnection(profile.host, profile.controlPort, username, password, credentials.createTlsConfig()))
            val connectedMs = elapsedMillis(started)
            val field = BoardSshClient::class.java.getDeclaredField("connection").apply { isAccessible = true }
            val connection = field.get(client)!!
            val session = connection.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(connection) as Session
            val echo = (session.openChannel("exec") as ChannelExec).also { channel = it }
            echo.setCommand("cat")
            echo.setPty(false)
            echo.setInputStream(null)
            val input = echo.inputStream
            val output = echo.outputStream
            withContext(Dispatchers.IO) { echo.connect(5000) }
            repeat(12) { sequence ->
                val request = "ping-$sequence\n".toByteArray(Charsets.US_ASCII)
                val began = System.nanoTime()
                withTimeout(5000) {
                    withContext(Dispatchers.IO) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation { client.disconnect("echo_write_cancelled") }
                            try {
                                if (continuation.isActive) { output.write(request); output.flush(); continuation.resume(Unit) }
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                        val response = ArrayList<Byte>()
                        while (response.size < request.size) {
                            if (input.available() > 0) {
                                val value = input.read()
                                if (value < 0) throw IOException("Echo channel closed")
                                response += value.toByte()
                            } else {
                                if (echo.isClosed || echo.isEOF) throw IOException("Echo channel closed")
                                delay(1)
                            }
                        }
                        assertArrayEquals(request, response.toByteArray())
                    }
                }
                rtt += elapsedMillis(began)
                delay(100)
            }
            fun percentile(q: Double) = rtt.sorted()[(ceil(rtt.size * q).toInt() - 1).coerceIn(rtt.indices)]
            val report = JSONObject().put("schemaVersion", 1).put("readOnly", true).put("host", profile.host)
                .put("relayTransport", relayTransport)
                .put("resolvedAddresses", JSONArray(resolved)).put("connectMs", connectedMs)
                .put("samplesMs", JSONArray(rtt)).put("p50Ms", percentile(.50)).put("p95Ms", percentile(.95))
                .put("p99Ms", percentile(.99))
            File(app.getExternalFilesDir(null), "remote-relay-echo-validation.json").writeText(report.toString(2))
        } finally {
            client.disconnect("readonly_echo_complete")
            runCatching { channel?.disconnect() }
        }
    }
}
