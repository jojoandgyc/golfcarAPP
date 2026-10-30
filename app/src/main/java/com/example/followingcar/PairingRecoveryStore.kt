package com.example.followingcar

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class PairingRecoveryPhase { PENDING, UNKNOWN, PAIRED }

/** This journal intentionally cannot represent credentials, network addresses or command text. */
internal data class PairingRecoveryRecord(
    val requestId: String,
    val address: String,
    val phase: PairingRecoveryPhase,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val firstFailureCode: String? = null,
) {
    override fun toString() = "PairingRecoveryRecord(phase=$phase, hasFailure=${firstFailureCode != null})"
}

internal sealed interface PairingRecoveryLoad {
    data object Empty : PairingRecoveryLoad
    /** Every saved phase requires read-only reconciliation after process recreation. */
    data class Pending(val record: PairingRecoveryRecord) : PairingRecoveryLoad
    /** Missing/unreadable directory, malformed data and interrupted writes fail closed. */
    data object Blocked : PairingRecoveryLoad
}

/** All disk operations run off the main thread. A false save result forbids credential dispatch. */
internal class PairingRecoveryStore(private val files: PairingRecoveryFileStore) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "pairing-recovery"))
    constructor(directory: File) : this(PairingRecoveryFileStore(directory, syncDirectory = { parent ->
        val descriptor = Os.open(parent.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }))

    suspend fun load(): PairingRecoveryLoad = withContext(Dispatchers.IO) { files.load() }
    suspend fun save(record: PairingRecoveryRecord): Boolean = withContext(Dispatchers.IO) { files.save(record) }
    suspend fun clear(expectedRequestId: String? = null): Boolean =
        withContext(Dispatchers.IO) { files.clear(expectedRequestId) }
}

