package com.example.followingcar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Fake receive-only player and callbacks: no WebView, HTTP, SSH, device network or videoctl. */
class RemoteVideoRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    private fun endpoint() = RemoteVideoEndpoint("video.example.test", 8443, "test-only-identity", { null }, { null })
    private fun viewing() = BoardUiState(connected = true, remoteMode = true,
        videoServiceState = VideoServiceState.RUNNING, videoPreviewWanted = true)

    private class Observations {
        var mounts = 0
        var disposals = 0
        var networkRevision = 0L
        var networkHandle: Long? = null
        var serviceCalls = 0
    }

    @Composable
    private fun Preview(state: BoardUiState, observations: Observations,
        endpoint: RemoteVideoEndpoint? = null, phone: PhoneNetworkState = PhoneNetworkState()) {
        MaterialTheme {
            BoardCameraPreview(state, { observations.serviceCalls++ }, Modifier.fillMaxSize(),
                remoteVideo = endpoint, phoneNetwork = phone,
                player = { _, _, onStatus, modifier, network ->
                    DisposableEffect(Unit) {
                        observations.mounts++
                        onDispose { observations.disposals++ }
                    }
                    SideEffect {
                        observations.networkRevision = network.revision
                        observations.networkHandle = network.defaultNetworkHandle
                    }
                    LaunchedEffect(Unit) { onStatus("模拟视频接收") }
                    Text("仅模拟接收画面", modifier.testTag("fakeVideoPlayer"))
                })
        }
    }

    @Test fun losingSshKeepsExistingRemotePlayerAndDisablesServiceCommands() {
        val state = mutableStateOf(viewing())
        val observed = Observations()
        val endpoint = endpoint()
        compose.setContent { Preview(state.value, observed, endpoint) }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.runOnIdle {
            assertEquals(1, observed.mounts)
            state.value = state.value.withTransportState(false)
        }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.onNodeWithTag("videoServiceToggle").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("cameraRetry").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, observed.mounts)
            assertEquals(0, observed.disposals)
            assertEquals(0, observed.serviceCalls)
            assertFalse(state.value.connected)
            assertFalse(state.value.canEnableDrive)
        }
    }

    @Test fun retryWithoutSshOnlyRecreatesTheReceiveOnlyPlayer() {
        val observed = Observations()
        val state = viewing().withTransportState(false)
        val endpoint = endpoint()
        compose.setContent { Preview(state, observed, endpoint) }
        compose.onNodeWithTag("cameraRetry").assertIsEnabled().performClick()
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.runOnIdle {
            assertEquals(2, observed.mounts)
            assertEquals(1, observed.disposals)
            assertEquals(0, observed.serviceCalls)
            assertFalse(state.connected)
            assertFalse(state.canToggleVideoService)
        }
    }

    @Test fun networkChangeIsDeliveredToExistingPlayerWithoutServiceOperation() {
        val observed = Observations()
        val state = viewing().withTransportState(false)
        val endpoint = endpoint()
        val phone = mutableStateOf(PhoneNetworkState(observed = true, available = true,
            internetValidated = true, transportLabel = "Wi-Fi", wifiAvailable = true, revision = 1,
            defaultNetworkHandle = 101L))
        compose.setContent { Preview(state, observed, endpoint, phone.value) }
        compose.runOnIdle { phone.value = phone.value.copy(transportLabel = "移动网络", wifiAvailable = false,
            revision = 2, defaultNetworkHandle = 202L) }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.runOnIdle {
            assertEquals(2L, observed.networkRevision)
            assertEquals(202L, observed.networkHandle)
            assertEquals(1, observed.mounts)
            assertEquals(0, observed.disposals)
            assertEquals(0, observed.serviceCalls)
        }
    }

    @Test fun explicitViewingCancellationUnloadsImmediatelyEvenBeforeSshCloses() {
        val observed = Observations()
        val state = mutableStateOf(viewing())
        val endpoint = endpoint()
        compose.setContent { Preview(state.value, observed, endpoint) }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.runOnIdle { state.value = state.value.copy(videoPreviewWanted = false) }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, observed.disposals)
            state.value = state.value.withTransportState(false)
        }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, observed.mounts); assertEquals(0, observed.serviceCalls) }
    }

    @Test fun followOwnershipAndConfirmedServiceStopPreventRemotePreview() {
        val observed = Observations()
        val state = mutableStateOf(viewing().withTransportState(false))
        val endpoint = endpoint()
        compose.setContent { Preview(state.value, observed, endpoint) }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.runOnIdle { state.value = state.value.copy(followActive = true) }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(followActive = false, followUncertain = true) }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(followUncertain = false, videoServiceState = VideoServiceState.STOPPED) }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, observed.mounts); assertEquals(1, observed.disposals); assertEquals(0, observed.serviceCalls) }
    }

    @Test fun missingRemoteIdentityNeverFallsBackToLocalVideoAfterSshLoss() {
        val observed = Observations()
        val state = mutableStateOf(viewing())
        compose.setContent { Preview(state.value, observed, endpoint = null) }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.withTransportState(false) }
        compose.onNodeWithTag("fakeVideoPlayer").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, observed.mounts); assertEquals(0, observed.serviceCalls) }
    }

    @Test fun ordinarySshReconnectBusyStateDoesNotDestroyRemotePlayback() {
        val observed = Observations()
        val state = mutableStateOf(viewing().withTransportState(false))
        val endpoint = endpoint()
        compose.setContent { Preview(state.value, observed, endpoint) }
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.onNodeWithTag("videoServiceToggle").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, observed.mounts); assertEquals(0, observed.disposals); assertEquals(0, observed.serviceCalls) }
    }

    @Test fun readingServiceStateAndReadFailureKeepTheExistingReceiveOnlyPlayer() {
        val observed = Observations()
        val state = mutableStateOf(viewing())
        val endpoint = endpoint()
        compose.setContent { Preview(state.value, observed, endpoint) }
        compose.runOnIdle { state.value = state.value.copy(videoServiceBusy = true,
            videoServiceState = VideoServiceState.UNKNOWN) }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.onNodeWithTag("videoServiceToggle").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.withTransportState(false).copy(
            videoServiceBusy = false, videoServiceError = "status read failed") }
        compose.onNodeWithTag("fakeVideoPlayer").assertExists()
        compose.onNodeWithTag("videoServiceToggle").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, observed.mounts); assertEquals(0, observed.disposals); assertEquals(0, observed.serviceCalls) }
    }
}
