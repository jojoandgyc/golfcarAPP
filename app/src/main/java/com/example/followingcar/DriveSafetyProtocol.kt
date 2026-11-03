package com.example.followingcar

/** Board measurements, separate from accepted motor targets. Values may report continued motion. */
data class DriveFeedback(
    val leftRpm: Int,
    val rightRpm: Int,
    val leftError: Int,
    val rightError: Int,
    val ageMs: Int,
    val samples: Int,
    val source: String,
) {
    val zeroSpeedConfirmed: Boolean get() = leftRpm == 0 && rightRpm == 0 &&
        leftError == 0 && rightError == 0 && ageMs in 0..250 && samples >= 2
}

internal data class ValidatedDriveFeedback(val targetStopped: Boolean, val stopped: Boolean,
    val feedback: DriveFeedback)

internal const val REQUIRED_DRIVE_SAFETY_VERSION = 2
internal const val DRIVE_FEEDBACK_FRESHNESS_MS = 500L

internal fun driveFeedbackIsFresh(receivedAtNanos: Long, ageAtReceiptMs: Long, nowNanos: Long): Boolean =
    receivedAtNanos > 0L && nowNanos >= receivedAtNanos && ageAtReceiptMs in 0..DRIVE_FEEDBACK_FRESHNESS_MS &&
        (nowNanos - receivedAtNanos) / 1_000_000L <= DRIVE_FEEDBACK_FRESHNESS_MS - ageAtReceiptMs

internal fun BoardUiState.hasFreshDriveFeedback(nowNanos: Long = System.nanoTime()): Boolean =
    driveFeedbackVerified && driveFeedbackIsFresh(driveLastFeedbackNanos, driveFeedbackAgeAtReceiptMs, nowNanos)

internal fun BoardUiState.canEditParkingAt(nowNanos: Long): Boolean =
    connected && driveEnabled && driveConfirmedStopped && driveLeft == 0 && driveRight == 0 &&
        hasFreshDriveFeedback(nowNanos) && driveFeedbackLeftRpm == 0 && driveFeedbackRightRpm == 0 &&
        !busy && !driveStarting && !driveStopping && !parkingUpdating && parkingSupported && parkingSettings != null &&
        !followBlocksControl && !motorActionUnconfirmed

internal fun DriveAck.hasFreshFeedback(nowNanos: Long = System.nanoTime()): Boolean = feedback != null &&
    driveFeedbackIsFresh(feedbackReceivedNanos, feedbackAgeAtReceiptMs, nowNanos)

internal fun BoardUiState.withDriveFeedback(ack: DriveAck): BoardUiState = copy(
    driveFeedbackLeftRpm = ack.feedback?.leftRpm,
    driveFeedbackRightRpm = ack.feedback?.rightRpm,
    driveFeedbackVerified = ack.hasFreshFeedback(),
    driveLastFeedbackNanos = ack.feedbackReceivedNanos,
    driveFeedbackAgeAtReceiptMs = ack.feedbackAgeAtReceiptMs,
)

internal fun driveBridgeCommand(dryRun: Boolean): String =
    "python3 -u /home/topeet/lianzhan/scripts/app_joystick_bridge.py --require-safety-version $REQUIRED_DRIVE_SAFETY_VERSION" +
        if (dryRun) " --dry-run" else ""

internal fun validateDriveSafetyVersion(raw: Any?, currentReadOnly: Any?) {
    require(safetyInteger(raw, "安全协议版本") == REQUIRED_DRIVE_SAFETY_VERSION) { "请更新开发板安全控制脚本" }
    require(currentReadOnly == true) { "开发板不支持只读驻车电流，拒绝启动控制" }
}

internal fun validatedDriveFeedback(values: Map<String, Any?>, dryRun: Boolean,
    targetLeft: Int, targetRight: Int): ValidatedDriveFeedback {
    val targetStopped = values["targetStopped"]
    val stopped = values["stopped"]
    require(targetStopped is Boolean && targetStopped == (targetLeft == 0 && targetRight == 0)) {
        "开发板零目标回执不一致"
    }
    require(stopped is Boolean) { "开发板实测停车字段无效" }
    val left = safetyInteger(values["feedbackLeftRpm"], "左轮反馈速度")
    val right = safetyInteger(values["feedbackRightRpm"], "右轮反馈速度")
    val leftError = safetyInteger(values["feedbackLeftError"], "左轮反馈故障")
    val rightError = safetyInteger(values["feedbackRightError"], "右轮反馈故障")
    val age = safetyInteger(values["feedbackAgeMs"], "反馈采样年龄")
    val samples = safetyInteger(values["feedbackSamples"], "反馈采样次数")
    require(leftError in 0..65535 && rightError in 0..65535) { "开发板轮速反馈故障码无效" }
    require(leftError == 0 && rightError == 0) { "开发板报告电机故障，停止控制" }
    require(age in 0..250 && samples >= 0) { "开发板轮速反馈缺失或已过期" }
    val source = values["feedbackSource"]
    require(source == if (dryRun) "simulated" else "hardware") { "开发板轮速反馈来源与控制模式不符" }
    val feedback = DriveFeedback(left, right, leftError, rightError, age, samples, source as String)
    require(!stopped || targetStopped && feedback.zeroSpeedConfirmed) { "开发板没有足够实测证据确认停车" }
    return ValidatedDriveFeedback(targetStopped, stopped, feedback)
}

internal fun validatedParkingSettings(values: Map<String, Any?>): ParkingSettings {
    val enabled = values["parkingEnabled"]
    require(enabled is Boolean) { "开发板驻车模式字段无效" }
    fun amps(key: String): Double {
        val value = values[key]
        require(value is Number && value.toDouble().isFinite() && value.toDouble() >= 0.0) {
            "开发板驻车电流读数无效"
        }
        return value.toDouble()
    }
    val settings = ParkingSettings(enabled, amps("parkingLeftCurrentAmps"), amps("parkingRightCurrentAmps"),
        amps("parkingMaxCurrentAmps"))
    require(!settings.enabled || settings.canEnable) { "开发板已启用驻车但电流配置无效" }
    return settings
}

private fun safetyInteger(raw: Any?, label: String): Int {
    require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() >= Int.MIN_VALUE.toDouble() &&
        raw.toDouble() <= Int.MAX_VALUE.toDouble() && raw.toDouble() == raw.toInt().toDouble()) {
        "开发板${label}字段无效"
    }
    return raw.toInt()
}
