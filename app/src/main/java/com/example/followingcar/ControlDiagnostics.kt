package com.example.followingcar

/** The initiating action is kept separately from subsequent channel cleanup. */
enum class ControlStopSource {
    USER_STOP, EMERGENCY_STOP, PAGE_CHANGED, APP_PAUSED, WINDOW_FOCUS_LOST,
    DISCONNECT, TRANSPORT_LOST, VIEW_MODEL_CLEARED, CONTROL_SURFACE_HIDDEN,
}

/** A failed recovery check must not replace the first failure of the control session. */
internal fun BoardUiState.afterAvailabilityFailure(message: String, transportConnected: Boolean): BoardUiState =
    withTransportState(transportConnected).copy(followMessage = "控制占用检查失败，保持锁定", followError = message,
        connected = transportConnected, status = if (transportConnected) "已连接" else "连接已断开")

/** Losing SSH invalidates observations; it is never evidence that the vehicle has stopped. */
internal fun BoardUiState.withTransportState(transportConnected: Boolean): BoardUiState =
    if (transportConnected) copy(connected = true) else copy(
        connected = false, status = "连接已断开",
        videoServiceState = VideoServiceState.UNKNOWN, videoServiceMessage = "连接中断，视频服务状态未确认",
        followConfiguration = null, followConfigurationError = null,
        followStatus = followStatus?.copy(statusAgeMs = null),
        driveConfirmedStopped = if (manualActive) false else driveConfirmedStopped,
    )

/** One unresolved control session survives rotation of the separate ten-entry fault history. */
internal data class FollowRecoverySnapshot(val context: DiagnosticContext, val firstFailure: DiagnosticFailure? = null) {
    /** Keep unresolved evidence in exports even after the ten-entry history has rotated. */
    fun exportMetadata(): Map<String, Any?> = mapOf(
        "pendingFollowOperationId" to context.operationId,
        "pendingFollowConnectionId" to context.connectionId,
        "pendingFollowRunId" to context.runId,
        "pendingFollowFirstFailureTime" to firstFailure?.time,
        "pendingFollowFirstFailureEvent" to firstFailure?.event,
        "pendingFollowFirstFailureMessage" to firstFailure?.message,
    )

    fun storedValues(): Map<String, String> = buildMap {
        put("pending", "true")
        put("operationId", context.operationId)
        context.connectionId?.let { put("connectionId", it) }
        context.runId?.let { put("runId", it) }
        firstFailure?.let {
            put("failureTime", it.time)
            put("failureEvent", it.event)
            put("failureMessage", it.message)
        }
    }

    companion object {
        fun restore(values: Map<String, *>): FollowRecoverySnapshot? {
            if (values["pending"] != "true") return null
            val operationId = (values["operationId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val context = DiagnosticContext(operationId, values["connectionId"] as? String, values["runId"] as? String)
            val failureTime = values["failureTime"] as? String
            val failureEvent = values["failureEvent"] as? String
            val failureMessage = values["failureMessage"] as? String
            val first = if (failureTime != null && failureEvent != null && failureMessage != null)
                DiagnosticFailure(failureTime, failureEvent, failureMessage, context) else null
            return FollowRecoverySnapshot(context, first)
        }
    }
}

internal fun selectFollowFirstFailure(
    context: DiagnosticContext,
    recovery: FollowRecoverySnapshot?,
    displayed: DiagnosticFailure?,
    history: List<DiagnosticFailure>,
): DiagnosticFailure? = recovery?.firstFailure?.takeIf { it.context.operationId == context.operationId }
    ?: displayed?.takeIf { it.context.operationId == context.operationId }
    ?: history.firstOrNull { it.context.operationId == context.operationId }
