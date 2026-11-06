package com.example.followingcar

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses only the connection page and read-only connection status commands. */
class RemoteConnectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun remoteSelectionConnectsWithoutReplacingSavedLanAddress() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteRelayUi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = (compose.activity.application as FollowingCarApplication).boardViewModel
        compose.waitUntil(15_000) { !model.state.value.remoteProfileLoading }
        assumeTrue("Start with an idle disconnected shared owner", model.state.value.canRecoverConnection)
        assertNotNull("Provision the emulator identity first", model.state.value.remoteProfile)
        val lease = SharedOwnerSshTestLease(model)
        val localHost = model.state.value.host
        val localPort = model.state.value.port
        try {
            compose.onNodeWithTag("pageConnection").performClick()
            compose.onNodeWithTag("connectionRemoteMode").performScrollTo().performClick()
            compose.waitUntil { model.state.value.remoteMode }
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            compose.waitUntil(60_000) { !model.state.value.busy }
            lease.rememberEstablishedConnection()
            assertTrue("Remote connection failed: ${model.state.value.error}", model.state.value.connected)
            assertEquals(localHost, model.state.value.host)
            assertEquals(localPort, model.state.value.port)
            assertFalse(model.state.value.manualActive)
            assertFalse(model.state.value.followActive)
            compose.onNodeWithTag("connectionRemoteMode").performScrollTo()
            compose.waitForIdle()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "remote-connection-ui.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        } finally {
            runBlocking { lease.closeIfStillOwnedAndIdle() }
        }
    }
}
