package com.example.followingcar

/** Current-action freshness only. Stop ACK and process-exit facts have separate UI semantics. */
internal data class FollowPresentation(
    val title: String,
    val subtitle: String,
    val fresh: Boolean = false,
    val targetFresh: Boolean = false,
    val rangeFresh: Boolean = false,
)

/** Only subtract phone monotonic times; board ages describe the snapshot at receipt. */
internal fun followReceivedAgeMs(state: BoardUiState, nowElapsedMs: Long?): Long? {
    val received = state.followLastReplyElapsedMs ?: return 0L // Compatibility with snapshots lacking a receipt clock.
    val now = nowElapsedMs ?: return null
    return (now - received).takeIf { it >= 0 }
}

internal fun followAgeIsFresh(ageMs: Long?, receivedAgeMs: Long?, maximumMs: Long): Boolean =
    ageMs != null && receivedAgeMs != null && ageMs in 0..maximumMs &&
        receivedAgeMs in 0..(maximumMs - ageMs)

/** Pure presentation: composing this status never starts, stops, or inspects a control session. */
internal fun followPresentation(state: BoardUiState, nowElapsedMs: Long? = null): FollowPresentation {
    val status = state.followStatus
    val fault = status?.runState == "FAULT" || !status?.fault.isNullOrBlank()
    if (state.followStartCancelled && !state.followActive && !state.followStopping &&
        !state.followUncertain && !fault && state.followError == null &&
        (status == null || state.followTerminal)) {
        return FollowPresentation("启动已取消", if (state.followTerminal)
            "已收到会话正常结束回执；这不代表车轮已经停稳。"
        else "未建立跟随控制会话，可重新选择参数后启动。")
    }
    // A legal terminal ACK remains authoritative even when the expected EOF closes transport.
    if (state.followTerminal && status != null) {
        return if (fault) FollowPresentation("跟随异常结束", "跟随进程已退出，故障信息已保留；机械停稳仍未确认。")
        else FollowPresentation("跟随程序已退出", "本次会话已结束；这不代表车轮已经停稳。")
    }
    if (state.followUncertain) {
        return FollowPresentation("状态待确认", if (state.connected)
            "上次跟随是否结束尚未确认，请检查控制占用；停车结果未知。"
        else "请重新连接开发板；进程退出和车辆停稳尚未确认。")
    }
    if (!state.connected) {
        return if (state.followActive || state.followStopping)
            FollowPresentation("连接中断", "跟随状态已失去确认，停车结果未知；请重新连接开发板。")
        else FollowPresentation("尚未连接", "连接开发板后，即可设置参数并启动跟随。")
    }
    if (!state.followActive && !state.followStopping && status != null) {
        return FollowPresentation(if (fault) "上次跟随异常" else "上次会话结果未确认",
            "显示上次记录；控制占用已释放，仍未取得上次会话终态或机械停稳确认。")
    }
    if (fault) {
        return FollowPresentation(
            if (status?.finalStatusPending == true || status?.processExited == true) "故障，等待关闭结果" else "跟随出现异常",
            if (status?.processExited == true) "进程已退出，仍在等待最终关闭结果；故障信息会保留。"
            else "进程尚未确认退出，继续读取状态；暂不能再次启动。")
    }
    if (status?.finalStatusPending == true) {
        return FollowPresentation("正在确认退出结果", "进程已退出，正在接收最终关闭状态；机械停稳仍未确认。")
    }
    if (state.followStopping || (state.followActive &&
            (status?.stopRequested == true || status?.runState == "STOPPING"))) {
        return FollowPresentation("正在停止跟随", "已请求停止，继续等待会话结束；机械停稳仍未确认。")
    }
    if (status?.processExited == true) {
        return if (status.runState == "STOPPED" && !status.stopRequested)
            FollowPresentation("已连接，等待开始", "控制会话已就绪，尚未创建跟随进程。")
        else FollowPresentation("等待会话结束确认", "进程已退出，尚未收到完整终态；机械停稳仍未确认。")
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
        return FollowPresentation("正在启动跟随", "正在检查配置并准备跟随会话。")
    }
    val receivedAgeMs = followReceivedAgeMs(state, nowElapsedMs)
    if (receivedAgeMs == null || receivedAgeMs > 2_000) {
        return FollowPresentation("状态已过期", "正在等待新的状态回执，当前动作与距离未知。")
    }
    // Startup snapshots can have no worker telemetry yet; a current ACK still reports a phase.
    if (status.runState == "STARTING") {
        return FollowPresentation("正在启动跟随", status.startup?.let { "${it.stage.startupStageLabel()}；可随时停止。" }
            ?: "正在等待相机和跟随程序就绪；可随时停止。")
    }
    if (!followAgeIsFresh(status.statusAgeMs, receivedAgeMs, 2_000)) {
        return FollowPresentation("状态已过期", "正在等待新的状态回执，当前动作与距离未知。")
    }
    if (status.runState == "STOPPED") {
        return FollowPresentation("等待进程退出", "软件已停止运行，进程退出与停车结果仍需确认。")
    }
    if (status.runState != "RUNNING") {
        return FollowPresentation("运行状态未知", "正在等待可识别的状态回执。")
    }

    val targetFresh = followAgeIsFresh(status.targetAgeMs, receivedAgeMs, 600)
    val rangeFresh = targetFresh && status.targetDetected == true &&
        followAgeIsFresh(status.rangeAgeMs, receivedAgeMs, 600) &&
        status.distanceMeters?.let { it.isFinite() && it >= 0 } == true
    val mode = if (status.execute) "实车模式" else "影子模式 · 不驱动电机"
    val targetKnown = targetFresh && status.targetDetected == true
    val following = status.behavior == "FOLLOW" && targetKnown && status.commandStop == false
    val title = when {
        status.commandStop == true -> "运行中，已发停止指令"
        following -> if (status.execute) "正在跟随" else "影子跟随中"
        else -> "运行中"
    }
    val detail = when {
        status.commandStop == true -> "控制指令为停止，机械停稳仍未确认"
        targetFresh && status.targetDetected == false -> "暂未检测到目标"
        !targetKnown -> "目标状态未知或已过期"
        following -> "已识别跟随目标"
        else -> "跟随程序已运行，当前动作请查看详情"
    }
    return FollowPresentation(title, "$mode · $detail", fresh = true,
        targetFresh = targetFresh, rangeFresh = rangeFresh)
}

internal fun String.startupStageLabel(): String = when (this) {
    "worker_start", "environment" -> "准备运行环境"
    "preflight" -> "检查设备占用"
    "runtime_import" -> "加载跟随程序"
    "configuration" -> "校验配置"
    "runtime_construct" -> "建立跟随实例"
    "gestures" -> "初始化手势服务"
    "detector" -> "加载人员检测模型"
    "reid" -> "加载身份特征模型"
    "gallery" -> "准备目标图库"
    "camera" -> "打开相机"
    "services" -> "准备辅助服务"
    "motor" -> "初始化电机适配"
    else -> "启动中"
}
