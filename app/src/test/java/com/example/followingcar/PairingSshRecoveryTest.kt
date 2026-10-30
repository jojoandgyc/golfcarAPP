package com.example.followingcar

import com.jcraft.jsch.JSchException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingSshRecoveryTest {
    @Test fun verifiedHotspotOverridesRemoteModeButRetainsImportedProfile() {
        val profile = RemoteProfile("car.nujabes.top", 2443, 8443, "a".repeat(64), Long.MAX_VALUE)
        val previous = BoardUiState(host = "172.16.16.82", port = "22", remoteMode = true,
            remoteProfile = profile, pairingBusy = true, pairingUnconfirmed = true)

        val local = previous.withVerifiedPairingNetwork("192.168.231.7")

        assertEquals("192.168.231.7", local.connectionHost)
        assertEquals("22", local.connectionPort)
        assertFalse(local.remoteMode)
        assertSame(profile, local.remoteProfile)
        assertFalse(local.pairingBusy)
        assertFalse(local.pairingUnconfirmed)
        assertTrue(local.pairingHotspotActive)
        assertFalse(local.connected)
    }

    @Test fun restoredNetworkDoesNotClearControlRecoveryLocksOrStartMotors() {
        val state = BoardUiState(followUncertain = true, motorActionUnconfirmed = true)
            .withVerifiedPairingNetwork("192.168.231.7")

        assertTrue(state.followUncertain)
        assertTrue(state.motorActionUnconfirmed)
        assertFalse(state.followActive)
        assertFalse(state.manualActive)
        assertFalse(state.canEnableDrive)
        assertFalse(state.canStartFollow)
    }

    @Test fun temporarySshRefusalsCanRecoverBeforeTheThirdAttempt() = runBlocking {
        var attempts = 0
        val retries = mutableListOf<Int>()
        val result = withInitialConnectionRetry(retryDelaysMillis = listOf(0L, 0L),
            onRetry = { retries += it }) {
            attempts += 1
            if (attempts < 3) throw ConnectException("Connection refused")
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(3, attempts)
        assertEquals(listOf(2, 3), retries)
    }

    @Test fun boundedHotspotAttemptsNeverLoopIndefinitely() = runBlocking {
        var attempts = 0
        val failure = SocketTimeoutException("Connect timed out")
        val caught = runCatching {
            withInitialConnectionRetry(retryDelaysMillis = listOf(0L, 0L)) {
                attempts += 1
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, caught)
        assertEquals(3, attempts)
    }

    @Test fun extraRecoveryBudgetDoesNotRetryAuthenticationOrHostKeyFailures() = runBlocking {
        for (message in listOf("Auth fail", "Host key has been changed", "Algorithm negotiation fail")) {
            var attempts = 0
            val failure = JSchException(message, SocketTimeoutException("Connect timed out"))
            val caught = runCatching {
                withInitialConnectionRetry(retryDelaysMillis = listOf(0L, 0L)) {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(1, attempts)
        }
    }

    @Test fun cancellingHotspotBackoffDoesNotOpenAnotherSshConnection() = runBlocking {
        val backoffStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val job = launch {
            withInitialConnectionRetry(retryDelaysMillis = listOf(750L, 1_500L),
                onRetry = { backoffStarted.complete(Unit) }) {
                attempts += 1
                throw ConnectException("Connection refused")
            }
        }
        backoffStarted.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test fun invalidRetryBudgetIsRejectedBeforeOpeningAnyConnection() = runBlocking {
        for (schedule in listOf(listOf(-1L), listOf(10_001L), List(4) { 0L })) {
            var attempts = 0
            val caught = runCatching {
                withInitialConnectionRetry(retryDelaysMillis = schedule) { attempts += 1 }
            }.exceptionOrNull()
            assertTrue(caught is IllegalArgumentException)
            assertEquals(0, attempts)
        }
    }
}
