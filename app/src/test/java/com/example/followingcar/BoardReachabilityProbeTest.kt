package com.example.followingcar

import com.jcraft.jsch.JSchException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class BoardReachabilityProbeTest {
    private val config = BoardConnection("192.0.2.10", 22, "example", "test-only")
    private val diagnostic = DiagnosticContext("presence-test")

    @Test fun onlyCompletedAuthenticationProducesOnlineAndClosesOwnConnection() = runBlocking {
        var authenticated = false
        var closed = 0
        val connection = object : BoardPresenceConnection {
            override suspend fun authenticate(config: BoardConnection) { authenticated = true }
            override fun close() { closed++ }
        }
        val result = BoardReachabilityProbe({ connection }, { 123L }).check(config, diagnostic)
        assertTrue(authenticated)
        assertTrue(result.isFreshOnline(123))
        assertEquals(1, closed)
    }

    @Test fun authenticationFailureDoesNotReportOnlineAndDoesNotExposeProviderMessage() = runBlocking {
        val secret = "sensitive-test-string"
        var closed = false
        val connection = object : BoardPresenceConnection {
            override suspend fun authenticate(config: BoardConnection) { throw JSchException("Auth fail $secret") }
            override fun close() { closed = true }
        }
        val result = BoardReachabilityProbe({ connection }, { 123L }).check(config, diagnostic)
        assertEquals(ReachabilityFailure.AUTHENTICATION, result.failure)
        assertFalse(result.isFreshOnline(123))
        assertFalse(result.toString().contains(secret))
        assertTrue(closed)
    }

    @Test fun localProbeDeadlineReturnsTimeoutAndClosesOnlyOwnedConnection() = runBlocking {
        var closed = 0
        val connection = object : BoardPresenceConnection {
            override suspend fun authenticate(config: BoardConnection): Unit = awaitCancellation()
            override fun close() { closed++ }
        }
        val result = BoardReachabilityProbe({ connection }, { 123L }, timeoutMillis = 20).check(config, diagnostic)
        assertEquals(ReachabilityFailure.TIMEOUT, result.failure)
        assertEquals(1, closed)
    }

    @Test fun callerCancellationPropagatesInsteadOfBecomingAnOfflineResult() = runBlocking {
        var closed = 0
        val connection = object : BoardPresenceConnection {
            override suspend fun authenticate(config: BoardConnection) { throw CancellationException("caller cancelled") }
            override fun close() { closed++ }
        }
        try {
            BoardReachabilityProbe({ connection }, { 123L }).check(config, diagnostic)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
        assertEquals(1, closed)
    }

    @Test fun outerDeadlineIsNotMistakenForTheProbeDeadline() = runBlocking {
        var closed = false
        val connection = object : BoardPresenceConnection {
            override suspend fun authenticate(config: BoardConnection): Unit = awaitCancellation()
            override fun close() { closed = true }
        }
        var returned = false
        val result = withTimeoutOrNull(20) {
            BoardReachabilityProbe({ connection }, { 123L }, timeoutMillis = 5_000).check(config, diagnostic)
            returned = true
        }
        assertNull(result)
        assertFalse(returned)
        assertTrue(closed)
    }

    @Test fun classificationsStayBoundedAndDistinguishIdentityFromPathFailure() {
        assertEquals(ReachabilityFailure.IDENTITY, classifyReachabilityFailure(SSLPeerUnverifiedException("untrusted")))
        assertEquals(ReachabilityFailure.IDENTITY, classifyReachabilityFailure(SSLHandshakeException("Received fatal alert: bad_certificate")))
        assertEquals(ReachabilityFailure.UNREACHABLE, classifyReachabilityFailure(SSLHandshakeException("Remote host terminated the handshake")))
        assertEquals(ReachabilityFailure.IDENTITY, classifyReachabilityFailure(JSchException("reject HostKey")))
        assertEquals(ReachabilityFailure.TIMEOUT, classifyReachabilityFailure(SocketTimeoutException("timeout")))
        assertEquals(ReachabilityFailure.UNREACHABLE, classifyReachabilityFailure(ConnectException("refused")))
        assertEquals(ReachabilityFailure.CONFIGURATION, classifyReachabilityFailure(IllegalArgumentException("invalid")))
    }
}
