package com.example.followingcar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real video-service checks. The camera is opened by the video service, but all motor
 * and follow sessions explicitly use dryRun=true. Never click the production enableDrive button.
 * Both successful completion and cleanup leave video off; no movement targets are sent.
 */
@RunWith(AndroidJUnit4::class)
class BoardVideoServiceIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun videoButtonAndDryRunModeTransitionsManageTheBoardService() {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("boardPassword").orEmpty()
        assumeTrue("Opt in with boardPassword or useSavedBoardCredentials",
            password.isNotEmpty() || arguments.getString("useSavedBoardCredentials") == "true")
        val viewModel = compose.runOnUiThread {
            ViewModelProvider(compose.activity)[BoardViewModel::class.java]
        }
        // Do not interfere with a previously unconfirmed follow session.
        assumeTrue("Resolve the existing follow session before this test",
            !viewModel.state.value.followBlocksControl)
        var testFailure: Throwable? = null
        try {
            compose.runOnUiThread {
                arguments.getString("boardHost")?.let(viewModel::updateHost)
                arguments.getString("boardUsername")?.let(viewModel::updateUsername)
                if (password.isNotEmpty()) viewModel.updatePassword(password)
                viewModel.connect()
            }
            compose.waitUntil(40_000) { !viewModel.state.value.busy }
            assertTrue(viewModel.state.value.error, viewModel.state.value.connected)
            compose.onNodeWithTag("pageJoystick").performClick()
            compose.onNodeWithTag("videoServiceToggle").assertIsDisplayed()

            // Establish a known baseline even when the user had left the service running.
            compose.runOnUiThread { viewModel.setVideoServiceEnabled(false) }
            awaitVideo(viewModel, VideoServiceState.STOPPED, 50_000)

            compose.onNodeWithTag("videoServiceToggle").assertIsEnabled().performClick()
            awaitVideo(viewModel, VideoServiceState.RUNNING, 65_000)
            assertFalse(viewModel.state.value.manualActive)
            compose.onNodeWithTag("videoServiceToggle").assertIsEnabled().performClick()
            awaitVideo(viewModel, VideoServiceState.STOPPED, 50_000)

            compose.runOnUiThread { viewModel.enableDrive(dryRun = true) }
            compose.waitUntil(75_000) {
                val state = viewModel.state.value
                state.driveEnabled || (!state.manualActive && state.driveError != null)
            }
            assertTrue(viewModel.state.value.driveError, viewModel.state.value.driveEnabled)
            assertEquals("dry-run", viewModel.state.value.drivePort)
            assertEquals(VideoServiceState.RUNNING, viewModel.state.value.videoServiceState)
            compose.runOnUiThread { viewModel.disableDrive("视频联动模拟测试停车") }
            awaitManualStopped(viewModel)
            assertTrue(viewModel.state.value.driveConfirmedStopped)

            // A stop requested while video is starting must not later enable the motor bridge.
            compose.runOnUiThread {
                viewModel.enableDrive(dryRun = true)
                viewModel.disableDrive("取消视频启动中的模拟驾驶")
            }
            awaitManualStopped(viewModel)
            assertFalse(viewModel.state.value.driveEnabled)
            assertEquals(0, viewModel.state.value.driveLeft)
            assertEquals(0, viewModel.state.value.driveRight)

            compose.onNodeWithTag("videoServiceToggle").assertIsEnabled().performClick()
            awaitVideo(viewModel, VideoServiceState.RUNNING, 65_000)
            compose.runOnUiThread { viewModel.inspectFollowConfiguration() }
            compose.waitUntil(35_000) { !viewModel.state.value.followConfigurationLoading }
            assertTrue(viewModel.state.value.followConfigurationError,
                viewModel.state.value.canStartFollow)
            compose.runOnUiThread {
                viewModel.setFollowExecute(false)
                viewModel.startFollow(dryRun = true)
            }
            compose.waitUntil(65_000) {
                val state = viewModel.state.value
                // The first runtime reply must never arrive before service shutdown is confirmed.
                if (state.followStatus != null) {
                    assertEquals(VideoServiceState.STOPPED, state.videoServiceState)
                }
                state.followStatus?.runState == "RUNNING" || !state.followActive
            }
            assertTrue(viewModel.state.value.followError, viewModel.state.value.followActive)
            assertEquals("RUNNING", viewModel.state.value.followStatus?.runState)
            assertEquals(false, viewModel.state.value.followStatus?.execute)
            assertEquals(VideoServiceState.STOPPED, viewModel.state.value.videoServiceState)
            compose.onNodeWithTag("videoServiceToggle").assertIsNotEnabled()
            // The ViewModel must also reject a direct call while follow owns the camera.
            compose.runOnUiThread { viewModel.setVideoServiceEnabled(true) }
            assertFalse(viewModel.state.value.videoServiceBusy)
            assertEquals(VideoServiceState.STOPPED, viewModel.state.value.videoServiceState)
            compose.runOnUiThread { viewModel.stopFollow("视频联动模拟测试结束") }
            compose.waitUntil(15_000) { !viewModel.state.value.followActive }
            assertFalse(viewModel.state.value.followError, viewModel.state.value.followUncertain)
            assertEquals(VideoServiceState.STOPPED, viewModel.state.value.videoServiceState)
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching { cleanUp(viewModel) }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (testFailure != null) testFailure.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            }
        }
    }

    private fun awaitVideo(viewModel: BoardViewModel, expected: VideoServiceState, timeout: Long) {
        compose.waitUntil(timeout) { !viewModel.state.value.videoServiceBusy }
        val state = viewModel.state.value
        assertEquals(state.videoServiceError ?: state.videoServiceMessage, expected, state.videoServiceState)
    }

    private fun awaitManualStopped(viewModel: BoardViewModel) {
        // Starting may take 60 s; shutdown may take another 45 s. Do not cancel the SSH command.
        compose.waitUntil(115_000) {
            val state = viewModel.state.value
            !state.manualActive && !state.videoServiceBusy
        }
        assertEquals(viewModel.state.value.videoServiceError, VideoServiceState.STOPPED,
            viewModel.state.value.videoServiceState)
    }

    private fun cleanUp(viewModel: BoardViewModel) {
        try {
            compose.runOnUiThread {
                viewModel.disableDrive("视频服务测试清理")
                viewModel.stopFollow("视频服务测试清理")
            }
            compose.waitUntil(115_000) {
                val state = viewModel.state.value
                !state.manualActive && !state.followActive && !state.videoServiceBusy
            }
            if (viewModel.state.value.canToggleVideoService) {
                compose.runOnUiThread { viewModel.setVideoServiceEnabled(false) }
                awaitVideo(viewModel, VideoServiceState.STOPPED, 50_000)
            }
        } finally {
            compose.runOnUiThread { viewModel.disconnect() }
            compose.waitUntil(120_000) {
                val state = viewModel.state.value
                !state.connected && !state.busy
            }
        }
    }
}
