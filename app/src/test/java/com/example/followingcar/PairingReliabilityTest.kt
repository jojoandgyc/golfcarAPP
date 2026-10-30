package com.example.followingcar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PairingReliabilityTest {
    @Test fun aFreshConnectionCanRecoverAfterTwoTransportFailures() = runBlocking {
        var attempts = 0
        val retryNumbers = mutableListOf<Int>()
        val result = withPairingReadRetry(delays = listOf(0L, 0L),
            onRetry = { next, _ -> retryNumbers += next }) {
            attempts += 1
            if (attempts == 1) throw PairingFailure("bluetooth_failed")
            if (attempts == 2) throw PairingFailure("bluetooth_eof")
            "status"
        }
        assertEquals("status", result)
        assertEquals(3, attempts)
        assertEquals(listOf(2, 3), retryNumbers)
    }

    @Test fun exhaustedTransportRetriesPreserveTheLastFailure() = runBlocking {
        var attempts = 0
        val failure = PairingFailure("partial_reply")
        val caught = runCatching {
            withPairingReadRetry(delays = listOf(0L, 0L)) {
                attempts += 1
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(3, attempts)
    }

    @Test fun permissionsMalformedProtocolAndControlDenialDoNotRetryStatus() = runBlocking {
        val failures = listOf(SecurityException("private platform detail"),
            PairingFailure("permission_denied"), PairingFailure("protocol_error"),
            PairingFailure("board_busy"), PairingFailure("bond_failed"),
            PairingFailure("bluetooth_disabled"))
        for (failure in failures) {
            var attempts = 0
            val caught = runCatching {
                withPairingReadRetry(delays = listOf(0L, 0L)) {
                    attempts += 1
                    throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(1, attempts)
        }
    }

    @Test fun scopedReadTimeoutCanRetryWithoutTreatingItAsUserCancellation() = runBlocking {
        var attempts = 0
        val result = withPairingReadRetry(delays = listOf(0L)) {
            attempts += 1
            if (attempts == 1) withTimeout(1L) { delay(1_000L) }
            "ready"
        }
        assertEquals("ready", result)
        assertEquals(2, attempts)
    }

    @Test fun cancellingBackoffPreventsTheNextBluetoothConnection() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val job = launch {
            withPairingReadRetry(delays = listOf(5_000L),
                onRetry = { _, _ -> retryStarted.complete(Unit) }) {
                attempts += 1
                throw PairingFailure("bluetooth_failed")
            }
        }
        retryStarted.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test fun outerTimeoutTakesPriorityOverReadRetries() = runBlocking {
        var attempts = 0
        var retries = 0
        val caught = runCatching {
            withTimeout(10L) {
                withPairingReadRetry(delays = listOf(0L, 0L), onRetry = { _, _ -> retries += 1 }) {
                    attempts += 1
                    delay(1_000L)
                }
            }
        }.exceptionOrNull()
        assertTrue(caught is TimeoutCancellationException)
        assertEquals(1, attempts)
        assertEquals(0, retries)
    }

    @Test fun lostSubmitAckOnlyQueriesTheOriginalRequestUntilItCompletes() = runBlocking {
        var submissions = 0
        var queries = 0
        var pendingNotifications = 0
        val lostAck = PairingFailure("bluetooth_eof")
        val result = submitPairingOnce(submit = {
            submissions += 1
            throw lostAck
        }, recover = { failure ->
            assertSame(lostAck, failure)
            awaitPairingRecovery(timeoutMs = 1_000L, intervalMs = 1L,
                onPending = { pendingNotifications += 1 }) {
                queries += 1
                if (queries < 3) null else "verified-original-request"
            }
        })
        assertEquals("verified-original-request", result)
        assertEquals(1, submissions)
        assertEquals(3, queries)
        assertEquals(2, pendingNotifications)
    }

    @Test fun successfulSubmitDoesNotStartRecovery() = runBlocking {
        var recoveryQueries = 0
        val result = submitPairingOnce(submit = { "verified" }, recover = { recoveryQueries += 1; "unexpected" })
        assertEquals("verified", result)
        assertEquals(0, recoveryQueries)
    }

    @Test fun malformedSubmitResponseUsesReadOnlyRecoveryRatherThanResubmission() = runBlocking {
        for (code in listOf("protocol_error", "empty_reply", "invalid_reply_encoding", "reply_too_large",
            "network_address_unconfirmed", "address_unverified", "provisioning_pending", "hotspot_stopped")) {
            var submissions = 0
            var queries = 0
            val result = submitPairingOnce(submit = {
                submissions += 1
                throw PairingFailure(code)
            }, recover = {
                queries += 1
                "resolved"
            })
            assertEquals("resolved", result)
            assertEquals(1, submissions)
            assertEquals(1, queries)
        }
    }

    @Test fun knownDefinitiveOrPermissionFailureDoesNotEnterRecovery() = runBlocking {
        for (failure in listOf(PairingFailure("board_busy"), PairingFailure("permission_denied"),
            PairingFailure("hotspot_denied"), CancellationException("user stopped waiting"))) {
            var recoveries = 0
            val caught = runCatching {
                submitPairingOnce(submit = { throw failure }, recover = { recoveries += 1 })
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(0, recoveries)
        }
    }

    @Test fun innerAckTimeoutRecoversButOuterCancellationDoesNot() = runBlocking {
        var recoveries = 0
        val recovered = submitPairingOnce(submit = {
            withTimeout(1L) { delay(1_000L); "unreachable" }
        }, recover = { recoveries += 1; "resolved" })
        assertEquals("resolved", recovered)
        assertEquals(1, recoveries)

        val caught = runCatching {
            withTimeout(10L) {
                submitPairingOnce(submit = { delay(1_000L); "unreachable" },
                    recover = { recoveries += 1; "unexpected" })
            }
        }.exceptionOrNull()
        assertTrue(caught is TimeoutCancellationException)
        assertEquals(1, recoveries)
    }

    @Test fun unconfirmedRecoveryStopsWithUnknownOutcomeWithoutResending() = runBlocking {
        var submissions = 0
        var queries = 0
        val caught = runCatching {
            submitPairingOnce(submit = {
                submissions += 1
                throw PairingFailure("bluetooth_eof")
            }, recover = {
                awaitPairingRecovery<String>(timeoutMs = 15L, intervalMs = 1L) {
                    queries += 1
                    null
                }
            })
        }.exceptionOrNull()
        assertTrue(caught is PairingFailure)
        assertEquals("recovery_timeout", (caught as PairingFailure).code)
        assertEquals(1, submissions)
        assertTrue(queries >= 1)
    }

    @Test fun userCancellationDuringRecoveryStopsPollingWithoutSynthesizingTimeout() = runBlocking {
        val queryEntered = CompletableDeferred<Unit>()
        var queries = 0
        var observed: Throwable? = null
        val job = launch {
            try {
                awaitPairingRecovery<String>(timeoutMs = 10_000L, intervalMs = 5_000L) {
                    queries += 1
                    queryEntered.complete(Unit)
                    null
                }
            } catch (failure: Throwable) {
                observed = failure
                throw failure
            }
        }
        queryEntered.await()
        job.cancelAndJoin()
        assertEquals(1, queries)
        assertTrue(observed is CancellationException)
        assertFalse(observed is PairingFailure)
    }

    @Test fun retryBudgetValidationHappensBeforeAccessingBluetooth() = runBlocking {
        for (schedule in listOf(listOf(-1L), listOf(5_001L), List(4) { 0L })) {
            var calls = 0
            val caught = runCatching {
                withPairingReadRetry(delays = schedule) { calls += 1 }
            }.exceptionOrNull()
            assertTrue(caught is IllegalArgumentException)
            assertEquals(0, calls)
        }
    }
}
