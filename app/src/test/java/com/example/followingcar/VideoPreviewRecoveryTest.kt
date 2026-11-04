package com.example.followingcar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure viewing policy. No SSH, video-service commands, credentials or Android network APIs. */
class VideoPreviewRecoveryTest {
    @Test fun scopedIpv6RescueUsesPublicVideoWithoutGrantingControlOrStartingVideo() {
        val rescue = BoardUiState(host = "fe80::1234%wlan0", connected = true)
        assertTrue(rescue.usesRemoteVideo)
        assertFalse(rescue.canPreviewVideo(remoteEndpointAvailable = true))
        val watching = rescue.copy(videoPreviewWanted = true, videoServiceState = VideoServiceState.RUNNING)
        assertFalse(watching.canPreviewVideo(remoteEndpointAvailable = false))
        assertTrue(watching.canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(watching.manualActive)
        assertFalse(watching.followActive)
    }

    private fun remoteViewing() = BoardUiState(
        connected = true, remoteMode = true, videoServiceState = VideoServiceState.RUNNING,
        videoPreviewWanted = true,
    )

    @Test fun remoteViewingSurvivesSshLossWithoutPreservingControlPermission() {
        val disconnected = remoteViewing().withTransportState(false)
        assertFalse(disconnected.connected)
        assertEquals(VideoServiceState.UNKNOWN, disconnected.videoServiceState)
        assertTrue(disconnected.videoPreviewWanted)
        assertTrue(disconnected.canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(disconnected.canToggleVideoService)
        assertFalse(disconnected.canEnableDrive)
        assertFalse(disconnected.canStartFollow)
        assertFalse(disconnected.canExecuteTerminal)
        assertNull(disconnected.followConfiguration)
    }

    @Test fun lostSshRequiresRemoteModeLoadedEndpointAndPriorViewingIntent() {
        val disconnected = remoteViewing().withTransportState(false)
        assertFalse(remoteViewing().canPreviewVideo(remoteEndpointAvailable = false))
        assertFalse(disconnected.canPreviewVideo(remoteEndpointAvailable = false))
        assertFalse(disconnected.copy(remoteMode = false).canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(disconnected.copy(videoPreviewWanted = false).canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(BoardUiState(remoteMode = true).canPreviewVideo(remoteEndpointAvailable = true))
    }

    @Test fun remoteRecoveryAcceptsUnconfirmedServiceButNeverConfirmedStoppedService() {
        val disconnected = remoteViewing().withTransportState(false)
        assertTrue(disconnected.copy(videoServiceState = VideoServiceState.RUNNING)
            .canPreviewVideo(remoteEndpointAvailable = true))
        assertTrue(disconnected.copy(videoServiceState = VideoServiceState.UNKNOWN)
            .canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(disconnected.copy(videoServiceState = VideoServiceState.STOPPED)
            .canPreviewVideo(remoteEndpointAvailable = true))
    }

    @Test fun cameraOwnershipStillBlocksRemoteViewing() {
        val disconnected = remoteViewing().withTransportState(false)
        listOf(
            disconnected.copy(followActive = true),
            disconnected.copy(followStopping = true),
            disconnected.copy(followUncertain = true),
        ).forEach { assertFalse(it.canPreviewVideo(remoteEndpointAvailable = true)) }
    }

    @Test fun inspectingServiceOrAnInspectionErrorDoesNotDestroyAnExistingPreview() {
        val disconnected = remoteViewing().withTransportState(false)
        assertTrue(disconnected.copy(videoServiceBusy = true)
            .canPreviewVideo(remoteEndpointAvailable = true))
        assertTrue(disconnected.copy(videoServiceError = "status read failed")
            .canPreviewVideo(remoteEndpointAvailable = true))
        assertTrue(remoteViewing().copy(videoServiceBusy = true, videoServiceError = "status read failed")
            .canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(disconnected.copy(videoPreviewWanted = false, videoServiceBusy = true)
            .canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(disconnected.copy(videoPreviewWanted = false, videoServiceError = "stop not confirmed")
            .canPreviewVideo(remoteEndpointAvailable = true))
    }

    @Test fun unrelatedSshWorkDoesNotInterruptReceiveOnlyRemotePreview() {
        val reconnecting = remoteViewing().withTransportState(false).copy(busy = true)
        assertTrue(reconnecting.canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(reconnecting.canToggleVideoService)
        assertTrue(remoteViewing().copy(busy = true).canPreviewVideo(remoteEndpointAvailable = true))
    }

    @Test fun connectedLocalPreviewAlsoRequiresExplicitViewingIntent() {
        assertFalse(BoardUiState(connected = true, videoServiceState = VideoServiceState.UNKNOWN)
            .canPreviewVideo(remoteEndpointAvailable = false))
        assertFalse(BoardUiState(connected = true, videoServiceState = VideoServiceState.RUNNING)
            .canPreviewVideo(remoteEndpointAvailable = false))
        assertTrue(BoardUiState(connected = true, videoServiceState = VideoServiceState.UNKNOWN,
            videoPreviewWanted = true)
            .canPreviewVideo(remoteEndpointAvailable = false))
        assertTrue(BoardUiState(connected = true, videoServiceState = VideoServiceState.RUNNING,
            videoPreviewWanted = true)
            .canPreviewVideo(remoteEndpointAvailable = false))
        assertFalse(BoardUiState(connected = true, videoServiceState = VideoServiceState.STOPPED)
            .canPreviewVideo(remoteEndpointAvailable = false))
        assertFalse(BoardUiState(connected = false, videoServiceState = VideoServiceState.RUNNING,
            videoPreviewWanted = true).canPreviewVideo(remoteEndpointAvailable = false))
    }

    @Test fun explicitViewingCancellationSurvivesLaterTransportInvalidation() {
        val pausedWhileConnected = remoteViewing().copy(videoPreviewWanted = false)
        assertFalse(pausedWhileConnected.canPreviewVideo(remoteEndpointAvailable = true))
        val cancelled = pausedWhileConnected.withTransportState(false)
        assertFalse(cancelled.videoPreviewWanted)
        assertFalse(cancelled.canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(cancelled.withTransportState(false).canPreviewVideo(remoteEndpointAvailable = true))
    }

    @Test fun videoRecoveryDoesNotClearUnconfirmedMotorStop() {
        val disconnected = remoteViewing().copy(driveEnabled = true, driveConfirmedStopped = true)
            .withTransportState(false)
        assertTrue(disconnected.canPreviewVideo(remoteEndpointAvailable = true))
        assertFalse(disconnected.driveConfirmedStopped)
        assertFalse(disconnected.canEnableDrive)
    }

    @Test fun remoteFailureDiagnosticsAcceptOnlyRecognizedFixedCodesAndHttpStatuses() {
        val codes = listOf("invalid_ice_config", "missing_turn_server", "invalid_offer", "invalid_candidate",
            "invalid_session_location", "invalid_answer", "candidate_overflow", "non_relay_candidate",
            "non_relay_transport", "media_disconnected", "media_timeout", "media_stalled",
            "request_timeout", "network_error", "webrtc_operation_failed", "unexpected_error",
            "turn_quota_exceeded", "first_frame_timeout", "http_100", "http_401", "http_503", "http_599")
        codes.forEach { code -> assertEquals(code, remoteVideoFailureCode("FollowingCarVideoError:$code")) }
    }

    @Test fun rawBrowserErrorsUrlsSdpAndCredentialTextNeverBecomeDiagnosticCodes() {
        val sensitiveTitles = listOf(
            "FollowingCarVideoError:Failed to fetch https://video.example.test/rgbd/whep?token=TEST_ONLY",
            "FollowingCarVideoError:http_503?token=TEST_ONLY",
            "FollowingCarVideoError:network_error password=TEST_ONLY",
            "FollowingCarVideoError:turn_quota_exceeded;credential=TEST_ONLY",
            "FollowingCarVideoError:media_timeout\na=ice-pwd:TEST_ONLY",
            "FollowingCarVideoError:v=0\r\na=ice-ufrag:TEST_ONLY\r\na=ice-pwd:TEST_ONLY",
            "FollowingCarVideoError:turns:TEST_ONLY@video.example.test:5349",
            "FollowingCarVideoError:icecandidateerror 486 Allocation Bandwidth Quota Reached",
            "FollowingCarVideoError:" + "x".repeat(100_000),
        )
        sensitiveTitles.forEach { assertNull(remoteVideoFailureCode(it)) }
    }

    @Test fun failureCodeParsingRejectsPrefixesSuffixesWhitespaceAndInvalidStatusRanges() {
        listOf(null, "", "media_timeout", "FollowingCarVideo:media_timeout",
            " FollowingCarVideoError:media_timeout", "followingcarvideoerror:media_timeout",
            "FollowingCarVideoError:", "FollowingCarVideoError:media_timeout ",
            "FollowingCarVideoError:media_timeout\n", "FollowingCarVideoError:media_timeout\u0000",
            "FollowingCarVideoError:media_timeout\u200b", "FollowingCarVideoError:MEDIA_TIMEOUT",
            "FollowingCarVideoError:http_099", "FollowingCarVideoError:http_600",
            "FollowingCarVideoError:http_2000", "FollowingCarVideoError:http_20",
            "FollowingCarVideoError:http_２００", "FollowingCarVideoError:http_200\n",
        ).forEach { assertNull(remoteVideoFailureCode(it)) }
    }
}
