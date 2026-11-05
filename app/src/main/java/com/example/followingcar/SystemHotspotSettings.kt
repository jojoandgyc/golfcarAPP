package com.example.followingcar

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** User-entered system-hotspot credentials; neither value is suitable for logs. */
internal class SystemHotspotCredentials(val ssid: String, val password: String) {
    override fun toString(): String = "SystemHotspotCredentials(ssid=[REDACTED], password=[REDACTED])"
}

/** Non-exportable key, authenticated ciphertext, and no system-backup copy of either credential. */
internal class SystemHotspotCredentialStore private constructor(
    private val file: AtomicFile,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(
        AtomicFile(File(context.applicationContext.noBackupFilesDir, "system-hotspot/credentials.enc")),
        "followingcar.system-hotspot.credentials.v1",
    )

    /** Separate files AND keystore alias: instrumentation never reads or replaces real credentials. */
    internal constructor(context: Context, testId: UUID) : this(
        AtomicFile(File(context.applicationContext.noBackupFilesDir,
            "system-hotspot-test-$testId/credentials.enc")),
        "followingcar.system-hotspot.test.$testId",
    )

    suspend fun load(): SystemHotspotCredentials? = withContext(Dispatchers.IO) {
        FILE_LOCK.withLock {
            var plaintext: ByteArray? = null
            try {
                val encrypted = readCiphertext()
                require(encrypted.size >= HEADER.size + IV_BYTES + TAG_BYTES)
                require(encrypted.copyOfRange(0, HEADER.size).contentEquals(HEADER))
                val key = existingKey() ?: return@withLock null
                val iv = encrypted.copyOfRange(HEADER.size, HEADER.size + IV_BYTES)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
                cipher.updateAAD(AAD)
                cipher.updateAAD(HEADER)
                plaintext = cipher.doFinal(encrypted, HEADER.size + IV_BYTES,
                    encrypted.size - HEADER.size - IV_BYTES)
                decode(plaintext)
            } catch (_: Exception) {
                // Missing, corrupt, unauthenticated or keyless data is not usable configuration.
                // No defaults, key regeneration, credential logging or network operations on load.
                null
            } finally {
                plaintext?.fill(0)
            }
        }
    }

    suspend fun save(credentials: SystemHotspotCredentials): Boolean = withContext(Dispatchers.IO) {
        FILE_LOCK.withLock {
            var plaintext: ByteArray? = null
            var stream: FileOutputStream? = null
            try {
                require(validPairingCredentials(credentials.ssid, credentials.password))
                plaintext = encode(credentials)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
                cipher.updateAAD(AAD)
                cipher.updateAAD(HEADER)
                val iv = cipher.iv
                require(iv.size == IV_BYTES)
                val ciphertext = HEADER + iv + cipher.doFinal(plaintext)
                require(ciphertext.size <= MAX_FILE_BYTES)
                val directory = requireNotNull(file.baseFile.parentFile)
                check(directory.isDirectory || directory.mkdirs())
                stream = file.startWrite()
                stream.write(ciphertext)
                stream.flush()
                stream.fd.sync()
                file.finishWrite(stream)
                stream = null
                // AtomicFile may log a failed rename rather than throw. Never report an
                // older committed value as a successful save of these credentials.
                readCiphertext().contentEquals(ciphertext)
            } catch (_: Exception) {
                stream?.let { pending -> runCatching { file.failWrite(pending) } }
                false
            } finally {
                plaintext?.fill(0)
            }
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(keyAlias)) return null
        return (store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Unexpected hotspot key type")
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build())
    }.generateKey()

    private fun readCiphertext(): ByteArray = file.openRead().use { input ->
        val bytes = ByteArray(MAX_FILE_BYTES + 1)
        var count = 0
        while (count < bytes.size) {
            val size = input.read(bytes, count, bytes.size - count)
            if (size < 0) break
            count += size
        }
        require(count <= MAX_FILE_BYTES)
        bytes.copyOf(count)
    }

    private fun encode(credentials: SystemHotspotCredentials): ByteArray {
        val ssid = credentials.ssid.toByteArray(Charsets.UTF_8)
        val password = credentials.password.toByteArray(Charsets.UTF_8)
        return try {
            ByteBuffer.allocate(4 + ssid.size + password.size)
                .putShort(ssid.size.toShort()).put(ssid)
                .putShort(password.size.toShort()).put(password).array()
        } finally {
            ssid.fill(0)
            password.fill(0)
        }
    }

    private fun decode(plaintext: ByteArray): SystemHotspotCredentials {
        val input = ByteBuffer.wrap(plaintext)
        fun readString(maxBytes: Int): String {
            require(input.remaining() >= 2)
            val size = input.short.toInt() and 0xffff
            require(size in 1..maxBytes && input.remaining() >= size)
            val encoded = input.slice().apply { limit(size) }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(encoded).toString()
            input.position(input.position() + size)
            return text
        }
        val credentials = SystemHotspotCredentials(readString(32), readString(64))
        require(!input.hasRemaining() && validPairingCredentials(credentials.ssid, credentials.password))
        return credentials
    }

    private companion object {
        val FILE_LOCK = Mutex()
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_FILE_BYTES = 1024
        val HEADER = byteArrayOf(0x46, 0x43, 0x53, 0x48, 1) // FCSH, format version 1.
        val AAD = "com.example.followingcar.system-hotspot.v1".toByteArray(Charsets.UTF_8)
    }
}

/** Opens a settings screen only; users remain responsible for enabling/configuring tethering. */
internal object SystemHotspotSettings {
    fun open(context: Context): Boolean {
        for (action in listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS)) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) == null) continue
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // OEMs may resolve a settings activity but deny its launch; try the public fallback.
            }
        }
        return false
    }
}
