package com.example.followingcar

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The production button must only query status, including when used repeatedly. */
class BluetoothStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun repeatedBluetoothChecksUseProductionPathWithoutProvisioning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runBluetoothStatus") == "true")
        assumeTrue(args.getString("confirmedControlIdle") == "true")
        val model = ViewModelProvider(compose.activity)[BoardViewModel::class.java]
        compose.waitUntil(15_000) { !model.state.value.remoteProfileLoading }
        assertTrue(model.state.value.canManagePairing)
        assertFalse(model.pairing.state.value.hotspotActive)
        assertFalse(model.pairing.state.value.confirmationRequired)
        val oldHost = model.state.value.host
        compose.onNodeWithTag("pageConnection").performClick()
        compose.onNodeWithTag("connectionLanMode").performScrollTo().performClick()
        repeat(3) {
            // Let Android release the previous idle ACL; test fresh radio establishment,
            // rather than passing three times through one still-open physical connection.
            if (it > 0) android.os.SystemClock.sleep(6_500)
            compose.onNodeWithTag("checkBluetooth").performScrollTo().performClick()
            compose.waitUntil(45_000) { !model.pairing.state.value.busy }
            assertNull("Bluetooth check failed", model.pairing.state.value.error)
            assertEquals("蓝牙服务正常，可开始配网", model.pairing.state.value.status)
            assertFalse(model.pairing.state.value.hotspotActive)
            assertFalse(model.pairing.state.value.confirmationRequired)
            assertFalse(model.state.value.connected)
            assertFalse(model.state.value.followActive)
            assertFalse(model.state.value.manualActive)
            assertEquals(oldHost, model.state.value.host)
        }
        compose.onNodeWithTag("pairingCard").performScrollTo()
        compose.waitForIdle()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "bluetooth-check-ui.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
