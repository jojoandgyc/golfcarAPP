package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSchException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in tests against the real board; all commands are read-only and avoid hardware. */
@RunWith(AndroidJUnit4::class)
class BoardSshIntegrationTest {
    @Test
    fun commandsReturnUtf8ExitStatusAndBoundedOutput() = runBlocking {
        val config = boardConnection()
        val client = BoardSshClient()
        try {
            withTimeout(60_000L) {
                client.connect(config)
                assertTrue(client.isConnected)

                val identity = client.execute("whoami")
                assertEquals(0, identity.exitCode)
                assertEquals(config.username, identity.stdout.trim())
                assertEquals("", identity.stderr)
                assertFalse(identity.truncated)

                val utf8 = client.execute("printf '开发板连接成功\\n'")
                assertEquals(0, utf8.exitCode)
                assertEquals("开发板连接成功\n", utf8.stdout)
                assertEquals("", utf8.stderr)

                val failure = client.execute("printf '正常输出\\n'; printf '错误输出\\n' >&2; exit 7")
                assertEquals(7, failure.exitCode)
                assertEquals("正常输出\n", failure.stdout)
                assertEquals("错误输出\n", failure.stderr)
                assertFalse(failure.truncated)
                assertTrue("A nonzero command exit must not disconnect SSH", client.isConnected)

                // Exceed both the retained limit and SSH's receive window. A sink that stops
                // consuming at its limit would leave this command waiting indefinitely.
                val large = client.execute(
                    "python3 -c \"import sys; " +
                        "sys.stdout.write('A' * (3 * 1024 * 1024)); " +
                        "sys.stderr.write('B' * (3 * 1024 * 1024))\"",
                )
                assertEquals(0, large.exitCode)
                assertTrue(large.truncated)
                assertEquals(32 * 1024, large.stdout.length)
                assertEquals(32 * 1024, large.stderr.length)
                assertTrue(large.stdout.all { it == 'A' })
                assertTrue(large.stderr.all { it == 'B' })
                assertTrue(client.isConnected)

                val next = client.execute("printf 'after-large-output'")
                assertEquals(0, next.exitCode)
                assertEquals("after-large-output", next.stdout)
                assertFalse(next.truncated)
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun commandTimeoutDisconnectsAndAllowsReconnect() = runBlocking {
        val config = boardConnection()
        val client = BoardSshClient()
        try {
            withTimeout(45_000L) {
                client.connect(config)
                try {
                    client.execute("sleep 2", timeoutMillis = 300L)
                    fail("Expected the sleeping command to time out")
                } catch (_: SocketTimeoutException) {
                    // The timeout must close the transport, not leave a usable stale session.
                }
                assertFalse(client.isConnected)

                client.connect(config)
                assertTrue(client.isConnected)
                val result = client.execute("whoami")
                assertEquals(0, result.exitCode)
                assertEquals(config.username, result.stdout.trim())
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun incorrectPasswordIsRejected() = runBlocking {
        val config = boardConnection()
        val client = BoardSshClient()
        try {
            withTimeout(30_000L) {
                try {
                    client.connect(config.copy(password = "invalid-${UUID.randomUUID()}"))
                    fail("SSH accepted an intentionally invalid password")
                } catch (error: JSchException) {
                    assertTrue(
                        "Expected an authentication rejection, not another connection failure",
                        error.message.orEmpty().contains("Auth fail", ignoreCase = true),
                    )
                }
                assertFalse(client.isConnected)
            }
        } finally {
            client.disconnect()
        }
    }

    private fun boardConnection(): BoardConnection {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("boardPassword")
        assumeTrue("Pass boardPassword to opt in to real-board tests", !password.isNullOrEmpty())
        return BoardConnection(
            host = arguments.getString("boardHost") ?: "172.16.16.82",
            port = 22,
            username = arguments.getString("boardUsername") ?: "topeet",
            password = requireNotNull(password),
        )
    }
}
