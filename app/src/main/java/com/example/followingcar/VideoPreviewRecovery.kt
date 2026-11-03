package com.example.followingcar

/** Receiving an already-requested remote stream never grants permission to control the car. */
internal fun BoardUiState.canPreviewVideo(remoteEndpointAvailable: Boolean): Boolean =
    videoPreviewWanted && !followBlocksControl && videoServiceState != VideoServiceState.STOPPED &&
        (if (remoteMode) remoteEndpointAvailable else connected)

/** Only fixed codes reach diagnostics; never store browser messages, URLs, SDP or credentials. */
internal fun remoteVideoFailureCode(title: String?): String? {
    val code = title?.takeIf { it.startsWith("FollowingCarVideoError:") }
        ?.removePrefix("FollowingCarVideoError:") ?: return null
    return code.takeIf { it in setOf(
        "invalid_ice_config", "missing_turn_server", "invalid_offer", "invalid_candidate",
        "invalid_session_location", "invalid_answer", "candidate_overflow", "non_relay_candidate",
        "non_relay_transport", "media_disconnected", "media_timeout", "media_stalled",
        "request_timeout", "network_error", "webrtc_operation_failed", "unexpected_error",
        "turn_quota_exceeded", "first_frame_timeout",
    ) || Regex("http_[1-5][0-9]{2}").matches(it) }
}
