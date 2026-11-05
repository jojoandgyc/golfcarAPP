package com.example.followingcar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingCredentialWriteGateTest {
    private val binding = Any()
    private val requestId = "request-one"
    private fun lease(active: Boolean = true, owner: Any? = binding, id: String? = requestId) =
        PairingCredentialLease(active, owner, id)
    private fun gate() = PairingCredentialWriteGate(binding, requestId)

    @Test fun onlyTheSameCurrentHotspotBindingAndRequestCanBeginExactlyOnce() {
        val gate = gate()
        assertTrue(gate.matches(lease()))
        assertFalse(gate.hasStarted)
        assertTrue(gate.beginWriting(lease()))
        assertTrue(gate.hasStarted)
        assertFalse(gate.beginWriting(lease()))
    }

    @Test fun lostReplacedOrUnpreparedLeaseCannotDispatchOldCredentials() {
        for (invalid in listOf(lease(active = false), lease(owner = null), lease(owner = Any()),
            lease(id = null), lease(id = "new-request"))) {
            val gate = gate()
            assertFalse(gate.matches(invalid))
            assertFalse(gate.beginWriting(invalid))
            assertFalse(gate.hasStarted)
            // Restoring an apparently identical snapshot cannot revive this rejected submission.
            assertFalse(gate.beginWriting(lease()))
        }
    }

    @Test fun requestBindingComparisonUsesIdentityRatherThanValueEquality() {
        class EquivalentBinding {
            override fun equals(other: Any?) = other is EquivalentBinding
            override fun hashCode() = 1
        }
        val original = EquivalentBinding()
        val replacement = EquivalentBinding()
        assertEquals(original, replacement)
        assertFalse(PairingCredentialWriteGate(original, requestId)
            .beginWriting(lease(owner = replacement)))
    }

    @Test fun hotspotLossWhileDurableSaveIsSuspendedPreventsProvisionAndRecoveryQuery() = runBlocking {
        val gate = gate()
        val saving = CompletableDeferred<Unit>()
        val saveFinished = CompletableDeferred<Unit>()
        var current = lease()
        var provisionWrites = 0
        var recoveryQueries = 0
        var clearedRecord: String? = null
        val job = launch {
            try {
                saving.complete(Unit)
                saveFinished.await()
                submitPairingOnce(submit = {
                    if (!gate.beginWriting(current)) throw PairingFailure("hotspot_stopped")
                    provisionWrites++
                }, recover = { failure ->
                    if (!gate.hasStarted) throw failure
                    recoveryQueries++
                })
            } catch (_: PairingFailure) {
                gate.cancelBeforeWrite()
                if (!gate.hasStarted) clearedRecord = gate.requestId
            }
        }
        saving.await()
        // Simulated observation of the system-managed hotspot stopping during disk I/O.
        current = lease(active = false, owner = null)
        saveFinished.complete(Unit)
        job.join()
        assertEquals(0, provisionWrites)
        assertEquals(0, recoveryQueries)
        assertEquals(requestId, clearedRecord)
        assertFalse(gate.hasStarted)
    }

    @Test fun cancellationDuringDurableSaveCannotBeUndoneByAQueuedWrite() = runBlocking {
        val gate = gate()
        val saving = CompletableDeferred<Unit>()
        val saveFinished = CompletableDeferred<Unit>()
        var clearedRecord: String? = null
        val job = launch {
            try {
                saving.complete(Unit)
                saveFinished.await()
            } finally {
                gate.cancelBeforeWrite()
                if (!gate.hasStarted) clearedRecord = gate.requestId
            }
        }
        saving.await()
        job.cancelAndJoin()
        saveFinished.complete(Unit)
        assertEquals(requestId, clearedRecord)
        assertFalse(gate.beginWriting(lease()))
        assertFalse(gate.hasStarted)
    }

    @Test fun cancelledProvisionQueuedBehindBlockedIoNeverTouchesItsOutputStream() {
        val executor = Executors.newSingleThreadExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val gate = gate()
        try {
            executor.submit { blocked.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            val queued = executor.submit {
                if (gate.beginWriting(lease())) writes.incrementAndGet()
            }
            gate.cancelBeforeWrite() // Cancellation hook runs now; socket close is still queued.
            assertFalse(gate.hasStarted)
            release.countDown()
            queued.get(3, TimeUnit.SECONDS)
            assertEquals(0, writes.get())
            assertFalse(gate.hasStarted)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun leaseMustBeRecheckedOnExecutorEvenAfterSuccessfulMainThreadCheck() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = gate()
        val leaseMonitor = Any()
        var current = lease()
        try {
            assertTrue(gate.matches(current))
            val writes = executor.submit<Int> {
                // Simulates executor delay / obtaining the output stream before the write guard.
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                if (synchronized(leaseMonitor) { gate.beginWriting(current) }) 1 else 0
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            synchronized(leaseMonitor) { current = lease(active = false, owner = null) }
            release.countDown()
            assertEquals(0, writes.get(3, TimeUnit.SECONDS))
            assertFalse(gate.hasStarted)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun writeStartWinningTheRaceMustRemainUnknownAfterCancellationOrHotspotLoss() = runBlocking {
        val gate = gate()
        var provisionWrites = 0
        var recoveryQueries = 0
        val result = submitPairingOnce(submit = {
            assertTrue(gate.beginWriting(lease()))
            provisionWrites++ // A partial write is possible from this point.
            gate.cancelBeforeWrite()
            assertFalse(gate.matches(lease(active = false, owner = null)))
            throw PairingFailure("bluetooth_eof")
        }, recover = { failure ->
            if (!gate.hasStarted) throw failure
            recoveryQueries++
            "same-request-read-only"
        })
        assertEquals("same-request-read-only", result)
        assertEquals(1, provisionWrites)
        assertEquals(1, recoveryQueries)
        assertTrue(gate.hasStarted)
        assertTrue(pairingFailureRequiresConfirmation(gate.hasStarted, false))
    }

    @Test fun simultaneousCancellationAndStartHaveOnePermanentOutcome() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val gate = gate()
                val release = CountDownLatch(1)
                val start = executor.submit<Boolean> {
                    check(release.await(3, TimeUnit.SECONDS))
                    gate.beginWriting(lease())
                }
                val cancel = executor.submit {
                    check(release.await(3, TimeUnit.SECONDS))
                    gate.cancelBeforeWrite()
                }
                release.countDown()
                val started = start.get(3, TimeUnit.SECONDS)
                cancel.get(3, TimeUnit.SECONDS)
                assertEquals(started, gate.hasStarted)
                assertFalse(gate.beginWriting(lease()))
                // Cleanup may clear the journal iff the writer permanently lost the race.
                assertEquals(started, pairingFailureRequiresConfirmation(gate.hasStarted, false))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun concurrentDuplicateWorkersCanNeverResendTheProvision() {
        val executor = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        val gate = gate()
        try {
            val workers = List(2) { executor.submit<Int> {
                check(release.await(3, TimeUnit.SECONDS))
                if (gate.beginWriting(lease())) 1 else 0
            } }
            release.countDown()
            assertEquals(1, workers.sumOf { it.get(3, TimeUnit.SECONDS) })
            assertTrue(gate.hasStarted)
        } finally { executor.shutdownNow() }
    }
}
