package com.example.followingcar

import android.app.Activity
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.util.UUID
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
        val arguments = InstrumentationRegistry.getArguments()
        val handover = arguments.getString("remoteVideoHandover") == "true"
        val recovery = arguments.getString("remoteVideoRecovery") == "true"
        val temporaryProfile = arguments.getString("remoteVideoTemporaryProfile") == "true"
        val stagedProfile = File(instrumentation.targetContext.cacheDir, "remote-video-profile.carremote")
        val credentials = if (temporaryProfile) RemoteCredentials(instrumentation.targetContext, UUID.randomUUID())
            else RemoteCredentials(instrumentation.targetContext)
        val connectivity = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
        val wifi = instrumentation.targetContext.applicationContext.getSystemService(WifiManager::class.java)
        val wifiWasEnabled = wifi.isWifiEnabled
        var monitor: BoardNetworkMonitor? = null
        var wifiMutationAttempted = false
        var contentMounted = false
        val receiverState = mutableStateOf(BoardUiState(connected = true, remoteMode = true,
            videoPreviewWanted = true, videoServiceState = VideoServiceState.RUNNING))
        val testNetworkNotice = mutableStateOf<PhoneNetworkState?>(null)
        var forbiddenServiceCalls = 0
        try {
        if (handover) assumeTrue("Handover requires validated Wi-Fi internet with no VPN and Wi-Fi enabled",
            wifiWasEnabled && activeNetworkMatches(connectivity, wifi = true))
        if (temporaryProfile) {
            val imported = runCatching { stagedProfile.inputStream().use(credentials::importProfile) }.isSuccess
            assertTrue("The staged temporary video profile could not be imported", imported)
        }
        val identity = runCatching { credentials.getClientIdentity() }.getOrNull()
        assertNotNull("Import a valid relay test identity before this opt-in test", identity)
        val loadedIdentity = requireNotNull(identity)
        val endpoint = RemoteVideoEndpoint(loadedIdentity.profile.host, loadedIdentity.profile.videoPort,
            loadedIdentity.profile.clientCertificateSha256, { loadedIdentity.privateKey }, { loadedIdentity.certificateChain })
        monitor = if (handover || recovery) BoardNetworkMonitor(instrumentation.targetContext) else null
        compose.setContent {
            MaterialTheme {
                val observedPhone = monitor?.state?.collectAsState()?.value ?: PhoneNetworkState()
                BoardCameraPreview(receiverState.value,
                    onVideoServiceEnabledChange = { forbiddenServiceCalls++ }, modifier = Modifier.fillMaxSize(),
                    remoteVideo = endpoint, phoneNetwork = testNetworkNotice.value ?: observedPhone)
            }
        }
        contentMounted = true
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
            awaitNextReceiverSample()
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
            awaitNextReceiverSample()
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
        if (handover || recovery) {
            val networkMonitor = requireNotNull(monitor)
            val view = requireNotNull(activeView)
            assertTrue("The deployed player must expose its non-sensitive generation counter",
                last.optLong("readerGeneration") > 0)
            // This is a receive-only UI state transition, never a real control SSH session.
            compose.runOnIdle { receiverState.value = receiverState.value.withTransportState(false) }
            compose.waitForIdle()
            compose.onNodeWithTag("cameraStatus").assertTextContains("控制未连接", substring = true)
            if (handover) {
                assertTrue("Wi-Fi must remain validated until the explicit handover begins",
                    activeNetworkMatches(connectivity, wifi = true))
                val wifiHandle = networkMonitor.state.value.defaultNetworkHandle
                val toCellular = measureReceiverRecovery("wifi_to_cellular", view, last,
                    networkReady = {
                        val current = networkMonitor.state.value
                        current.defaultNetworkHandle != null && current.defaultNetworkHandle != wifiHandle &&
                            current.defaultNetworkHandle == connectivity.activeNetwork?.networkHandle &&
                            current.internetValidated && activeNetworkMatches(connectivity, wifi = false)
                    }, networkState = { networkMonitor.state.value },
                    trigger = { wifiMutationAttempted = true; setWifiEnabled(false) })
                val cellularHandle = networkMonitor.state.value.defaultNetworkHandle
                measureReceiverRecovery("cellular_to_wifi", view, toCellular,
                    networkReady = {
                        val current = networkMonitor.state.value
                        current.defaultNetworkHandle != null && current.defaultNetworkHandle != cellularHandle &&
                            current.defaultNetworkHandle == connectivity.activeNetwork?.networkHandle &&
                            current.internetValidated && activeNetworkMatches(connectivity, wifi = true)
                    }, networkState = { networkMonitor.state.value },
                    trigger = { setWifiEnabled(true) })
            } else {
                val actual = networkMonitor.state.value
                // Inject a receiver notification only. Real advancing decoded frames below,
                // not the emulator's system internet probe, verify the actual media path.
                val simulatedNotice = PhoneNetworkState(observed = true, available = true,
                    internetValidated = true, transportLabel = "injected_network_notice",
                    defaultNetworkHandle = (actual.defaultNetworkHandle ?: 0L) + 1,
                    revision = actual.revision + 1)
                println("REMOTE_VIDEO_INJECTED_NETWORK_NOTICE " + JSONObject()
                    .put("actualPhoneTransport", actual.transportLabel)
                    .put("actualInternetValidated", actual.internetValidated)
                    .put("injectedInternetValidated", true).put("changedActualNetwork", false))
                measureReceiverRecovery("receiver_only_simulated_network_notice", view, last,
                    networkReady = { true },
                    networkState = { networkMonitor.state.value.copy(
                        transportLabel = "injected_network_notice") },
                    trigger = { compose.runOnIdle { testNetworkNotice.value = simulatedNotice } })
            }
            compose.runOnIdle {
                assertFalse("Video recovery must never reconnect control", receiverState.value.connected)
                assertFalse(receiverState.value.canToggleVideoService)
                assertEquals("Receive-only recovery must never invoke videoctl or control", 0, forbiddenServiceCalls)
            }
        }
        } finally {
            try {
                // No svc command is reachable unless real handover was explicitly opted in.
                if (handover && wifiMutationAttempted) {
                    if (wifi.isWifiEnabled != wifiWasEnabled) setWifiEnabled(wifiWasEnabled)
                    val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (System.nanoTime() < restoreDeadline &&
                        (wifi.isWifiEnabled != wifiWasEnabled || (wifiWasEnabled && !activeNetworkMatches(connectivity, wifi = true))))
                        Thread.sleep(250)
                    assertEquals("Restore the original Wi-Fi enabled state", wifiWasEnabled, wifi.isWifiEnabled)
                    assertTrue("Restore the original validated Wi-Fi route", !wifiWasEnabled || activeNetworkMatches(connectivity, wifi = true))
                }
            } finally {
                try {
                    if (contentMounted) {
                        compose.runOnIdle { receiverState.value = receiverState.value.copy(videoPreviewWanted = false) }
                        compose.waitForIdle()
                    }
                } finally {
                    try { monitor?.close() } finally {
                        if (temporaryProfile) {
                            val cleared = runCatching { credentials.clear() }.isSuccess
                            val deleted = !stagedProfile.exists() || stagedProfile.delete()
                            assertTrue("Temporary video test identity/profile cleanup failed", cleared && deleted)
                        }
                    }
                }
            }
        }
    }

    private fun activeNetworkMatches(connectivity: ConnectivityManager, wifi: Boolean): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasTransport(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun setWifiEnabled(enabled: Boolean) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(if (enabled) "svc wifi enable" else "svc wifi disable")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun awaitNextReceiverSample() {
        // WebRTC advances on wall time; Compose recomposition/effect delays use the test clock.
        // Merely draining the Android main looper does not advance the 500 ms recovery debounce.
        Thread.sleep(500)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** A new reader generation and continuing new frames prevent cached pixels from passing. */
    private fun measureReceiverRecovery(label: String, webView: WebView, before: JSONObject,
        networkReady: () -> Boolean, networkState: () -> PhoneNetworkState, trigger: () -> Unit): JSONObject {
        val previousGeneration = before.optLong("readerGeneration")
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(90)
        var stableStarted = 0L
        var recoveredAt = 0L
        var stableGeneration = 0L
        var initialFrames = 0
        var previousFrames = 0
        var advancingSamples = 0
        var last = before
        trigger()
        compose.waitForIdle()
        while (System.nanoTime() < deadline) {
            awaitNextReceiverSample()
            compose.onNodeWithTag("cameraStatus").assertTextContains("控制未连接", substring = true)
            last = readPlayerState(webView)
            if (last.optString("state") == "authentication_failed") fail("Video identity was rejected during $label")
            val generation = last.optLong("readerGeneration")
            val frames = last.optInt("framesDecoded")
            val healthy = networkReady() && generation > previousGeneration &&
                last.optString("state") == "playing" && last.optString("candidateType") == "relay" &&
                frames >= 20 && last.optInt("width") == 1280 && last.optInt("height") == 480 &&
                last.optInt("readyState") >= 2
            if (!healthy) {
                stableStarted = 0L
                continue
            }
            val now = System.nanoTime()
            if (stableStarted == 0L || stableGeneration != generation || frames < previousFrames) {
                stableStarted = now
                if (recoveredAt == 0L) recoveredAt = now
                stableGeneration = generation
                initialFrames = frames
                previousFrames = frames
                advancingSamples = 0
            } else if (frames > previousFrames) {
                previousFrames = frames
                advancingSamples++
            }
            if (now - stableStarted >= TimeUnit.SECONDS.toNanos(8) && frames - initialFrames >= 20 && advancingSamples >= 2) {
                val phone = networkState()
                val evidence = JSONObject().put("stage", label)
                    .put("recoveryMs", TimeUnit.NANOSECONDS.toMillis(recoveredAt - started))
                    .put("stableSampleMs", TimeUnit.NANOSECONDS.toMillis(now - stableStarted))
                    .put("readerGenerationBefore", previousGeneration).put("readerGenerationAfter", generation)
                    .put("decodedFrameDelta", frames - initialFrames).put("frameAdvanceSamples", advancingSamples)
                    .put("phoneTransport", phone.transportLabel).put("internetValidated", phone.internetValidated)
                    .put("localProtocol", last.optString("localProtocol")).put("relayProtocol", last.optString("relayProtocol"))
                    .put("roundTripMs", last.optInt("roundTripMs", -1)).put("controlConnected", false)
                println("REMOTE_VIDEO_RECOVERY_OK $evidence")
                return last
            }
        }
        fail("Receive-only recovery did not establish new advancing frames within 90 s ($label): $last")
        return last
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
                    readerGeneration:Number(typeof reader!=='undefined'?reader.generation:0),
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
