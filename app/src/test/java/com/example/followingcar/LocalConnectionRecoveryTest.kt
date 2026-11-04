package com.example.followingcar

import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class LocalConnectionRecoveryTest {
    private val old = LocalBoardEndpoint("192.168.1.82", source = "saved")
    private val discovered = LocalBoardEndpoint("192.168.1.117")

    @Test fun successfulSavedAuthenticationDoesNotDiscoverOrTryRemote() = runBlocking {
        val result = recoverLocalConnection(old, discover = { error("unneeded discovery") },
            connectLocal = { assertEquals(old, it) }, connectRemote = { error("unneeded remote") })
        assertEquals(LocalConnectionRecoveryResult(BoardRoute.LOCAL, old), result)
    }

    @Test fun staleAddressUsesDiscoveredAuthenticatedAddress() = runBlocking {
        val attempts = mutableListOf<LocalBoardEndpoint>()
        val result = recoverLocalConnection(old, { listOf(discovered) }, {
            attempts += it
            if (it == old) throw ConnectException("Connection refused")
        }, { error("unneeded remote") })
        assertEquals(listOf(old, discovered), attempts)
        assertEquals(LocalConnectionRecoveryResult(BoardRoute.LOCAL, discovered), result)
    }

    @Test fun wrongHostKeyAtSavedIpDoesNotHideCorrectDiscoveredBoard() = runBlocking {
        val attempts = mutableListOf<LocalBoardEndpoint>()
        val result = recoverLocalConnection(old, { listOf(old, discovered) }, {
            attempts += it
            if (it.host == old.host) throw JSchException("reject HostKey: other device")
        })
        assertEquals(listOf(old, discovered), attempts)
        assertEquals(discovered, result.endpoint)
    }

    @Test fun advertisedIdAndFingerprintNeverBypassActualHostKeyAuthentication() = runBlocking {
        var remoteAttempts = 0
        val result = recoverLocalConnection(null, { listOf(discovered) }, {
            throw JSchException("reject HostKey")
        }, { remoteAttempts++ })
        assertEquals(BoardRoute.REMOTE, result.route)
        assertNull(result.endpoint)
        assertEquals(1, remoteAttempts)
    }

    @Test fun passwordRejectionStopsDiscoveryAndRemoteAttempts() = runBlocking {
        try {
            recoverLocalConnection(old, { error("Must not rediscover after correct-board password failure") }, {
                throw JSchException("Auth fail")
            }, { error("Must not resubmit the same password") })
            fail("Expected authentication failure")
        } catch (failure: JSchException) { assertEquals("Auth fail", failure.message) }
    }

    @Test fun passwordRejectionOnCandidateStopsRemainingCandidatesAndRemote() = runBlocking {
        val attempted = mutableListOf<String>()
        try {
            recoverLocalConnection(null, { listOf(discovered, old) }, {
                attempted += it.host
                throw JSchException("Auth fail", SocketTimeoutException("nested timeout"))
            }, { error("Must not retry password") })
            fail("Expected authentication failure")
        } catch (_: JSchException) { assertEquals(listOf(discovered.host), attempted) }
    }

    @Test fun failedCandidatesFallBackToRemoteOnlyOnce() = runBlocking {
        var localAttempts = 0
        var remoteAttempts = 0
        val result = recoverLocalConnection(old, { listOf(old, discovered, discovered) }, {
            localAttempts++
            throw ConnectException("refused")
        }, { remoteAttempts++ })
        assertEquals(2, localAttempts)
        assertEquals(1, remoteAttempts)
        assertEquals(BoardRoute.REMOTE, result.route)
    }

    @Test fun candidatesAreBoundedAndIpv4IsTriedFirst() = runBlocking {
        val attempted = mutableListOf<String>()
        val candidates = listOf(LocalBoardEndpoint("fe80::1%19")) + (1..12).map { LocalBoardEndpoint("192.168.1.$it") }
        recoverLocalConnection(null, { candidates }, { attempted += it.host; throw IOException("unavailable") }, {})
        assertEquals(6, attempted.size)
        assertTrue(attempted.all { ':' !in it })
    }

    @Test fun discoveryFailureCanUseConfiguredRemoteWithoutMarkingLocalOnline() = runBlocking {
        val result = recoverLocalConnection(null, { throw SecurityException("discovery unavailable") },
            { error("no candidate") }, {})
        assertEquals(LocalConnectionRecoveryResult(BoardRoute.REMOTE), result)
    }

    @Test fun localAttemptDeadlineCancelsAttemptBeforeFallback() = runBlocking {
        var attemptClosed = false
        var remoteAttempts = 0
        val result = recoverLocalConnection(old, { emptyList() }, {
            try { awaitCancellation() } finally { attemptClosed = true }
        }, {
            assertTrue(attemptClosed)
            remoteAttempts++
        }, localTimeoutMillis = 20)
        assertEquals(BoardRoute.REMOTE, result.route)
        assertEquals(1, remoteAttempts)
    }

    @Test fun callerCancellationNeverStartsDiscoveryOrFallback() = runBlocking {
        var attemptClosed = false
        val result = withTimeoutOrNull(20) {
            recoverLocalConnection(old, { error("cancelled discovery") }, {
                try { awaitCancellation() } finally { attemptClosed = true }
            }, { error("cancelled remote") })
        }
        assertNull(result)
        assertTrue(attemptClosed)
    }

    @Test fun cancellationDuringDiscoveryDoesNotFallBack() = runBlocking {
        try {
            recoverLocalConnection(null, { throw CancellationException("cancel") },
                { error("cancelled candidate") }, { error("cancelled remote") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun remoteIdentityFailureIsPropagatedWithoutRetry() = runBlocking {
        var attempts = 0
        try {
            recoverLocalConnection(null, { emptyList() }, {}, {
                attempts++
                throw SSLPeerUnverifiedException("relay certificate mismatch")
            })
            fail("Expected identity failure")
        } catch (_: SSLPeerUnverifiedException) { assertEquals(1, attempts) }
    }

    @Test fun remoteDeadlineIsBoundedAndCancellationClosesItsAttempt() = runBlocking {
        var closed = false
        try {
            recoverLocalConnection(null, { emptyList() }, {}, {
                try { awaitCancellation() } finally { closed = true }
            }, remoteTimeoutMillis = 20)
            fail("Expected timeout")
        } catch (_: SocketTimeoutException) { assertTrue(closed) }
    }

    @Test fun allFailuresWithoutRemoteRemainFailureAndNeverYieldAnEndpoint() = runBlocking {
        try {
            recoverLocalConnection(null, { listOf(discovered) }, { throw JSchException("reject HostKey") })
            fail("Wrong-key candidate cannot become a successful result")
        } catch (_: JSchException) { /* expected */ }
    }

    @Test fun configurationFailureDoesNotKeepTrying() = runBlocking {
        try {
            recoverLocalConnection(old, { error("no discovery for invalid configuration") },
                { throw IllegalArgumentException("invalid config") }, { error("no remote") })
            fail("Expected configuration failure")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
