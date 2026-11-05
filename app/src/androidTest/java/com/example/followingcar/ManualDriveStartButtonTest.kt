package com.example.followingcar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.followingcar.ui.theme.FollowingCarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pure UI fixtures: no MainActivity, ViewModel, SSH, video service or motor operations. */
class ManualDriveStartButtonTest {
    @get:Rule val compose = createComposeRule()

    private fun remoteReady() = BoardUiState(
        connected = true,
        remoteMode = true,
        pairingUnconfirmed = true,
        videoServiceState = VideoServiceState.RUNNING,
        videoPreviewWanted = true,
    )

    @Test fun connectedRemoteCanEnableDespiteUnconfirmedLocalPairingWithoutClearingIt() {
        val state = remoteReady()
        var enables = 0
        var inspections = 0
        compose.setContent { FollowingCarTheme {
            ManualDriveStartButton(state, onEnable = { enables++ }, onInspect = { inspections++ })
        } }
        compose.onNodeWithTag("enableDrive").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("driveStartBlockReason").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, enables); assertEquals(0, inspections) }
        compose.onNodeWithTag("enableDrive").performClick()
        compose.runOnIdle {
            assertEquals(1, enables)
            assertEquals(0, inspections)
            assertTrue(state.canEnableDrive)
            assertNull(state.driveStartBlockReason)
            assertTrue(state.pairingUnconfirmed)
            assertEquals(VideoServiceState.RUNNING, state.videoServiceState)
            assertTrue(state.videoPreviewWanted)
            assertFalse(state.manualActive)
        }
    }

    @Test fun localPendingPairingIsBlockedAndChangingRouteDoesNotStartAnything() {
        val state = mutableStateOf(remoteReady().copy(remoteMode = false))
        var enables = 0
        var inspections = 0
        compose.setContent { FollowingCarTheme {
            ManualDriveStartButton(state.value, onEnable = { enables++ }, onInspect = { inspections++ })
        } }
        compose.onNodeWithTag("enableDrive").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("driveStartBlockReason").assertIsDisplayed()
            .assertTextEquals(requireNotNull(state.value.driveStartBlockReason))
        compose.runOnIdle {
            assertFalse(state.value.canEnableDrive)
            assertTrue(state.value.driveStartBlockReason!!.isNotBlank())
            state.value = state.value.copy(remoteMode = true)
        }
        compose.onNodeWithTag("enableDrive").assertIsEnabled()
        compose.onNodeWithTag("driveStartBlockReason").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(state.value.pairingUnconfirmed)
            assertEquals(0, enables)
            assertEquals(0, inspections)
            state.value = state.value.copy(remoteMode = false)
        }
        compose.onNodeWithTag("enableDrive").assertIsNotEnabled()
        compose.onNodeWithTag("driveStartBlockReason").assertIsDisplayed()
    }

    @Test fun activelyRunningPairingBlocksBothRemoteAndLocalRoutes() {
        val state = mutableStateOf(remoteReady().copy(pairingBusy = true))
        var enables = 0
        var inspections = 0
        compose.setContent { FollowingCarTheme {
            ManualDriveStartButton(state.value, onEnable = { enables++ }, onInspect = { inspections++ })
        } }
        for (remote in listOf(true, false)) {
            compose.runOnIdle { state.value = state.value.copy(remoteMode = remote) }
            compose.onNodeWithTag("enableDrive").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("driveStartBlockReason").assertIsDisplayed()
                .assertTextEquals(requireNotNull(state.value.driveStartBlockReason))
            compose.runOnIdle { assertFalse(state.value.canEnableDrive) }
        }
        compose.runOnIdle { assertEquals(0, enables); assertEquals(0, inspections) }
    }

    @Test fun unconfirmedManualCleanupOnlyOffersInspectionAndKeepsAllRecoveryLocks() {
        val state = remoteReady().copy(
            driveCleanupUnconfirmed = true,
            followUncertain = true,
            motorActionUnconfirmed = true,
        )
        var enables = 0
        var inspections = 0
        compose.setContent { FollowingCarTheme {
            ManualDriveStartButton(state, onEnable = { enables++ }, onInspect = { inspections++ })
        } }
        compose.onNodeWithTag("enableDrive").assertIsDisplayed().assertIsEnabled()
            .assertTextEquals("检查控制占用").performClick()
        compose.runOnIdle {
            assertEquals(0, enables)
            assertEquals(1, inspections)
            assertTrue(state.canInspectFollow)
            assertFalse(state.canEnableDrive)
            assertTrue(state.driveCleanupUnconfirmed)
            assertTrue(state.followUncertain)
            assertTrue(state.motorActionUnconfirmed)
            assertTrue(state.pairingUnconfirmed)
            assertEquals(VideoServiceState.RUNNING, state.videoServiceState)
            assertTrue(state.videoPreviewWanted)
        }
    }

    @Test fun cleanupInspectionAlsoHonorsActivePairingAndOtherOperations() {
        val base = remoteReady().copy(driveCleanupUnconfirmed = true)
        val state = mutableStateOf(base)
        var enables = 0
        var inspections = 0
        compose.setContent { FollowingCarTheme {
            ManualDriveStartButton(state.value, onEnable = { enables++ }, onInspect = { inspections++ })
        } }
        for (blocked in listOf(base.copy(pairingBusy = true), base.copy(videoServiceBusy = true),
            base.copy(connected = false), base.copy(followActive = true), base.copy(busy = true))) {
            compose.runOnIdle { state.value = blocked }
            compose.onNodeWithTag("enableDrive").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("driveStartBlockReason").assertIsDisplayed()
            compose.runOnIdle { assertFalse(state.value.canInspectFollow) }
        }
        compose.runOnIdle { assertEquals(0, enables); assertEquals(0, inspections) }
    }

    @Test fun disconnectedSshAndBusyVideoShowReasonsInsteadOfAnUnexplainedDisabledButton() {
        val state = mutableStateOf(remoteReady().copy(connected = false))
        var enables = 0
        var inspections = 0
        compose.setContent { FollowingCarTheme {
            ManualDriveStartButton(state.value, onEnable = { enables++ }, onInspect = { inspections++ })
        } }
        val blockedStates = listOf(remoteReady().copy(connected = false),
            remoteReady().copy(videoServiceBusy = true))
        for (blocked in blockedStates) {
            compose.runOnIdle { state.value = blocked }
            compose.onNodeWithTag("enableDrive").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("driveStartBlockReason").assertIsDisplayed()
                .assertTextEquals(requireNotNull(blocked.driveStartBlockReason))
            compose.runOnIdle {
                assertNotNull(blocked.driveStartBlockReason)
                assertTrue(blocked.driveStartBlockReason!!.isNotBlank())
                assertEquals(0, enables)
                assertEquals(0, inspections)
            }
        }
    }
}
