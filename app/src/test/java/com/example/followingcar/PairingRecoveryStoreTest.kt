package com.example.followingcar

import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingRecoveryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = File(temporary.root, "recovery")
    private fun record(phase: PairingRecoveryPhase = PairingRecoveryPhase.PENDING) = PairingRecoveryRecord(
        "2fcd8185-14be-4a37-88f4-0c7fba167cd7", "88:49:2D:14:07:61", phase, 12345)
    private fun loaded(store: PairingRecoveryFileStore) = (store.load() as PairingRecoveryLoad.Pending).record

    @Test fun everyPhaseSurvivesRecreationAsPendingRecoveryAndSuccessDoesNotEraseIt() {
        for (phase in PairingRecoveryPhase.entries) {
            val store = PairingRecoveryFileStore(directory())
            assertTrue(store.save(record(phase)))
            assertEquals(record(phase), loaded(PairingRecoveryFileStore(directory())))
        }
        assertTrue(PairingRecoveryFileStore(directory()).clear())
        assertEquals(PairingRecoveryLoad.Empty, PairingRecoveryFileStore(directory()).load())
    }

    @Test fun firstFailureCannotBeOverwrittenByLaterStatusFailure() {
        val store = PairingRecoveryFileStore(directory())
        assertTrue(store.save(record().copy(firstFailureCode = "bluetooth_eof")))
        assertTrue(store.save(record(PairingRecoveryPhase.UNKNOWN).copy(firstFailureCode = "request_not_found")))
        assertEquals("bluetooth_eof", loaded(store).firstFailureCode)
        assertTrue(store.save(record(PairingRecoveryPhase.PAIRED)))
        assertEquals("bluetooth_eof", loaded(store).firstFailureCode)
    }

    @Test fun differentUnresolvedRequestOrTargetCannotReplaceJournal() {
        val store = PairingRecoveryFileStore(directory())
        assertTrue(store.save(record()))
        assertFalse(store.save(record().copy(requestId = "7e5d1fc0-b264-49c0-9a06-bf2cbf79e09f")))
        assertFalse(store.save(record().copy(address = "88:49:2D:14:07:62")))
        assertEquals(record(), loaded(store))
    }

    @Test fun unsentRequestCleanupCannotDeleteAnotherRequestOrCorruptRecovery() {
        val store = PairingRecoveryFileStore(directory())
        assertTrue(store.save(record()))
        assertFalse(store.clear("7e5d1fc0-b264-49c0-9a06-bf2cbf79e09f"))
        assertEquals(record(), loaded(store))
        assertTrue(store.clear(record().requestId))
        assertEquals(PairingRecoveryLoad.Empty, store.load())
        File(directory(), "request.pending").writeText("interrupted")
        assertFalse(store.clear(record().requestId))
        assertEquals(PairingRecoveryLoad.Blocked, store.load())
    }

    @Test fun failedAtomicReplaceRetainsPreviousCommittedRecord() {
        val initial = PairingRecoveryFileStore(directory())
        assertTrue(initial.save(record()))
        val failing = PairingRecoveryFileStore(directory(), atomicReplace = { _, _ -> throw IOException("disk fault") })
        assertFalse(failing.save(record(PairingRecoveryPhase.PAIRED)))
        assertEquals(record(), loaded(PairingRecoveryFileStore(directory())))
    }

    @Test fun firstWriteInterruptedBeforeCommitBlocksRestartAndCannotBeSilentlyOverwritten() {
        val failing = PairingRecoveryFileStore(directory(), atomicReplace = { _, _ -> throw IOException("disk fault") })
        assertFalse(failing.save(record()))
        val restarted = PairingRecoveryFileStore(directory())
        assertEquals(PairingRecoveryLoad.Blocked, restarted.load())
        assertFalse(restarted.save(record()))
        assertTrue(restarted.clear())
        assertEquals(PairingRecoveryLoad.Empty, restarted.load())
    }

    @Test fun directorySyncFailureNeverAcknowledgesDurability() {
        assertTrue(directory().mkdir())
        val failing = PairingRecoveryFileStore(directory(), syncDirectory = { throw IOException("sync fault") })
        assertFalse(failing.save(record()))
        // A committed record still requires recovery even though dispatch was correctly prohibited.
        assertEquals(record(), loaded(PairingRecoveryFileStore(directory())))
    }

    @Test fun corruptTruncatedOversizedAndTrailingDataAllFailClosed() {
        val store = PairingRecoveryFileStore(directory())
        assertTrue(store.save(record()))
        val file = File(directory(), "request.bin")
        val good = file.readBytes()
        val corrupt = good.clone().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        for (bytes in listOf(byteArrayOf(), good.copyOf(10), corrupt, ByteArray(1025), good + byteArrayOf(1))) {
            file.writeBytes(bytes)
            assertEquals(PairingRecoveryLoad.Blocked, store.load())
            assertFalse(store.save(record()))
        }
        file.writeBytes(good)
        assertEquals(record(), loaded(store))
    }

    @Test fun missingStorageParentAndNonDirectoryAreNotMistakenForFirstLaunch() {
        assertEquals(PairingRecoveryLoad.Blocked,
            PairingRecoveryFileStore(File(temporary.root, "missing/child")).load())
        directory().writeText("not a directory")
        val store = PairingRecoveryFileStore(directory())
        assertEquals(PairingRecoveryLoad.Blocked, store.load())
        assertFalse(store.save(record()))
        assertFalse(store.clear())
    }

    @Test fun malformedIdentifiersCannotBeWrittenAndArbitraryFailureTextIsFiltered() {
        val store = PairingRecoveryFileStore(directory())
        for (invalid in listOf(record().copy(requestId = "1-1-1-1-1"),
            record().copy(address = "192.168.1.1"), record().copy(updatedAtMs = -1))) {
            assertFalse(store.save(invalid))
        }
        assertTrue(store.save(record().copy(firstFailureCode = "private-password-123")))
        assertEquals("pairing_failed", loaded(store).firstFailureCode)
        val files = directory().listFiles()!!.joinToString { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(files.contains("private-password-123"))
        assertFalse(record().toString().contains(record().address))
        assertFalse(record().toString().contains(record().requestId))
    }

    @Test fun simultaneousInstancesNeverWriteAnUnparseableRecord() {
        val first = PairingRecoveryFileStore(directory())
        assertTrue(first.save(record()))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val workers = (1..4).map { index -> executor.submit {
                repeat(10) {
                    assertTrue(PairingRecoveryFileStore(directory()).save(record().copy(updatedAtMs = index.toLong())))
                    assertTrue(PairingRecoveryFileStore(directory()).load() is PairingRecoveryLoad.Pending)
                }
            } }
            workers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertTrue(first.load() is PairingRecoveryLoad.Pending)
    }
}
