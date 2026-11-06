package com.example.followingcar

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
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
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        val originalHost = viewModel.state.value.host
        val originalRemoteMode = viewModel.state.value.remoteMode
        val forceLocal = arguments.getString("forceLocalMode") == "true"
        var startedByTest = false
        var connectedByTest = false
        var preferencesChangedByTest = false
        var testFailure: Throwable? = null
        try {
            if (forceLocal) assumeTrue("Local video test requires a disconnected App",
                !viewModel.state.value.connected)
            if (!viewModel.state.value.connected) {
                compose.runOnUiThread {
                    if (forceLocal) {
                        check(viewModel.setRemoteMode(false)) { "Cannot select local route for video test" }
                        preferencesChangedByTest = true
                    }
                    arguments.getString("boardHost")?.let {
                        viewModel.updateHost(it)
                        preferencesChangedByTest = true
                    }
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
            if (forceLocal) assertTrue("Local video test fell back to remote route", !idle.remoteMode)
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
            val frame = awaitDecodedFrames()
            assertTrue("Unexpected Gemini video frame: $frame", frame.startsWith("frames:1920x540:"))
            savePhoneScreenshot(if (forceLocal) "local" else "remote")
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
            if (preferencesChangedByTest) {
                try {
                    compose.runOnUiThread {
                        check(!viewModel.state.value.connected && !viewModel.state.value.busy) {
                            "Cannot restore connection preferences while transport is active"
                        }
                        viewModel.updateHost(originalHost)
                        check(viewModel.setRemoteMode(originalRemoteMode)) {
                            "Cannot restore the original connection route"
                        }
                    }
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

    /** A loaded player page is insufficient: wait for two frames decoded by Android WebView. */
    private fun awaitDecodedFrames(): String {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var last = "not-started"
        while (SystemClock.elapsedRealtime() < deadline) {
            last = probeVideoFrame()
            if (last.startsWith("frames:")) return last
            Thread.sleep(400)
        }
        throw AssertionError("No decoded Gemini RGB/depth frame within 30 seconds: $last")
    }

    private fun probeVideoFrame(): String {
        val result = AtomicReference("webview-missing")
        val latch = CountDownLatch(1)
        compose.runOnUiThread {
            val player = findWebView(compose.activity.window.decorView)
            if (player == null) {
                latch.countDown()
                return@runOnUiThread
            }
            player.evaluateJavascript(
                """(() => {
                    const video = document.querySelector('video');
                    if (!video) return 'video-missing:' + document.readyState;
                    if (typeof video.requestVideoFrameCallback !== 'function') return 'frame-api-missing';
                    let probe = window.__followingCarGeminiFrameProbe;
                    if (!probe || probe.video !== video) {
                        probe = { video: video, count: 0 };
                        window.__followingCarGeminiFrameProbe = probe;
                        const onFrame = (_now, metadata) => {
                            probe.count += 1;
                            probe.presented = metadata.presentedFrames;
                            if (probe.count < 2) video.requestVideoFrameCallback(onFrame);
                        };
                        video.requestVideoFrameCallback(onFrame);
                    }
                    if (probe.count >= 2 && video.videoWidth > 0 && video.videoHeight > 0)
                        return 'frames:' + video.videoWidth + 'x' + video.videoHeight + ':' + probe.presented;
                    return 'waiting:' + video.readyState + ':' + probe.count;
                })()""".trimIndent(),
            ) { raw ->
                result.set(runCatching { JSONTokener(raw).nextValue() as String }.getOrDefault("invalid-js-result"))
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) return "webview-js-timeout"
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun savePhoneScreenshot(route: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Phone screenshot unavailable after Gemini decoded frames")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = context.getExternalFilesDir(null)
            ?: throw AssertionError("App external files unavailable for Gemini screenshot")
        val output = File(folder, "gemini335-manual-video-$route-first-frame.png")
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Screenshot encode failed" }
        }
        bitmap.recycle()
    }
}
