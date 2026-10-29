package com.example.followingcar

import java.math.BigDecimal
import java.math.RoundingMode

enum class MotorConfigurationGroup(val label: String) {
    COMMON("通用电机参数"), RIGHT_MOTOR("右电机"), LEFT_MOTOR("左电机"), SYSTEM("保护与控制模式"),
    BRAKE("抱闸输出"), RS485("RS485 通讯"), CAN("CAN 通讯"), TTL("TTL 通讯"),
    PID_SPEED("速度环 PID"), PID_D("D 轴 PID"), PID_Q("Q 轴 PID"), PID_PARKING("驻车 PID"),
}

enum class MotorConfigurationWriteMode(val label: String, val protocolValue: String) {
    PERSISTENT("保存参数（FC10）", "persistent"), TEMPORARY("临时设置（FC06）", "temporary"),
}

/** Exact logical register fields, including signed and high-word-first multiword values. */
enum class MotorConfigurationField(
    val id: String,
    val label: String,
    val unit: String,
    val decimalPlaces: Int,
    val minRaw: Long,
    val maxRaw: Long,
    val address: Int = 0,
    val words: Int = if (maxRaw > 65535) 2 else 1,
    val signed: Boolean = minRaw < 0,
    val divisor: Long = if (decimalPlaces == 2) 100 else 1,
    val group: MotorConfigurationGroup = MotorConfigurationGroup.COMMON,
    val choices: Map<Long, String> = emptyMap(),
    val note: String? = null,
    val phasePair: Boolean = false,
    val supportsTemporary: Boolean = words == 1,
) {
    MAX_OUTPUT_CURRENT("max_output_current", "最大输出电流（单路）", "A", 2, 50, 6000, address = 0x0058, words = 1, divisor = 100, group = MotorConfigurationGroup.COMMON),
    MAX_FORWARD_SPEED("max_forward_speed", "正转最大速度", "RPM", 0, 0, 110000, address = 0x0059, words = 2, divisor = 1, group = MotorConfigurationGroup.COMMON, note = "临时设置分两帧 FC06 写入，过程中保持中间值合法；异常后结果可能不完整，必须重新读取，不自动重试。", supportsTemporary = true),
    MAX_REVERSE_SPEED("max_reverse_speed", "反转最大速度", "RPM", 0, 0, 110000, address = 0x005B, words = 2, divisor = 1, group = MotorConfigurationGroup.COMMON, note = "临时设置分两帧 FC06 写入，过程中保持中间值合法；异常后结果可能不完整，必须重新读取，不自动重试。", supportsTemporary = true),
    MIN_SPEED("min_speed", "最小转速", "RPM", 0, 0, 110000, address = 0x005D, words = 2, divisor = 1, group = MotorConfigurationGroup.COMMON, note = "临时设置分两帧 FC06 写入，过程中保持中间值合法；异常后结果可能不完整，必须重新读取，不自动重试。", supportsTemporary = true),
    CLOSED_LOOP_ACCELERATION("closed_loop_acceleration", "闭环加速度", "RPM/s", 0, 10, 65535, address = 0x005F, words = 1, divisor = 1, group = MotorConfigurationGroup.COMMON),
    CLOSED_LOOP_DECELERATION("closed_loop_deceleration", "闭环减速度", "RPM/s", 0, 10, 65535, address = 0x0060, words = 1, divisor = 1, group = MotorConfigurationGroup.COMMON),
    AUTOMATIC_REVERSAL_MODE("automatic_reversal_mode", "自动换向模式", "", 0, 0, 1, address = 0x0064, words = 1, divisor = 1, group = MotorConfigurationGroup.COMMON, choices = mapOf(0L to "正常换向", 1L to "急停换向")),
    HALL_ELECTRICAL_ANGLE("hall_electrical_angle", "霍尔电角度", "", 0, 0, 1, address = 0x0065, words = 1, divisor = 1, group = MotorConfigurationGroup.COMMON, choices = mapOf(0L to "60°", 1L to "120°")),
    SENSOR_TYPE("sensor_type", "传感器类型", "", 0, 0, 1, address = 0x0066, words = 1, divisor = 1, group = MotorConfigurationGroup.COMMON, choices = mapOf(0L to "霍尔", 1L to "霍尔 + AB 编码器")),
    RIGHT_POLE_PAIRS("right_pole_pairs", "右电机极对数", "", 0, 0, 65535, address = 0x0068, words = 1, divisor = 1, group = MotorConfigurationGroup.RIGHT_MOTOR, note = "手册允许 0–65535；0 的实际含义未说明。"),
    RIGHT_PHASE_1_2("right_phase_1_2", "右电机第 1/2 相相序", "", 0, 0, 1542, address = 0x0069, words = 1, divisor = 1, group = MotorConfigurationGroup.RIGHT_MOTOR, note = "低字节为第 1 相，高字节为第 2 相；两个字节各为 0–6。", phasePair = true),
    RIGHT_PHASE_3_4("right_phase_3_4", "右电机第 3/4 相相序", "", 0, 0, 1542, address = 0x006A, words = 1, divisor = 1, group = MotorConfigurationGroup.RIGHT_MOTOR, note = "低字节为第 3 相，高字节为第 4 相；两个字节各为 0–6。", phasePair = true),
    RIGHT_PHASE_5_6("right_phase_5_6", "右电机第 5/6 相相序", "", 0, 0, 1542, address = 0x006B, words = 1, divisor = 1, group = MotorConfigurationGroup.RIGHT_MOTOR, note = "低字节为第 5 相，高字节为第 6 相；两个字节各为 0–6。", phasePair = true),
    RIGHT_PHASE_LEARNING_STATE("right_phase_learning_state", "右电机相序学习状态", "", 0, 0, 1, address = 0x006C, words = 1, divisor = 1, group = MotorConfigurationGroup.RIGHT_MOTOR, choices = mapOf(0L to "未学习", 1L to "已经学习"), note = "写入标志不会执行相序学习；学习操作位于测试控制。"),
    RIGHT_PARKING_CURRENT("right_parking_current", "右电机电子驻车最大电流", "A", 2, 0, 6000, address = 0x006D, words = 1, divisor = 100, group = MotorConfigurationGroup.RIGHT_MOTOR, note = "必须不超过最大输出电流。手册 0 值原文为“0：不停机锁相”，不据此宣称机械抱闸状态。"),
    RIGHT_FOC_COMPENSATION_ANGLE("right_foc_compensation_angle", "右电机 FOC 相位补偿角", "°", 2, -18000, 18000, address = 0x006E, words = 1, divisor = 100, group = MotorConfigurationGroup.RIGHT_MOTOR),
    RIGHT_FOC_ADVANCE_ANGLE("right_foc_advance_angle", "右电机 FOC 超前角", "°", 2, -18000, 18000, address = 0x006F, words = 1, divisor = 100, group = MotorConfigurationGroup.RIGHT_MOTOR),
    RIGHT_REVERSE("right_reverse", "右电机反向", "", 0, 0, 1, address = 0x0070, words = 1, divisor = 1, group = MotorConfigurationGroup.RIGHT_MOTOR, choices = mapOf(0L to "无", 1L to "反向")),
    LEFT_POLE_PAIRS("left_pole_pairs", "左电机极对数", "", 0, 0, 65535, address = 0x0071, words = 1, divisor = 1, group = MotorConfigurationGroup.LEFT_MOTOR, note = "手册允许 0–65535；0 的实际含义未说明。"),
    LEFT_PHASE_1_2("left_phase_1_2", "左电机第 1/2 相相序", "", 0, 0, 1542, address = 0x0072, words = 1, divisor = 1, group = MotorConfigurationGroup.LEFT_MOTOR, note = "低字节为第 1 相，高字节为第 2 相；两个字节各为 0–6。", phasePair = true),
    LEFT_PHASE_3_4("left_phase_3_4", "左电机第 3/4 相相序", "", 0, 0, 1542, address = 0x0073, words = 1, divisor = 1, group = MotorConfigurationGroup.LEFT_MOTOR, note = "低字节为第 3 相，高字节为第 4 相；两个字节各为 0–6。", phasePair = true),
    LEFT_PHASE_5_6("left_phase_5_6", "左电机第 5/6 相相序", "", 0, 0, 1542, address = 0x0074, words = 1, divisor = 1, group = MotorConfigurationGroup.LEFT_MOTOR, note = "低字节为第 5 相，高字节为第 6 相；两个字节各为 0–6。", phasePair = true),
    LEFT_PHASE_LEARNING_STATE("left_phase_learning_state", "左电机相序学习状态", "", 0, 0, 1, address = 0x0075, words = 1, divisor = 1, group = MotorConfigurationGroup.LEFT_MOTOR, choices = mapOf(0L to "未学习", 1L to "已经学习"), note = "写入标志不会执行相序学习；学习操作位于测试控制。"),
    LEFT_PARKING_CURRENT("left_parking_current", "左电机电子驻车最大电流", "A", 2, 0, 6000, address = 0x0076, words = 1, divisor = 100, group = MotorConfigurationGroup.LEFT_MOTOR, note = "必须不超过最大输出电流。手册 0 值原文为“0：不停机锁相”，不据此宣称机械抱闸状态。"),
    LEFT_FOC_COMPENSATION_ANGLE("left_foc_compensation_angle", "左电机 FOC 相位补偿角", "°", 2, -18000, 18000, address = 0x0077, words = 1, divisor = 100, group = MotorConfigurationGroup.LEFT_MOTOR),
    LEFT_FOC_ADVANCE_ANGLE("left_foc_advance_angle", "左电机 FOC 超前角", "°", 2, -18000, 18000, address = 0x0078, words = 1, divisor = 100, group = MotorConfigurationGroup.LEFT_MOTOR),
    LEFT_REVERSE("left_reverse", "左电机反向", "", 0, 0, 1, address = 0x0079, words = 1, divisor = 1, group = MotorConfigurationGroup.LEFT_MOTOR, choices = mapOf(0L to "无", 1L to "反向")),
    OVERVOLTAGE_THRESHOLD("overvoltage_threshold", "过压保护电压", "V", 0, 8, 60, address = 0x0084, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM),
    UNDERVOLTAGE_THRESHOLD("undervoltage_threshold", "欠压保护电压", "V", 0, 8, 60, address = 0x0085, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM),
    VOLTAGE_PROTECTION_ENABLED("voltage_protection_enabled", "电压保护", "", 0, 0, 1, address = 0x0086, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM, choices = mapOf(0L to "不保护", 1L to "保护")),
    SYSTEM_MODE("system_mode", "系统模式", "", 0, 0, 4, address = 0x0087, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM, choices = mapOf(1L to "独立速度闭环", 3L to "同源速度闭环", 4L to "差速闭环"), note = "配置值 0/2 在本版手册中保留；状态寄存器可能报告这两个旧模式。"),
    FOC_CLOSED_LOOP_MODE("foc_closed_loop_mode", "FOC 闭环模式", "", 0, 0, 1, address = 0x0089, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM, choices = mapOf(0L to "单速度闭环", 1L to "速度电流双闭环")),
    DIFFERENTIAL_TURN_MAX_SPEED("differential_turn_max_speed", "差速转弯最大速度", "RPM", 0, 0, 65535, address = 0x008A, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM, note = "手册写 0–110000。手册上限超过单个 16 位寄存器；仅接受可编码的 0–65535，上限冲突仍需厂家确认。"),
    STALL_STOP_TIME("stall_stop_time", "堵转停机时间", "ms", 0, 0, 65535, address = 0x008B, words = 1, divisor = 1, group = MotorConfigurationGroup.SYSTEM, note = "手册写 0–66635。手册上限超过单个 16 位寄存器；仅接受可编码的 0–65535，上限冲突仍需厂家确认。"),
    PHASE_OVERCURRENT_THRESHOLD("phase_overcurrent_threshold", "相过流关闭电流", "A", 2, 100, 8000, address = 0x008C, words = 1, divisor = 100, group = MotorConfigurationGroup.SYSTEM),
    RIGHT_BRAKE_OUTPUT_TYPE("right_brake_output_type", "右电机抱闸输出类型", "", 0, 0, 2, address = 0x00B0, words = 1, divisor = 1, group = MotorConfigurationGroup.BRAKE, choices = mapOf(0L to "不输出", 1L to "停机自动刹车", 2L to "通讯控制")),
    RIGHT_STOP_BRAKE_ENABLED("right_stop_brake_enabled", "右电机停车刹车", "", 0, 0, 1, address = 0x00B1, words = 1, divisor = 1, group = MotorConfigurationGroup.BRAKE, choices = mapOf(0L to "不生效", 1L to "停止刹车生效")),
    LEFT_BRAKE_OUTPUT_TYPE("left_brake_output_type", "左电机抱闸输出类型", "", 0, 0, 2, address = 0x00B2, words = 1, divisor = 1, group = MotorConfigurationGroup.BRAKE, choices = mapOf(0L to "不输出", 1L to "停机自动刹车", 2L to "通讯控制")),
    LEFT_STOP_BRAKE_ENABLED("left_stop_brake_enabled", "左电机停车刹车", "", 0, 0, 1, address = 0x00B3, words = 1, divisor = 1, group = MotorConfigurationGroup.BRAKE, choices = mapOf(0L to "不生效", 1L to "停止刹车生效")),
    RS485_ADDRESS("rs485_address", "RS485 从站地址", "", 0, 0, 255, address = 0x00C8, words = 1, divisor = 1, group = MotorConfigurationGroup.RS485, note = "配置表为 0–255，帧地址说明为 1–254；0/255 写入后本工具不能据普通单播确认。修改会影响当前 RS485 链路，请单项保存并使用新参数重新连接。"),
    RS485_BAUD_RATE_CODE("rs485_baud_rate_code", "RS485 波特率", "", 0, 0, 4, address = 0x00C9, words = 1, divisor = 1, group = MotorConfigurationGroup.RS485, choices = mapOf(0L to "9600", 1L to "19200", 2L to "38400", 3L to "57600", 4L to "115200")),
    RS485_PARITY_CODE("rs485_parity_code", "RS485 校验及停止位", "", 0, 0, 3, address = 0x00CA, words = 1, divisor = 1, group = MotorConfigurationGroup.RS485, choices = mapOf(0L to "N1", 1L to "E1", 2L to "O1", 3L to "N2")),
    RS485_TIMEOUT_S("rs485_timeout_s", "RS485 通信中断制动时间", "s", 0, 0, 65535, address = 0x00CB, words = 1, divisor = 1, group = MotorConfigurationGroup.RS485, note = "手册单位为秒、上限 66635。手册上限超过单个 16 位寄存器；仅接受可编码的 0–65535，上限冲突仍需厂家确认。"),
    CAN_NODE_ID("can_node_id", "CAN 节点 ID", "", 0, 0, 127, address = 0x00CD, words = 1, divisor = 1, group = MotorConfigurationGroup.CAN),
    CAN_BAUD_RATE_CODE("can_baud_rate_code", "CAN 波特率", "", 0, 0, 13, address = 0x00CE, words = 1, divisor = 1, group = MotorConfigurationGroup.CAN, choices = mapOf(0L to "10 kbps", 1L to "20 kbps", 2L to "40 kbps", 3L to "50 kbps", 4L to "80 kbps", 5L to "100 kbps", 6L to "125 kbps", 7L to "200 kbps", 8L to "250 kbps", 9L to "400 kbps", 10L to "500 kbps", 11L to "600 kbps", 12L to "800 kbps", 13L to "1 Mbps")),
    CAN_TIMEOUT_S("can_timeout_s", "CAN 通信中断制动时间", "s", 0, 0, 65535, address = 0x00CF, words = 1, divisor = 1, group = MotorConfigurationGroup.CAN, note = "手册单位为秒、上限 66635。手册上限超过单个 16 位寄存器；仅接受可编码的 0–65535，上限冲突仍需厂家确认。"),
    TTL_ADDRESS("ttl_address", "TTL 从站地址", "", 0, 0, 255, address = 0x00D2, words = 1, divisor = 1, group = MotorConfigurationGroup.TTL, note = "配置表为 0–255，帧地址说明为 1–254；0/255 写入后本工具不能据普通单播确认。"),
    TTL_BAUD_RATE_CODE("ttl_baud_rate_code", "TTL 波特率", "", 0, 0, 4, address = 0x00D3, words = 1, divisor = 1, group = MotorConfigurationGroup.TTL, choices = mapOf(0L to "9600", 1L to "19200", 2L to "38400", 3L to "57600", 4L to "115200")),
    TTL_PARITY_CODE("ttl_parity_code", "TTL 校验及停止位", "", 0, 0, 3, address = 0x00D4, words = 1, divisor = 1, group = MotorConfigurationGroup.TTL, choices = mapOf(0L to "N1", 1L to "E1", 2L to "O1", 3L to "N2")),
    TTL_TIMEOUT_S("ttl_timeout_s", "TTL 通信中断制动时间", "s", 0, 0, 65535, address = 0x00D5, words = 1, divisor = 1, group = MotorConfigurationGroup.TTL, note = "手册单位为秒、上限 66635。手册上限超过单个 16 位寄存器；仅接受可编码的 0–65535，上限冲突仍需厂家确认。"),
    PID_SPEED_P("pid_speed_p", "速度环 P", "", 15, 0, 4294967295L, address = 0x00E0, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_SPEED, note = "手册建议原始值 1–327680，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_SPEED_I("pid_speed_i", "速度环 I", "", 15, 0, 4294967295L, address = 0x00E2, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_SPEED, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_SPEED_D("pid_speed_d", "速度环 D", "", 15, 0, 4294967295L, address = 0x00E4, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_SPEED, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_D_AXIS_P("pid_d_axis_p", "D 轴 P", "", 15, 0, 4294967295L, address = 0x00E6, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_D, note = "手册建议原始值 1–327680，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_D_AXIS_I("pid_d_axis_i", "D 轴 I", "", 15, 0, 4294967295L, address = 0x00E8, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_D, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_D_AXIS_D("pid_d_axis_d", "D 轴 D", "", 15, 0, 4294967295L, address = 0x00EA, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_D, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_Q_AXIS_P("pid_q_axis_p", "Q 轴 P", "", 15, 0, 4294967295L, address = 0x00EC, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_Q, note = "手册建议原始值 1–327680，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_Q_AXIS_I("pid_q_axis_i", "Q 轴 I", "", 15, 0, 4294967295L, address = 0x00EE, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_Q, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_Q_AXIS_D("pid_q_axis_d", "Q 轴 D", "", 15, 0, 4294967295L, address = 0x00F0, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_Q, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_SPEED_P("pid_parking_speed_p", "编码器驻车速度环 P", "", 15, 0, 4294967295L, address = 0x00F2, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_SPEED_I("pid_parking_speed_i", "编码器驻车速度环 I", "", 15, 0, 4294967295L, address = 0x00F4, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_SPEED_D("pid_parking_speed_d", "编码器驻车速度环 D", "", 15, 0, 4294967295L, address = 0x00F6, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_D_AXIS_P("pid_parking_d_axis_p", "编码器驻车 D 轴 P", "", 15, 0, 4294967295L, address = 0x00F8, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_D_AXIS_I("pid_parking_d_axis_i", "编码器驻车 D 轴 I", "", 15, 0, 4294967295L, address = 0x00FA, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_D_AXIS_D("pid_parking_d_axis_d", "编码器驻车 D 轴 D", "", 15, 0, 4294967295L, address = 0x00FC, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_Q_AXIS_P("pid_parking_q_axis_p", "编码器驻车 Q 轴 P", "", 15, 0, 4294967295L, address = 0x00FE, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_Q_AXIS_I("pid_parking_q_axis_i", "编码器驻车 Q 轴 I", "", 15, 0, 4294967295L, address = 0x0100, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_PARKING_Q_AXIS_D("pid_parking_q_axis_d", "编码器驻车 Q 轴 D", "", 15, 0, 4294967295L, address = 0x0102, words = 2, divisor = 32768, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。实际值 = 原始值 / 32768。", supportsTemporary = false),
    PID_HALL_PARKING_SPEED_P("pid_hall_parking_speed_p", "霍尔驻车速度环 P", "", 0, 0, 4294967295L, address = 0x0104, words = 2, divisor = 1, group = MotorConfigurationGroup.PID_PARKING, note = "手册建议原始值 1–326780，是建议而非硬限制。此项直接使用原始值，不除以 32768。", supportsTemporary = false);

    fun display(raw: Long): String = if (phasePair) "${raw and 255},${(raw ushr 8) and 255}"
        else BigDecimal.valueOf(raw).divide(BigDecimal.valueOf(divisor)).stripTrailingZeros().toPlainString()
    val rangeLabel: String get() = if (phasePair) "两个相序值各为 0–6，按低字节相位,高字节相位输入"
        else if (choices.isNotEmpty()) choices.entries.joinToString("；") { "${it.key}=${it.value}" }
        else "${display(minRaw)}–${display(maxRaw)} $unit".trim()
    val addressLabel: String get() = if (words == 1) "0x%04X".format(address)
        else "0x%04X–0x%04X".format(address, address + words - 1)
    fun accepts(raw: Long): Boolean = raw in minRaw..maxRaw &&
        (choices.isEmpty() || raw in choices) && (!phasePair || (raw and 255) <= 6 && ((raw ushr 8) and 255) <= 6)

    /** Exact decimal-to-raw conversion; no rounding, exponent notation or locale guessing. */
    fun parse(text: String): Long? {
        val value = text.trim()
        if (phasePair) {
            if (!value.matches(Regex("[0-6]\\s*,\\s*[0-6]"))) return null
            val phases = value.split(',').map { it.trim().toLong() }
            return phases[0] or (phases[1] shl 8)
        }
        if (value.length !in 1..32 || !value.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))) return null
        return try {
            BigDecimal(value).multiply(BigDecimal.valueOf(divisor)).setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact().takeIf(::accepts)
        } catch (_: ArithmeticException) { null }
          catch (_: NumberFormatException) { null }
    }
}

/** Values are a snapshot from a deliberate read, not live telemetry or proof of disabled outputs. */
data class MotorConfigurationDetail(val label: String, val value: String?, val address: Int? = null, val note: String? = null)

data class MotorConfigurationSnapshot(
    val snapshotId: String,
    val readAtMillis: Long,
    val values: Map<MotorConfigurationField, Long>,
    val deviceInfo: List<MotorConfigurationDetail> = emptyList(),
    val leftMotorInfo: List<MotorConfigurationDetail> = emptyList(),
    val rightMotorInfo: List<MotorConfigurationDetail> = emptyList(),
    val readOnlyInfo: List<MotorConfigurationDetail> = emptyList(),
    val writeAllowed: Boolean = false,
    val writeBlockedReason: String? = null,
    val leftParkingCurrentRaw: Long? = null,
    val rightParkingCurrentRaw: Long? = null,
    val connection: MotorSerialSettings = MotorSerialSettings(),
    val stopAllowed: Boolean = writeAllowed,
)

/** Codec owns register word order; these are complete numeric raw values. */
data class MotorConfigurationChange(val field: MotorConfigurationField, val expectedRaw: Long, val raw: Long)
data class MotorConfigurationWritePlan(val snapshotId: String, val changes: List<MotorConfigurationChange>,
    val writeMode: MotorConfigurationWriteMode = MotorConfigurationWriteMode.PERSISTENT)

enum class MotorConfigurationWriteOutcome {
    VERIFIED, ACKNOWLEDGED_UNVERIFIED, WRITE_OUTCOME_UNKNOWN, FAILED, NOT_ATTEMPTED,
}

data class MotorConfigurationWriteResult(
    val field: MotorConfigurationField,
    val requestedRaw: Long,
    val outcome: MotorConfigurationWriteOutcome,
    val observedRaw: Long? = null,
    val message: String? = null,
)

data class MotorConfigurationUiState(
    val snapshot: MotorConfigurationSnapshot? = null,
    val draftValues: Map<MotorConfigurationField, String> = emptyMap(),
    val reading: Boolean = false,
    val applying: Boolean = false,
    val requiresRead: Boolean = false,
    val writeResults: List<MotorConfigurationWriteResult> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val writeMode: MotorConfigurationWriteMode = MotorConfigurationWriteMode.PERSISTENT,
    val connection: MotorSerialSettings = MotorSerialSettings(),
    val actioning: Boolean = false,
    val actionResult: MotorActionResult? = null,
) {
    val busy: Boolean get() = reading || applying || actioning
}

data class MotorConfigurationDraftAnalysis(
    val changes: List<MotorConfigurationChange>,
    val errors: Map<MotorConfigurationField, String>,
)

fun analyzeMotorConfigurationDraft(state: MotorConfigurationUiState): MotorConfigurationDraftAnalysis {
    val changes = mutableListOf<MotorConfigurationChange>()
    val errors = linkedMapOf<MotorConfigurationField, String>()
    fun proposed(field: MotorConfigurationField, fallback: Long? = null): Long? =
        state.draftValues[field]?.let(field::parse) ?: state.snapshot?.values?.get(field) ?: fallback
    val proposedMaximum = proposed(MotorConfigurationField.MAX_OUTPUT_CURRENT)
    val proposedLeftParking = proposed(MotorConfigurationField.LEFT_PARKING_CURRENT, state.snapshot?.leftParkingCurrentRaw)
    val proposedRightParking = proposed(MotorConfigurationField.RIGHT_PARKING_CURRENT, state.snapshot?.rightParkingCurrentRaw)
    MotorConfigurationField.entries.forEach { field ->
        val text = state.draftValues[field] ?: return@forEach
        val original = state.snapshot?.values?.get(field)
        val raw = field.parse(text)
        when {
            original == null -> errors[field] = "尚未读取此项，请先读取配置"
            raw == null -> errors[field] = "请输入 ${field.rangeLabel}；数值必须能精确编码"
            state.writeMode == MotorConfigurationWriteMode.TEMPORARY && !field.supportsTemporary ->
                errors[field] = "此项仅支持 FC10 保存参数，请选择保存参数"
            field in setOf(MotorConfigurationField.LEFT_PARKING_CURRENT, MotorConfigurationField.RIGHT_PARKING_CURRENT) &&
                (proposedMaximum == null || raw > proposedMaximum) ->
                errors[field] = "驻车电流不能超过最大输出电流，请核对本次修改后的组合"
            field == MotorConfigurationField.MAX_OUTPUT_CURRENT && raw != original &&
                listOfNotNull(proposedLeftParking, proposedRightParking).any { it > raw } ->
                errors[field] = "最大输出电流不能低于当前任一侧的驻车电流"
            field == MotorConfigurationField.MAX_OUTPUT_CURRENT && raw < original &&
                (proposedLeftParking == null || proposedRightParking == null) ->
                errors[field] = "左右驻车电流尚未完整读取，暂不能降低最大输出电流"
            raw != original -> changes += MotorConfigurationChange(field, original, raw)
        }
    }
    val reconnectFields = setOf(MotorConfigurationField.RS485_ADDRESS,
        MotorConfigurationField.RS485_BAUD_RATE_CODE, MotorConfigurationField.RS485_PARITY_CODE)
    if (changes.size > 1) changes.filter { it.field in reconnectFields }.forEach {
        errors[it.field] = "更改当前 RS485 地址或串口格式会改变连接，请单独提交这一项并重新读取"
    }
    return MotorConfigurationDraftAnalysis(changes, errors)
}

/** Both the UI preview and the owner must reject stale, busy, empty or invalid write plans. */
fun motorConfigurationWritePlan(state: MotorConfigurationUiState): MotorConfigurationWritePlan? {
    val snapshot = state.snapshot ?: return null
    if (state.busy || state.requiresRead || !snapshot.writeAllowed || snapshot.snapshotId.isBlank() ||
        !state.connection.isValid || snapshot.connection != state.connection) return null
    val analysis = analyzeMotorConfigurationDraft(state)
    if (analysis.errors.isNotEmpty() || analysis.changes.isEmpty()) return null
    return MotorConfigurationWritePlan(snapshot.snapshotId, analysis.changes, state.writeMode)
}
