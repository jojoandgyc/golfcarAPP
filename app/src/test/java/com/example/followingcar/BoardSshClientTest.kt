package com.example.followingcar

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSshClientTest {
    @Test
    fun coroutineCancellationClosesSocketDuringStalledHandshake() = runBlocking {
        val client = BoardSshClient()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val attempt = launch(Dispatchers.Default) {
                client.connect(BoardConnection("127.0.0.1", listener.localPort, "test", "test"))
            }
            try {
                withContext(Dispatchers.IO) { listener.accept() }.use { peer ->
                    peer.soTimeout = 3_000
                    // Consume the client banner but never answer: authentication cannot finish.
                    val reader = peer.getInputStream().bufferedReader()
                    assertTrue(withContext(Dispatchers.IO) { reader.readLine() }.startsWith("SSH-2.0-"))
                    withTimeout(3_000) { attempt.cancelAndJoin() }
                    assertFalse(client.isConnected)
                    assertEquals(-1, withContext(Dispatchers.IO) { reader.read() })
                }
            } finally {
                client.disconnect()
                attempt.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitDisconnectInterruptsStalledHandshake() = runBlocking {
        val client = BoardSshClient()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val attempt = launch(Dispatchers.Default) {
                runCatching {
                    client.connect(BoardConnection("127.0.0.1", listener.localPort, "test", "test"))
                }
            }
            try {
                withContext(Dispatchers.IO) { listener.accept() }.use { peer ->
                    peer.soTimeout = 3_000
                    val reader = peer.getInputStream().bufferedReader()
                    assertTrue(withContext(Dispatchers.IO) { reader.readLine() }.startsWith("SSH-2.0-"))
                    client.disconnect()
                    withTimeout(3_000) { attempt.join() }
                    assertFalse(client.isConnected)
                    assertEquals(-1, withContext(Dispatchers.IO) { reader.read() })
                }
            } finally {
                client.disconnect()
                attempt.cancelAndJoin()
            }
        }
    }
}
