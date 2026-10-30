package com.example.followingcar

import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated private files only: no Bluetooth, hotspot, SSH or control operation. */
@RunWith(AndroidJUnit4::class)
class PairingRecoveryPersistenceTest {
    private fun record() = PairingRecoveryRecord(UUID.randomUUID().toString(),
        "88:49:2D:14:07:61", PairingRecoveryPhase.PENDING)

    @Test fun realAndroidAtomicJournalSurvivesRecreationAndCorruptionFailsClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "pairing-recovery-test-${UUID.randomUUID()}")
        try {
            val first = PairingRecoveryStore(directory)
            assertEquals(PairingRecoveryLoad.Empty, first.load())
            val original = record()
            assertTrue(first.save(original))
            assertEquals(original, (PairingRecoveryStore(directory).load() as PairingRecoveryLoad.Pending).record)
            assertTrue(first.save(original.copy(phase = PairingRecoveryPhase.PAIRED)))
            assertEquals(PairingRecoveryPhase.PAIRED,
                (PairingRecoveryStore(directory).load() as PairingRecoveryLoad.Pending).record.phase)
            withContext(Dispatchers.IO) { File(directory, "request.bin").writeBytes(byteArrayOf(1, 2, 3)) }
            assertEquals(PairingRecoveryLoad.Blocked, PairingRecoveryStore(directory).load())
            assertFalse(first.save(original))
            assertTrue(first.clear())
            assertEquals(PairingRecoveryLoad.Empty, PairingRecoveryStore(directory).load())
        } finally {
            withContext(Dispatchers.IO) {
                check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    @Test fun slowDiskCommitDoesNotBlockMainDispatcherOrHeartbeat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "pairing-recovery-test-${UUID.randomUUID()}")
        val onMain = AtomicBoolean(true)
        val ticks = AtomicInteger(0)
        val store = PairingRecoveryStore(PairingRecoveryFileStore(directory, atomicReplace = { source, target ->
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            SystemClock.sleep(350)
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        }))
        try {
            withContext(Dispatchers.Main) {
                val heartbeat = launch { while (true) { ticks.incrementAndGet(); delay(20) } }
                try {
                    val saved = async { store.save(record()) }
                    assertTrue(saved.await())
                    assertFalse(onMain.get())
                    assertTrue("Main dispatcher stalled while saving", ticks.get() >= 5)
                } finally { heartbeat.cancel() }
            }
        } finally {
            withContext(Dispatchers.IO) {
                check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }
}
