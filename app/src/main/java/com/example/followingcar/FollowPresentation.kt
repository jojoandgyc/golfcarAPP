package com.example.followingcar

/** Current-action freshness only. Stop ACK and process-exit facts have separate UI semantics. */
internal data class FollowPresentation(
    val title: String,
    val subtitle: String,
    val fresh: Boolean = false,
    val targetFresh: Boolean = false,
    val rangeFresh: Boolean = false,
)

/** Pure presentation: composing this status never starts, stops, or inspects a control session. */
internal fun followPresentation(state: BoardUiState): FollowPresentation {
    val status = state.followStatus
    if (state.followUncertain) {
        return FollowPresentation("状态待确认", if (state.connected)
            "上次跟随是否结束尚未确认，请检查控制占用。"
        else "请重新连接开发板；进程退出和车辆停稳尚未确认。")
    }
    if (!state.connected) {
        return if (state.followActive || state.followStopping)
            FollowPresentation("连接中断", "跟随状态已失去确认，请重新连接开发板。")
        else FollowPresentation("尚未连接", "连接开发板后，即可设置参数并启动跟随。")
    }
    if (status?.runState == "FAULT" || !status?.fault.isNullOrBlank()) {
        return FollowPresentation(if (status?.processExited == true) "跟随异常结束" else "跟随出现异常",
            if (status?.processExited == true) "进程已退出；停车回执与机械停稳状态请查看详情。"
            else "本次跟随出现异常，请查看异常信息；进程退出仍需确认。")
    }
    // Terminal evidence takes priority over a contradictory or old RUNNING field.
    if (status?.processExited == true) {
        return FollowPresentation("本次跟随已结束", "跟随进程已退出；这不代表车轮已经停稳。")
    }
    if (state.followStopping || (state.followActive &&
            (status?.stopRequested == true || status?.runState == "STOPPING"))) {
        return FollowPresentation("正在停止跟随", "正在等待进程退出，停车结果以回执为准。")
    }
    if (!state.followActive) {
        return when {
            state.manualActive -> FollowPresentation("手动驾驶中", "结束手动驾驶后，即可启动自动跟随。")
            !state.followError.isNullOrBlank() -> FollowPresentation("上次操作未完成", "请查看异常信息，确认状态后重新启动。")
            else -> FollowPresentation("准备开始跟随", if (state.followExecute)
                "已选择实车模式，点击启动后可驱动电机。"
            else "已选择影子模式，仅计算跟随指令，不驱动电机。")
        }
    }
    if (status == null) {
        return FollowPresentation("正在启动跟随", "正在准备视频服务、相机和跟随会话。")
    }
    if (status.statusAgeMs?.let { it in 0..2_000 } != true) {
        return FollowPresentation("状态已过期", "正在等待新的状态回执，当前动作与距离未知。")
    }
    if (status.runState == "STARTING") {
        return FollowPresentation("正在启动跟随", "正在等待相机和跟随程序就绪。")
    }
    if (status.runState == "STOPPED") {
        return FollowPresentation("等待进程退出", "软件已停止运行，进程退出与停车结果仍需确认。")
    }
    if (status.runState != "RUNNING") {
        return FollowPresentation("运行状态未知", "正在等待可识别的状态回执。")
    }

    val targetFresh = status.targetAgeMs?.let { it in 0..600 } == true
    val rangeFresh = targetFresh && status.targetDetected == true &&
        status.rangeAgeMs?.let { it in 0..600 } == true &&
        status.distanceMeters?.let { it.isFinite() && it >= 0 } == true
    val mode = if (status.execute) "实车模式" else "影子模式 · 不驱动电机"
    val targetKnown = targetFresh && status.targetDetected == true
    // FOLLOW is confirmed in the repository's protocol fixtures. Other behavior/reason
    // values are open-ended runtime data and remain available verbatim in inline details.
    val title = if (status.behavior == "FOLLOW" && targetKnown)
        if (status.execute) "正在跟随" else "影子跟随中"
    else "运行中"
    val detail = when {
        targetFresh && status.targetDetected == false -> "暂未检测到目标"
        !targetKnown -> "目标状态未知或已过期"
        status.behavior == "FOLLOW" -> "已识别跟随目标"
        else -> "跟随程序已运行，当前动作请查看详情"
    }
    return FollowPresentation(title, "$mode · $detail", fresh = true,
        targetFresh = targetFresh, rangeFresh = rangeFresh)
}
