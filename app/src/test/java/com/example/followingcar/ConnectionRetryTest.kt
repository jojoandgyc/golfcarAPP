package com.example.followingcar

import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectionRetryTest {
    @Test
    fun firstTimeoutCanRecoverOnTheSecondConnection() = runBlocking {
        var attempts = 0
        val retryNotifications = mutableListOf<Int>()

        val result = withInitialConnectionRetry(onRetry = { retryNotifications += it }) {
            attempts += 1
            if (attempts == 1) throw SocketTimeoutException("Read timed out")
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(2, attempts)
        assertEquals(listOf(2), retryNotifications)
    }

    @Test
    fun consecutiveNetworkFailuresStopAfterTwoAttempts() = runBlocking {
        var attempts = 0
        var notifications = 0
        val failure = ConnectException("Connection refused")

        val caught = runCatching {
            withInitialConnectionRetry(onRetry = { notifications += 1 }) {
                attempts += 1
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, caught)
        assertEquals(2, attempts)
        assertEquals(1, notifications)
    }

    @Test
    fun directAndWrappedCancellationNeverRetry() = runBlocking {
        val cancelled = CancellationException("User cancelled connection")
        for (failure in listOf(cancelled, JSchException("Session.connect", cancelled))) {
            var attempts = 0
            var notifications = 0
            val caught = runCatching {
                withInitialConnectionRetry(onRetry = { notifications += 1 }) {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()

            assertSame(failure, caught)
            assertEquals(1, attempts)
            assertEquals(0, notifications)
        }
    }

    @Test
    fun cancellingDuringBackoffPreventsAnotherConnection() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val job = launch {
            withInitialConnectionRetry(onRetry = { retryStarted.complete(Unit) }) {
                attempts += 1
                throw SocketTimeoutException("Connect timed out")
            }
        }
        retryStarted.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test
    fun authenticationHostIdentityAndAlgorithmFailuresNeverRetry() = runBlocking {
        val messages = listOf(
            "Auth fail for methods 'password'",
            "reject HostKey: followingcar-board",
            "Host key has been changed",
            "Algorithm negotiation fail",
        )
        for (message in messages) {
            // A nested timeout must not override an explicit authentication or identity failure.
            val failure = JSchException(message, SocketTimeoutException("Read timed out"))
            var attempts = 0
            val caught = runCatching {
                withInitialConnectionRetry {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()

            assertSame(failure, caught)
            assertEquals(message, 1, attempts)
        }
    }

    @Test
    fun jschWrappedNetworkFailuresCanRecover() = runBlocking {
        val failures = listOf(
            JSchException("Session.connect", IOException("transport failed", SocketTimeoutException("Read timed out"))),
            JSchException("Session.connect: java.net.SocketTimeoutException: Read timed out"),
            JSchException("Session.connect", ConnectException("Connection refused")),
            JSchException("Session.connect", SocketException("Connection reset")),
        )
        for (failure in failures) {
            var attempts = 0
            val result = withInitialConnectionRetry {
                attempts += 1
                if (attempts == 1) throw failure
                "connected"
            }

            assertEquals("connected", result)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun addressPermissionAndIntentionalSocketClosureDoNotRetry() = runBlocking {
        val failures = listOf(
            UnknownHostException("Unknown board address"),
            SocketException("Permission denied"),
            SocketException("Socket closed"),
            IOException("Invalid connection configuration"),
        )
        for (failure in failures) {
            var attempts = 0
            val caught = runCatching {
                withInitialConnectionRetry {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()

            assertSame(failure, caught)
            assertEquals(1, attempts)
        }
    }
}
