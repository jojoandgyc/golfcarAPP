package com.example.followingcar

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in receiver only. The orchestrator must first start the isolated synthetic source. */
@RunWith(AndroidJUnit4::class)
class RemoteVideoIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authenticatedRemoteVideoUsesTurnAndDecodesSyntheticFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires an explicitly staged synthetic relay source",
            InstrumentationRegistry.getArguments().getString("remoteVideoSynthetic") == "true")
        val identity = requireNotNull(RemoteCredentials(instrumentation.targetContext).getClientIdentity()) {
            "Import a dedicated relay test identity before this opt-in test"
        }
        val endpoint = RemoteVideoEndpoint(identity.profile.host, identity.profile.videoPort,
            identity.profile.clientCertificateSha256, { identity.privateKey }, { identity.certificateChain })
        var forbiddenServiceCalls = 0
        compose.setContent {
            MaterialTheme {
                BoardCameraPreview(BoardUiState(connected = true, videoServiceState = VideoServiceState.RUNNING),
                    onVideoServiceEnabledChange = { forbiddenServiceCalls++ }, modifier = Modifier.fillMaxSize(),
                    remoteVideo = endpoint)
            }
        }
        compose.waitForIdle()
        var last = JSONObject()
        var activeView: WebView? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
        while (System.nanoTime() < deadline) {
            val view = AtomicReference<WebView?>()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { activity ->
                    findWebView(activity)?.takeIf { it.url == endpoint.previewUrl }?.let(view::set)
                }
            }
            val webView = view.get()
            if (webView != null) {
                activeView = webView
                last = readPlayerState(webView)
                if (last.optString("state") == "authentication_failed") fail("Video authentication was rejected")
                if (last.optString("state") == "playing" && last.optString("candidateType") == "relay" &&
                    last.optInt("framesDecoded") >= 20 && last.optInt("width") == 1280 &&
                    last.optInt("height") == 480 && last.optInt("readyState") >= 2) break
            }
            Thread.sleep(500)
        }
        assertEquals("Only synthetic video is received; no videoctl/control callback may run", 0, forbiddenServiceCalls)
        assertEquals("Last safe player state: $last", "playing", last.optString("state"))
        assertEquals("relay", last.optString("candidateType"))
        assertTrue("Video must decode frames, not merely load the page: $last", last.optInt("framesDecoded") >= 20)
        assertEquals(1280, last.optInt("width"))
        assertEquals(480, last.optInt("height"))
        val firstFrames = last.getInt("framesDecoded")
        val started = System.nanoTime()
        val samples = mutableListOf<Int>()
        val transports = mutableSetOf<String>()
        var sequence = -1
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30)) {
            Thread.sleep(500)
            last = readPlayerState(requireNotNull(activeView))
            assertEquals("Video disconnected during the steady-state sample: $last", "playing", last.optString("state"))
            assertEquals("relay", last.optString("candidateType"))
            if (last.optInt("statsSequence") != sequence) {
                sequence = last.optInt("statsSequence")
                if (last.optInt("roundTripMs", -1) >= 0) samples.add(last.getInt("roundTripMs"))
                transports.add("${last.optString("localProtocol")}/${last.optString("relayProtocol")}/${last.optString("remoteProtocol")}")
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val frameDelta = last.getInt("framesDecoded") - firstFrames
        assertTrue("Frames must continue decoding during 30-second observation", frameDelta >= 20)
        samples.sort()
        val performance = JSONObject().put("sampleSeconds", seconds).put("decodedFrameDelta", frameDelta)
            .put("decodedFps", frameDelta / seconds).put("rttSamples", samples.size)
            .put("rttP50Ms", samples.getOrNull(samples.size / 2))
            .put("rttP95Ms", samples.getOrNull(kotlin.math.ceil(samples.size * .95).toInt() - 1))
            .put("rttValuesMs", JSONArray(samples))
            .put("protocols", transports.joinToString(","))
        println("REMOTE_VIDEO_STEADY_STATE $performance")
        assertTrue("Successful player must not show the unavailable message", last.optBoolean("messageEmpty"))
        compose.onNodeWithTag("cameraStatus").assertTextEquals("远程画面已连接")
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(instrumentation.targetContext.cacheDir, "remote-video-synthetic.png").outputStream().use {
                assertTrue("Save synthetic video evidence", screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        }
        println("REMOTE_VIDEO_SYNTHETIC_OK $last")
    }

    private fun findWebView(activity: Activity): WebView? = findWebView(activity.window.decorView)

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun readPlayerState(webView: WebView): JSONObject {
        val result = AtomicReference<String?>()
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript("""
                (function() {
                  var d = document.documentElement.dataset, v = document.getElementById('video');
                  return JSON.stringify({state:d.state||'',failure:d.failure||'',candidateType:d.candidateType||'',
                    title:/^FollowingCarVideo:[a-z_]+$/.test(document.title)?document.title:'',
                    messageEmpty:document.getElementById('message')?.textContent === '',
                    roundTripMs:d.roundTripMs?Number(d.roundTripMs):-1,statsSequence:Number(d.statsSequence)||0,
                    localProtocol:d.localProtocol||'',relayProtocol:d.relayProtocol||'',remoteProtocol:d.remoteProtocol||'',
                    framesDecoded:Number(d.framesDecoded)||0,width:v?v.videoWidth:0,
                    height:v?v.videoHeight:0,readyState:v?v.readyState:0});
                })()
            """.trimIndent()) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("WebView state callback timed out", completed.await(5, TimeUnit.SECONDS))
        return runCatching { JSONObject(JSONTokener(result.get()).nextValue() as String) }.getOrDefault(JSONObject())
    }
}
