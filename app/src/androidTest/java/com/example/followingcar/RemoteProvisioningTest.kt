package com.example.followingcar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit deployment helper: no secrets in instrumentation arguments or public storage. */
@RunWith(AndroidJUnit4::class)
class RemoteProvisioningTest {
    @Test fun importPrivateStagedIdentity() {
        assumeTrue("Explicit private provisioning opt-in required",
            InstrumentationRegistry.getArguments().getString("importRemoteProvisioning") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(app.filesDir, "remote-provisioning.json")
        assertFalse("Provisioning file must not be a symlink", Files.isSymbolicLink(staged.toPath()))
        assertTrue("Stage credentials in the application's private files directory first", staged.isFile)
        val repository = RemoteCredentials(app)
        val profile = staged.inputStream().use(repository::importProfile)
        assertEquals(profile, repository.load())
        assertNull("Only the non-exportable key handle may remain", repository.getClientPrivateKey()!!.encoded)
        assertTrue("Delete plaintext provisioning material after a successful import", staged.delete())
        assertFalse(staged.exists())
        Diagnostics.event("test.remote_provisioned", fields = mapOf("host" to profile.host), critical = true)
    }
}
