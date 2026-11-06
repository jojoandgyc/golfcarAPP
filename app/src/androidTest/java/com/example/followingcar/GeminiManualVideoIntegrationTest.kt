package com.example.followingcar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real camera check. It never opens a motor or follow control session. */
@RunWith(AndroidJUnit4::class)
class GeminiManualVideoIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedGeminiStartsAndStopsRgbDepthService() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Set runGeminiVideoTest=true to open the camera", arguments.getString("runGeminiVideoTest") == "true")
        val password = arguments.getString("boardPassword").orEmpty()
        assumeTrue("Use saved credentials or boardPassword",
            password.isNotEmpty() || arguments.getString("useSavedBoardCredentials") == "true")
        val viewModel = compose.runOnUiThread {
            (compose.activity.application as FollowingCarApplication).boardViewModel
        }
        var startedByTest = false
        var connectedByTest = false
        var testFailure: Throwable? = null
        try {
            if (!viewModel.state.value.connected) {
                compose.runOnUiThread {
                    arguments.getString("boardHost")?.let(viewModel::updateHost)
                    arguments.getString("boardUsername")?.let(viewModel::updateUsername)
                    if (password.isNotEmpty()) viewModel.updatePassword(password)
                    viewModel.connect()
                }
                connectedByTest = true
                compose.waitUntil(45_000) {
                    val state = viewModel.state.value
                    !state.busy && !state.videoServiceBusy
                }
            }
            val idle = viewModel.state.value
            assertTrue(idle.error, idle.connected)
            // Never replace an existing stream or touch a control session.
            assumeTrue("Video must already be stopped; no control session may be active",
                idle.videoServiceState == VideoServiceState.STOPPED && !idle.manualActive &&
                    !idle.followBlocksControl && idle.canToggleVideoService)
            compose.onNodeWithTag("pageJoystick").performClick()
            compose.onNodeWithTag("manualVideoCamera_gemini335").performClick().assertIsSelected()
            compose.runOnUiThread { viewModel.setVideoServiceEnabled(true) }
            startedByTest = true
            compose.waitUntil(65_000) { !viewModel.state.value.videoServiceBusy }
            val running = viewModel.state.value
            assertEquals(running.videoServiceError, VideoServiceState.RUNNING, running.videoServiceState)
            assertEquals(ManualVideoCamera.GEMINI335, running.activeVideoCamera)
            assertTrue("The RGB/depth player should be requested", running.videoPreviewWanted)
            compose.onNodeWithTag("cameraPreview").assertIsDisplayed()
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            if (startedByTest) {
                try {
                    // Never skip cleanup just because start has not returned yet.
                    compose.waitUntil(75_000) { !viewModel.state.value.videoServiceBusy }
                    check(viewModel.state.value.connected && viewModel.state.value.canToggleVideoService) {
                        "无法发送视频关闭请求，车端相机释放未确认"
                    }
                    compose.runOnUiThread { viewModel.setVideoServiceEnabled(false) }
                    compose.waitUntil(50_000) { !viewModel.state.value.videoServiceBusy }
                    assertEquals(viewModel.state.value.videoServiceError, VideoServiceState.STOPPED,
                        viewModel.state.value.videoServiceState)
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                }
            }
            // Disconnect also awaits a pending start and attempts to stop video. Run it even if
            // the explicit stop failed, and report the unconfirmed cleanup to the test runner.
            val safeToDisconnect = startedByTest || connectedByTest &&
                viewModel.state.value.videoServiceState == VideoServiceState.STOPPED &&
                !viewModel.state.value.manualActive && !viewModel.state.value.followBlocksControl
            if (safeToDisconnect && viewModel.state.value.connected) {
                try {
                    compose.runOnUiThread { viewModel.disconnect() }
                    compose.waitUntil(135_000) { !viewModel.state.value.busy && !viewModel.state.value.connected }
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure
                    else cleanupFailure.addSuppressed(failure)
                }
            }
            cleanupFailure?.let { failure ->
                val unresolved = AssertionError("Gemini 视频清理未确认；请检查板端 videoctl status 和相机占用", failure)
                if (testFailure != null) testFailure.addSuppressed(unresolved) else throw unresolved
            }
        }
    }
}