/** Plain JVM file core: bounded, checksummed, atomically replaced, and serialized across instances. */
internal class PairingRecoveryFileStore(
    private val directory: File,
    private val atomicReplace: (File, File) -> Unit = { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
    private val syncDirectory: (File) -> Unit = {},
) {
    private val recordFile get() = File(directory, "request.bin")
    private val pendingFile get() = File(directory, "request.pending")

    fun load(): PairingRecoveryLoad = synchronized(FILE_LOCK) {
        try {
            if (!exists(directory)) {
                // A missing dedicated journal directory is the normal first launch, not a reset.
                if (directory.parentFile?.isDirectory != true) return@synchronized PairingRecoveryLoad.Blocked
                return@synchronized PairingRecoveryLoad.Empty
            }
            requireDirectory()
            if (!exists(recordFile)) {
                return@synchronized if (exists(pendingFile))
                    PairingRecoveryLoad.Blocked else PairingRecoveryLoad.Empty
            }
            requireRegular(recordFile)
            val bytes = recordFile.inputStream().use { stream ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var offset = 0
                while (offset < buffer.size) {
                    val size = stream.read(buffer, offset, buffer.size - offset)
                    if (size < 0) break
                    offset += size
                }
                require(offset <= MAX_BYTES)
                buffer.copyOf(offset)
            }
            PairingRecoveryLoad.Pending(decode(bytes))
        } catch (_: Exception) { PairingRecoveryLoad.Blocked }
    }

    fun save(record: PairingRecoveryRecord): Boolean = synchronized(FILE_LOCK) {
        try {
            val existing = load()
            require(existing != PairingRecoveryLoad.Blocked)
            val previous = (existing as? PairingRecoveryLoad.Pending)?.record
            require(previous == null || (previous.requestId == record.requestId && previous.address == record.address))
            val payload = encode(record.copy(firstFailureCode = previous?.firstFailureCode ?: record.firstFailureCode))
            if (!exists(directory)) {
                check(directory.mkdir())
                syncDirectory(requireNotNull(directory.parentFile))
            }
            requireDirectory()
            if (exists(recordFile)) requireRegular(recordFile)
            if (exists(pendingFile)) requireRegular(pendingFile)
            FileOutputStream(pendingFile, false).use { stream ->
                stream.write(payload)
                stream.flush()
                stream.fd.sync()
            }
            atomicReplace(pendingFile, recordFile)
            syncDirectory(directory)
            true
        } catch (_: Exception) {
            // Preserve both the previous committed record and any interrupted write for recovery.
            false
        }
    }

    /** Caller must already have confirmed failure or explicitly closed its known hotspot. */
    fun clear(expectedRequestId: String? = null): Boolean = synchronized(FILE_LOCK) {
        try {
            if (expectedRequestId != null) {
                when (val existing = load()) {
                    PairingRecoveryLoad.Empty -> return@synchronized true
                    PairingRecoveryLoad.Blocked -> return@synchronized false
                    is PairingRecoveryLoad.Pending -> require(existing.record.requestId == expectedRequestId)
                }
            }
            if (!exists(directory))
                return@synchronized directory.parentFile?.isDirectory == true
            requireDirectory()
            for (file in listOf(pendingFile, recordFile)) {
                if (exists(file)) {
                    requireRegular(file)
                    Files.delete(file.toPath())
                }
            }
            syncDirectory(directory)
            true
        } catch (_: Exception) { false }
    }

    /** Files.exists suppresses IO/permission failures; only a real ENOENT means absent here. */
    private fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) { false }

    private fun requireDirectory() {
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(directory.toPath()))
    }

    private fun requireRegular(file: File) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(file.toPath()))
    }

    private fun encode(record: PairingRecoveryRecord): ByteArray {
        validate(record)
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(record.requestId)
                output.writeUTF(record.address)
                output.writeUTF(record.phase.name)
                output.writeLong(record.updatedAtMs)
                output.writeUTF(safeFailureCode(record.firstFailureCode) ?: "")
            }
        }.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private fun decode(bytes: ByteArray): PairingRecoveryRecord {
        require(bytes.size in 33..MAX_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        val digest = bytes.copyOfRange(bytes.size - 32, bytes.size)
        require(MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(payload)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val record = PairingRecoveryRecord(input.readUTF(), input.readUTF(),
                PairingRecoveryPhase.valueOf(input.readUTF()), input.readLong(), input.readUTF().ifEmpty { null })
            require(input.read() == -1)
            validate(record)
            require(safeFailureCode(record.firstFailureCode) == record.firstFailureCode)
            record
        }
    }

    private fun validate(record: PairingRecoveryRecord) {
        require(UUID.fromString(record.requestId).toString() == record.requestId)
        require(record.address.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")))
        require(record.updatedAtMs >= 0)
    }

    private fun safeFailureCode(code: String?): String? = when (code) {
        null -> null
        in SAFE_FAILURE_CODES -> code
        else -> "pairing_failed"
    }

    companion object {
        private val FILE_LOCK = Any()
        private const val MAGIC = 0x50414952
        private const val VERSION = 1
        private const val MAX_BYTES = 1024
        private val SAFE_FAILURE_CODES = setOf(
            "pairing_failed", "bluetooth_failed", "bluetooth_timeout", "bluetooth_eof", "partial_reply",
            "protocol_error", "permission_denied", "bluetooth_disabled", "bond_failed", "bond_timeout",
            "hotspot_stopped", "address_unverified", "request_not_found", "provisioning_pending",
            "network_address_unconfirmed", "network_restored", "cancelled", "state_changed",
            "outcome_unknown_after_restart", "recovery_storage_failed", "networkmanager_command_failed",
            "command_timeout", "board_failed", "hotspot_not_visible_2g", "hotspot_unavailable",
            "network_superseded", "hotspot_disconnected", "request_expired", "recovery_pending",
            "recovery_timeout", "recovery_unavailable", "current_network_unconfirmed", "hotspot_timeout",
            "recovery_timeout", "network_superseded", "empty_reply", "invalid_reply_encoding", "reply_too_large",
        )
    }
}
