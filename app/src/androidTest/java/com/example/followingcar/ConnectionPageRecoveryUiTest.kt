package com.example.followingcar

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in inspection of an existing real UNKNOWN recovery record. Only page/method chips are clicked.
 * No connect, Bluetooth, recovery, permission, wireless-group or motor action is invoked; no store is cleared.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionPageRecoveryUiTest {
    // Check the explicit opt-in and existing journal before creating the production activity.
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pendingLocalRecoveryDoesNotHideRemoteConnectionOrModeSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires runConnectionPageRecoveryUi=true",
            args.getString("runConnectionPageRecoveryUi") == "true")
        assumeTrue("Confirm the phone and board are idle before opening the production activity",
            args.getString("confirmedControlIdle") == "true")
        assumeTrue("Requires a real phone", Build.HARDWARE !in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val store = PairingRecoveryStore(context)
        val originalRecord = runBlocking { store.load() }
        assumeTrue("Requires an existing real UNKNOWN pairing record; this test never creates one",
            originalRecord is PairingRecoveryLoad.Pending && originalRecord.record.phase == PairingRecoveryPhase.UNKNOWN)
        assumeTrue("A remote identity must already be imported",
            RemoteCredentials(context).getClientIdentity() != null)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: BoardViewModel
        var restoreRemote = false
        try {
            scenario.onActivity { model = (it.application as FollowingCarApplication).boardViewModel }
            compose.waitUntil(15_000) {
                !model.state.value.remoteProfileLoading && !model.pairing.state.value.recoveryLoading &&
                    !model.wifiDirect.state.value.busy
            }
            val state = model.state.value
            val pairing = model.pairing.state.value
            assumeTrue("SSH/control must be disconnected and idle", idle(model))
            assumeTrue("Requires an unresolved pairing result without an active hotspot or pairing task",
                pairing.stage == PairingStage.UNKNOWN && pairing.confirmationRequired &&
                    !pairing.busy && !pairing.hotspotActive)
            assumeTrue("Requires a usable already-imported remote profile",
                state.remoteProfile != null && state.remoteProfileError == null)
            assumeTrue("No wireless mutation may be pending", !model.wifiDirect.mutationPending)
            assumeTrue("Mode selection must be allowed before this inspection",
                state.connectionSelectionBlockReason(model.wifiDirect.mutationPending) == null)
            val originalHost = state.host
            val originalPort = state.port
            val originalProfile = state.remoteProfile
            val originalGroupExists = model.wifiDirect.state.value.groupExists
            val originalGroupOwner = model.wifiDirect.state.value.isGroupOwner
            val originalWirelessUnconfirmed = model.wifiDirect.state.value.confirmationRequired
            val screenshots = File(context.filesDir,
                "test-evidence/connection-page-recovery/${System.currentTimeMillis()}")
            assertTrue("Create a fresh private screenshot directory", screenshots.mkdirs())

            compose.onNodeWithTag("pageConnection").performClick()
            assertAllMethodsEnabled()
            restoreRemote = true

            selectMethod("connectionLanMode")
            compose.onNodeWithTag("connect").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("checkPairing").assertExists()
            compose.onNodeWithTag("connectionPairingRecoveryReason").assertExists()
            assertUnchangedRecoveryAndIdle(model, store, originalRecord)
            screenshot(screenshots, "01-lan.png")

            selectMethod("connectionDirectMode")
            compose.onNodeWithTag("connect").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("checkPairing").assertExists()
            compose.onNodeWithTag("wifiDirectLocked").assertExists()
            assertFalse("An unresolved pairing result must block wireless-group changes",
                model.state.value.canManageWifiDirect)
            compose.onAllNodes(SemanticsMatcher("wireless mutation controls") { node ->
                val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                tag in setOf("wifiDirectCreate", "wifiDirectRemove", "wifiDirectDiscover",
                    "wifiDirectPermission", "wifiDirectOpenSettings") || tag.startsWith("wifiDirectInvite_")
            }).assertAll(isNotEnabled())
            assertUnchangedRecoveryAndIdle(model, store, originalRecord)
            screenshot(screenshots, "02-direct.png")
            compose.onNodeWithTag("wifiDirectLocked").performScrollTo()
            screenshot(screenshots, "02-direct-locked.png", scrollToSetup = false)

            selectMethod("connectionRemoteMode")
            compose.onNodeWithTag("connect").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("checkPairing").assertExists()
            assertAllMethodsEnabled()
            assertUnchangedRecoveryAndIdle(model, store, originalRecord)
            screenshot(screenshots, "03-remote.png")
            assertTrue("Saved LAN coordinates must not change",
                originalHost == model.state.value.host && originalPort == model.state.value.port)
            assertTrue("Imported remote identity must not change", originalProfile == model.state.value.remoteProfile)
            assertTrue("Existing wireless-group state must not be changed by mode selection",
                originalGroupExists == model.wifiDirect.state.value.groupExists &&
                    originalGroupOwner == model.wifiDirect.state.value.isGroupOwner &&
                    originalWirelessUnconfirmed == model.wifiDirect.state.value.confirmationRequired)
            // A relative evidence path contains no journal identifiers, addresses or credentials.
            println("CONNECTION_PAGE_RECOVERY_UI_OK screenshots=test-evidence/connection-page-recovery/${screenshots.name}")
        } finally {
            try {
                if (restoreRemote) {
                    // Restore through the same guarded UI; never override a newly active control lock.
                    selectMethod("connectionRemoteMode")
                    assertTrue("Leave the connection page in remote mode", model.state.value.remoteMode)
                }
            } finally {
                scenario.close()
            }
        }
    }

    private fun idle(model: BoardViewModel): Boolean = model.state.value.let {
        !it.connected && !it.busy && !it.manualActive && !it.followActive && !it.followStopping &&
            !it.videoServiceBusy && !it.motorActionCancellable && !model.pairing.state.value.busy &&
            !model.wifiDirect.mutationPending
    }

    private fun assertUnchangedRecoveryAndIdle(model: BoardViewModel, store: PairingRecoveryStore,
        originalRecord: PairingRecoveryLoad) {
        assertTrue("Inspecting connection choices must not start SSH, control, pairing or video operations", idle(model))
        assertTrue("Pairing uncertainty must remain intact", model.pairing.state.value.confirmationRequired)
        assertTrue("The existing recovery journal must remain unchanged", runBlocking { store.load() } == originalRecord)
    }

    private fun assertAllMethodsEnabled() {
        listOf("connectionLanMode", "connectionDirectMode", "connectionRemoteMode").forEach {
            compose.onNodeWithTag(it).assertIsEnabled()
        }
    }

    private fun selectMethod(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsSelected()
    }

    private fun screenshot(directory: File, name: String, scrollToSetup: Boolean = true) {
        if (scrollToSetup) compose.onNodeWithTag("connectionSetupCard").performScrollTo()
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Phone must remain unlocked for screenshots"
        }
        try {
            File(directory, name).outputStream().use {
                assertTrue("Save connection-page screenshot", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
