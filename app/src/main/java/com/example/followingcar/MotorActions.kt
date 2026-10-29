package com.example.followingcar

/** Host UART settings for a subsequent request, not writes to the controller configuration. */
data class MotorSerialSettings(val slave: Int = 1, val baudRate: Int = 115200, val parityCode: Int = 0) {
    val isValid: Boolean get() = slave in 1..254 && baudRate in BAUD_RATES && parityCode in 0..3
    val parity: String get() = when (parityCode) { 1 -> "E"; 2 -> "O"; else -> "N" }
    val stopBits: Int get() = if (parityCode == 3) 2 else 1
    val label: String get() = "从站 $slave · $baudRate baud · ${PARITY_LABELS[parityCode] ?: "未知格式"}"
    companion object {
        val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200)
        val PARITY_LABELS = mapOf(0 to "8N1", 1 to "8E1", 2 to "8O1", 3 to "8N2")
    }
}

/** All eight documented logical command interfaces. No reserved register is exposed. */
enum class MotorAction(
    val id: String, val label: String, val address: Int, val words: Int,
    val minRaw: Long, val maxRaw: Long, val unit: String = "", val requiresDuration: Boolean = false,
    val choices: Map<Long, String> = emptyMap(), val note: String,
) {
    RIGHT_STOP("right_stop", "右电机停止", 0x40, 1, 0, 2,
        choices = mapOf(0L to "正常停止", 1L to "紧急停止", 2L to "自由停止"),
        note = "独立模式停止右侧；同源和差速模式停止两侧。回执不代表车轮停稳。"),
    RIGHT_TARGET_SPEED("right_target_speed", "右 / 直行目标速度", 0x41, 2, -110000, 110000, "RPM", true,
        note = "独立模式控制右侧，同源模式控制两侧，差速模式控制直行。限时运行后板端请求停止。"),
    RIGHT_BRAKE("right_brake", "右电机抱闸控制", 0x43, 1, 0, 1,
        choices = mapOf(0L to "断开抱闸", 1L to "闭合抱闸"), note = "直接控制抱闸输出，请核对硬件接线及抱闸输出配置。"),
    LEFT_STOP("left_stop", "左电机停止", 0x44, 1, 0, 2,
        choices = mapOf(0L to "正常停止", 1L to "紧急停止", 2L to "自由停止"),
        note = "仅独立模式有效；同源和差速模式应使用右侧停止接口。回执不代表车轮停稳。"),
    LEFT_TARGET_SPEED("left_target_speed", "左 / 转弯目标速度", 0x45, 2, -110000, 110000, "RPM", true,
        note = "独立模式控制左侧，差速模式控制转弯；同源模式无效。限时运行后板端请求停止。"),
    LEFT_BRAKE("left_brake", "左电机抱闸控制", 0x47, 1, 0, 1,
        choices = mapOf(0L to "打开抱闸", 1L to "关闭抱闸"), note = "直接控制抱闸输出，请核对硬件接线及抱闸输出配置。"),
    FACTORY_RESET("factory_reset", "恢复出厂参数", 0x48, 1, 1, 1,
        choices = mapOf(1L to "恢复出厂"), note = "会覆盖已调好的参数并可能改变通讯设置；收到回执后仍需重新连接并读取。"),
    PHASE_LEARNING("phase_learning", "相序学习 / 取消测试", 0x49, 1, 0, 2, requiresDuration = true,
        choices = mapOf(0L to "取消测试", 1L to "左电机相序学习", 2L to "右电机相序学习"),
        note = "学习可能驱动电机；限时结束由板端发送取消。学习是否完成以重新读取学习状态为准。"),
    ;

    val addressLabel: String get() = if (words == 1) "0x%04X".format(address)
        else "0x%04X–0x%04X".format(address, address + words - 1)
    fun durationRange(value: Long): IntRange = when {
        this == PHASE_LEARNING && value != 0L -> 100..60_000
        this == RIGHT_TARGET_SPEED || this == LEFT_TARGET_SPEED -> 100..3_000
        else -> 0..0
    }
    fun accepts(value: Long): Boolean = value in minRaw..maxRaw && (choices.isEmpty() || value in choices)
    fun isStop(value: Long): Boolean = this == RIGHT_STOP || this == LEFT_STOP || this == PHASE_LEARNING && value == 0L
}

data class MotorActionPlan(val snapshotId: String, val action: MotorAction, val value: Long = 0,
    val durationMs: Int = 0) {
    fun validate() {
        require(snapshotId.matches(Regex("[a-f0-9]{64}"))) { "请重新读取当前设备" }
        require(action.accepts(value)) { "动作参数超出协议范围" }
        require(durationMs in action.durationRange(value)) { "动作持续时间超出允许范围" }
    }
}

enum class MotorActionOutcome { ACKNOWLEDGED, WRITE_OUTCOME_UNKNOWN, NOT_ATTEMPTED }

data class MotorActionResult(val action: MotorAction, val value: Long, val durationMs: Int,
    val outcome: MotorActionOutcome, val cleanupConfirmed: Boolean? = null,
    val reconnectRequired: Boolean = false, val error: String? = null)

fun motorActionValidationError(state: MotorConfigurationUiState, action: MotorAction,
    valueText: String, durationText: String): String? {
    val snapshot = state.snapshot ?: return "请先读取当前设备"
    if (state.busy || state.requiresRead) return "操作处理中或读值已失效，请重新读取"
    if (!state.connection.isValid) return "串口连接参数无效"
    if (snapshot.connection != state.connection) return "连接参数已变化，请重新读取"
    val value = valueText.trim().takeIf { it.matches(Regex("-?[0-9]{1,12}")) }?.toLongOrNull()
        ?: return "请输入整数动作参数"
    if (!action.accepts(value)) return "动作参数应为 ${action.minRaw}–${action.maxRaw} ${action.unit}".trim()
    if (!(if (action.isStop(value)) snapshot.stopAllowed else snapshot.writeAllowed))
        return snapshot.writeBlockedReason ?: "板端尚未确认可执行状态"
    val range = action.durationRange(value)
    val duration = if (range == 0..0 && durationText.isBlank()) 0 else durationText.trim().toIntOrNull()
        ?: return "请输入整数毫秒数"
    if (duration !in range) return if (range == 0..0) "此动作不使用持续时间，请设为 0"
        else "持续时间应为 ${range.first}–${range.last} ms"
    return runCatching { MotorActionPlan(snapshot.snapshotId, action, value, duration).validate() }.exceptionOrNull()?.message
}

fun motorActionPlan(state: MotorConfigurationUiState, action: MotorAction,
    valueText: String, durationText: String): MotorActionPlan? {
    if (motorActionValidationError(state, action, valueText, durationText) != null) return null
    return MotorActionPlan(requireNotNull(state.snapshot).snapshotId, action, valueText.trim().toLong(),
        durationText.trim().toIntOrNull() ?: 0)
}
