package com.example.followingcar

/** The initiating action is kept separately from subsequent channel cleanup. */
enum class ControlStopSource {
    USER_STOP, EMERGENCY_STOP, PAGE_CHANGED, APP_PAUSED, WINDOW_FOCUS_LOST,
    DISCONNECT, TRANSPORT_LOST, VIEW_MODEL_CLEARED, CONTROL_SURFACE_HIDDEN,
}

/** A failed recovery check must not replace the first failure of the control session. */
internal fun BoardUiState.afterAvailabilityFailure(message: String, transportConnected: Boolean): BoardUiState =
    copy(followMessage = "控制占用检查失败，保持锁定", followError = message,
        connected = transportConnected, status = if (transportConnected) "已连接" else "连接已断开")
