package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic certificate and isolated aliases only; never reads/replaces provisioned credentials. */
@RunWith(AndroidJUnit4::class)
class RemoteCredentialsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = UUID.randomUUID()
    private val repository = RemoteCredentials(context, testId)

    @After fun cleanup() {
        repository.clear()
        File(context.cacheDir, "remote-credential-test-$testId").deleteRecursively()
    }

    @Test fun importsToNonExportableKeyAndRestoresProfileWithoutSavingPkcs12() {
        assertNull(repository.load())
        val profile = repository.importProfile(json().toString().byteInputStream())
        assertEquals("relay.example.test", profile.host)
        assertEquals(64, profile.clientCertificateSha256.length)
        val restored = RemoteCredentials(context, testId).getClientIdentity()!!
        assertEquals(profile, restored.profile)
        assertNull("AndroidKeyStore must not export private bytes", restored.privateKey.encoded)
        verifyClientKey(restored.privateKey, restored.certificateChain.first())
        assertEquals(profile.host, repository.createTlsConfig().serverName)
        val contents = File(context.cacheDir, "remote-credential-test-$testId/profile.json").readText()
        assertFalse(contents.contains("fixture-password"))
        assertFalse(contents.contains("clientPkcs12"))
        repository.clear()
        assertNull(repository.load())
    }

    @Test fun failedImportPreservesPreviouslyWorkingIdentity() {
        val original = repository.importProfile(json().toString().byteInputStream())
        val corrupted = json().put("clientPkcs12Base64", Base64.getEncoder().encodeToString("invalid".toByteArray()))
        expectSafeFailure(corrupted.toString())
        assertEquals(original, repository.load())
        verifyClientKey(repository.getClientPrivateKey()!!, repository.getClientCertificateChain()!!.first())
    }

    @Test fun strictParserRejectsDuplicateUnknownWrongTypeAndTrailingValues() {
        val good = json().toString()
        listOf(
            good.replaceFirst("{", "{\"host\":\"other.example.test\","),
            json().put("rootPassword", "do-not-echo-secret").toString(),
            json().put("controlPort", "2443").toString(),
            json().put("controlPort", 2443.5).toString(),
            json().put("schemaVersion", true).toString(),
            good + "{}",
            json().put("host", "https://relay.example.test").toString(),
            json().put("videoPort", 65536).toString(),
            json().put("clientPkcs12Base64", "do-not-echo-secret").toString(),
        ).forEach(::expectSafeFailure)
    }

    @Test fun wrongPasswordAndOversizedDocumentsNeverEchoTheirInput() {
        expectSafeFailure(json().put("clientPkcs12Password", "do-not-echo-secret").toString())
        expectSafeFailure("do-not-echo-secret".repeat(5000))
    }

    @Test fun replacedAliasIsDeletedAndMissingKeyIsNotReportedAsUnconfigured() {
        repository.importProfile(json().toString().byteInputStream())
        val profileFile = File(context.cacheDir, "remote-credential-test-$testId/profile.json")
        val oldAlias = JSONObject(profileFile.readText()).getString("keyAlias")
        repository.importProfile(json().toString().byteInputStream())
        val newAlias = JSONObject(profileFile.readText()).getString("keyAlias")
        assertNotEquals(oldAlias, newAlias)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(store.containsAlias(oldAlias))
        store.deleteEntry(newAlias)
        assertThrows(IOException::class.java) { repository.load() }
    }

    private fun json(): JSONObject = JSONObject().put("schemaVersion", 1)
        .put("host", "relay.example.test").put("controlPort", 2443).put("videoPort", 8443)
        .put("clientPkcs12Password", "fixture-password")
        .put("clientPkcs12Base64", Base64.getEncoder().encodeToString(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("remote-client-test-only.p12").use { it.readBytes() }))

    private fun expectSafeFailure(value: String) {
        val error = assertThrows(IOException::class.java) {
            repository.importProfile(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
        }
        assertFalse(error.stackTraceToString().contains("do-not-echo-secret"))
        assertFalse(error.stackTraceToString().contains("fixture-password"))
        assertNull(error.cause)
    }
}
