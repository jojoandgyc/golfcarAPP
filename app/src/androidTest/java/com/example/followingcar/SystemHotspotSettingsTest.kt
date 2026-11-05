package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic credentials and aliases only. Never opens settings or enables networking. */
@RunWith(AndroidJUnit4::class)
class SystemHotspotSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = UUID.randomUUID()
    private val directory = File(context.noBackupFilesDir, "system-hotspot-test-$testId")
    private val encryptedFile = File(directory, "credentials.enc")
    private val keyAlias = "followingcar.system-hotspot.test.$testId"
    private fun repository() = SystemHotspotCredentialStore(context, testId)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanup() {
        keyStore().deleteEntry(keyAlias)
        check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        directory.deleteRecursively()
    }

    @Test fun newStoreHasNoDefaultPasswordOrCreatedKey() = runBlocking {
        assertNull(repository().load())
        assertFalse(keyStore().containsAlias(keyAlias))
        assertFalse(directory.exists())
    }

    @Test fun encryptedCredentialsSurviveStoreRecreationUsingNonExportableKey() = runBlocking {
        val expected = SystemHotspotCredentials("FollowingCar-测试", "fixture-hotspot-42")
        assertTrue(repository().save(expected))
        val firstCiphertext = encryptedFile.readBytes()
        val restored = requireNotNull(repository().load())
        assertEquals(expected.ssid, restored.ssid)
        assertEquals(expected.password, restored.password)
        assertNull("AndroidKeyStore must not export the AES key", keyStore().getKey(keyAlias, null).encoded)
        assertTrue(repository().save(expected))
        assertFalse("Each encryption must use a fresh IV", firstCiphertext.contentEquals(encryptedFile.readBytes()))
        val reloaded = requireNotNull(repository().load())
        assertEquals(expected.ssid, reloaded.ssid)
        assertEquals(expected.password, reloaded.password)
    }

    @Test fun onlyCiphertextIsStoredUnderNoBackupDirectory() = runBlocking {
        val credentials = SystemHotspotCredentials("private-hotspot-fixture", "private-password-fixture42")
        assertTrue(repository().save(credentials))
        assertEquals(context.noBackupFilesDir.canonicalFile, directory.canonicalFile.parentFile)
        assertEquals(directory.canonicalFile, encryptedFile.canonicalFile.parentFile)
        assertFalse(encryptedFile.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertFalse(encryptedFile.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        val files = requireNotNull(directory.listFiles()).filter { it.isFile }
        assertEquals(listOf("credentials.enc"), files.map { it.name })
        files.forEach { file ->
            val content = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("SSID must never be saved as plaintext", content.contains(credentials.ssid))
            assertFalse("Password must never be saved as plaintext", content.contains(credentials.password))
        }
    }

    @Test fun damagedTamperedAndOversizedFilesReturnNullWithoutThrowing() = runBlocking {
        assertTrue(repository().save(SystemHotspotCredentials("fixture-hotspot", "fixture-password42")))
        val original = encryptedFile.readBytes()
        val tampered = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val unknownFormat = original.copyOf().also { it[4] = 2 }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), tampered, unknownFormat,
            original + byteArrayOf(1), ByteArray(1025))) {
            encryptedFile.writeBytes(bytes)
            assertNull(repository().load())
        }
        // Recovery requires explicit user input; a failed read never invents credentials.
        val replacement = SystemHotspotCredentials("replacement-hotspot", "replacement-password42")
        assertTrue(repository().save(replacement))
        assertEquals(replacement.ssid, repository().load()?.ssid)
    }

    @Test fun missingKeystoreKeyDoesNotSilentlyRegenerateOnLoad() = runBlocking {
        assertTrue(repository().save(SystemHotspotCredentials("fixture-hotspot", "fixture-password42")))
        keyStore().deleteEntry(keyAlias)
        assertNull(repository().load())
        assertFalse(keyStore().containsAlias(keyAlias))
    }

    @Test fun invalidSavePreservesPreviouslyCommittedCredentials() = runBlocking {
        val original = SystemHotspotCredentials("existing-hotspot", "existing-password42")
        assertTrue(repository().save(original))
        val bytes = encryptedFile.readBytes()
        assertFalse(repository().save(SystemHotspotCredentials("", "")))
        assertTrue(bytes.contentEquals(encryptedFile.readBytes()))
        assertEquals(original.ssid, repository().load()?.ssid)
        assertEquals(original.password, repository().load()?.password)
    }

    @Test fun credentialToStringRedactsBothSsidAndPassword() {
        val credentials = SystemHotspotCredentials("private-ssid-fixture", "private-password-fixture42")
        val rendered = credentials.toString()
        assertFalse(rendered.contains(credentials.ssid))
        assertFalse(rendered.contains(credentials.password))
        assertEquals("SystemHotspotCredentials(ssid=[REDACTED], password=[REDACTED])", rendered)
    }
}
