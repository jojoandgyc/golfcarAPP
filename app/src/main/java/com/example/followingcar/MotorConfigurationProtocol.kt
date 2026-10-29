package com.example.followingcar

import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal const val MOTOR_CONFIGURATION_COMMAND =
    "sudo -n /usr/bin/python3 -B /home/topeet/lianzhan/scripts/app_motor_config_bridge.py --request"

internal data class MotorConfigurationReply(
    val ok: Boolean, val snapshot: MotorConfigurationSnapshot?, val results: List<MotorConfigurationWriteResult>,
    val writeMayHaveOccurred: Boolean, val error: String?, val reconnectRequired: Boolean = false,
)

internal data class MotorActionReply(
    val ok: Boolean, val snapshot: MotorConfigurationSnapshot?, val actionResult: MotorActionResult?,
    val writeMayHaveOccurred: Boolean, val reconnectRequired: Boolean, val error: String?,
)

internal suspend fun BoardSshClient.motorConfigurationRequest(plan: MotorConfigurationWritePlan? = null,
    diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId), simulation: Boolean = false,
    connection: MotorSerialSettings = MotorSerialSettings()): MotorConfigurationReply {
    val result = execute(MOTOR_CONFIGURATION_COMMAND + if (simulation) " --simulate" else "",
        timeoutMillis = motorConfigurationTimeoutMillis(plan), kind = if (plan == null) "motor_config_read" else "motor_config_write",
        diagnostic = diagnostic, input = motorConfigurationRequest(diagnostic.operationId, plan, connection),
        outputLimitBytes = 128 * 1024)
    return parseMotorConfigurationReply(result, diagnostic.operationId, plan, simulation, connection)
}

/** More configuration items require more guarded serial transactions; control deadlines are separate. */
internal fun motorConfigurationTimeoutMillis(plan: MotorConfigurationWritePlan?): Long =
    (45_000L + (plan?.changes?.size ?: 0) * 6_000L).coerceAtMost(300_000L)

internal suspend fun BoardSshClient.motorActionRequest(plan: MotorActionPlan,
    diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId), simulation: Boolean = false,
    connection: MotorSerialSettings = MotorSerialSettings()): MotorActionReply {
    plan.validate()
    val result = execute(MOTOR_CONFIGURATION_COMMAND + if (simulation) " --simulate" else "",
        timeoutMillis = plan.durationMs.toLong() + 45_000L, kind = "motor_action", diagnostic = diagnostic,
        input = motorActionRequest(diagnostic.operationId, plan, connection), outputLimitBytes = 128 * 1024)
    return parseMotorActionReply(result, diagnostic.operationId, plan, simulation, connection)
}

private fun requestBase(requestId: String, op: String, connection: MotorSerialSettings): JSONObject {
    require(requestId.matches(Regex("[A-Za-z0-9-]{1,80}")))
    require(connection.isValid) { "串口连接参数无效" }
    return JSONObject().put("schemaVersion", 2).put("requestId", requestId).put("op", op)
        .put("connection", JSONObject().put("slave", connection.slave).put("baudRate", connection.baudRate)
            .put("parity", connection.parity).put("stopBits", connection.stopBits))
}

internal fun motorConfigurationRequest(requestId: String, plan: MotorConfigurationWritePlan? = null,
    connection: MotorSerialSettings = MotorSerialSettings()): ByteArray {
    val request = requestBase(requestId, if (plan == null) "snapshot" else "write", connection)
    if (plan != null) {
        require(plan.snapshotId.matches(Regex("[a-f0-9]{64}"))) { "配置快照无效，请重新读取" }
        require(plan.changes.size in 1..MotorConfigurationField.entries.size)
        require(plan.changes.map { it.field }.distinct().size == plan.changes.size)
        request.put("snapshotId", plan.snapshotId).put("writeMode", plan.writeMode.protocolValue)
            .put("changes", JSONArray().apply {
                plan.changes.forEach { change ->
                    require(change.field.accepts(change.raw))
                    require(plan.writeMode != MotorConfigurationWriteMode.TEMPORARY || change.field.supportsTemporary)
                    require(change.raw != change.expectedRaw)
                    put(JSONObject().put("id", change.field.id)
                        .put("expectedRaw", motorWords(change.field, change.expectedRaw))
                        .put("raw", motorWords(change.field, change.raw)))
                }
            })
    }
    return encodeRequest(request)
}

internal fun motorActionRequest(requestId: String, plan: MotorActionPlan,
    connection: MotorSerialSettings = MotorSerialSettings()): ByteArray {
    plan.validate()
    return encodeRequest(requestBase(requestId, "action", connection)
        .put("snapshotId", plan.snapshotId).put("actionId", plan.action.id)
        .put("value", plan.value).put("durationMs", plan.durationMs).put("confirmed", true))
}

private fun encodeRequest(request: JSONObject): ByteArray = (request.toString() + "\n").toByteArray(Charsets.UTF_8).also {
    require(it.size <= 8192) { "请求过大，请减少本次修改项" }
}

internal fun motorWords(field: MotorConfigurationField, raw: Long): JSONArray {
    val bits = field.words * 16
    val minimum = if (field.signed) -(1L shl (bits - 1)) else 0L
    val maximum = if (field.signed) (1L shl (bits - 1)) - 1 else (1L shl bits) - 1
    require(raw in minimum..maximum) { "寄存器数值无法完整编码" }
    return JSONArray().apply { (field.words - 1 downTo 0).forEach { put(((raw ushr (it * 16)) and 65535).toInt()) } }
}

private fun wordsValue(array: JSONArray, count: Int, signed: Boolean = false): Long {
    require(count in 1..2 && array.length() == count) { "配置寄存器长度不匹配" }
    var result = 0L
    repeat(count) { result = (result shl 16) or exactInteger(array.get(it), 0, 65535) }
    if (signed && result and (1L shl (count * 16 - 1)) != 0L) result -= 1L shl (count * 16)
    return result
}

private fun exactInteger(value: Any?, min: Long, max: Long): Long {
    require(value is Number) { "配置数值类型无效" }
    val integer = BigDecimal(value.toString()).longValueExact()
    require(integer in min..max) { "配置数值超出协议范围" }
    return integer
}

private fun JSONObject.strictBoolean(name: String): Boolean =
    (get(name) as? Boolean) ?: throw IOException("配置响应缺少有效的 $name")

private fun JSONObject.shortString(name: String, maxLength: Int = 160): String {
    val value = get(name)
    require(value is String && value.length <= maxLength) { "配置文本字段无效" }
    return value
}

private fun parseConnection(json: JSONObject): MotorSerialSettings {
    val parity = json.shortString("parity", 1)
    val stopBits = exactInteger(json.get("stopBits"), 1, 2).toInt()
    val code = when (parity to stopBits) {
        "N" to 1 -> 0; "E" to 1 -> 1; "O" to 1 -> 2; "N" to 2 -> 3
        else -> throw IOException("串口校验格式不受支持")
    }
    return MotorSerialSettings(exactInteger(json.get("slave"), 1, 254).toInt(),
        exactInteger(json.get("baudRate"), 9600, 115200).toInt(), code).also { require(it.isValid) }
}

private fun envelope(result: CommandResult, requestId: String, op: String, simulation: Boolean,
    connection: MotorSerialSettings): JSONObject {
    require(!result.truncated && result.stdout.isNotBlank()) { "电机接口响应不完整" }
    val tokener = JSONTokener(result.stdout.trim())
    val root = tokener.nextValue() as? JSONObject ?: throw IOException("电机接口响应不是对象")
    require(tokener.nextClean() == '\u0000') { "电机接口响应包含额外输出" }
    require(root.shortString("type") == "motor_config")
    require(exactInteger(root.get("schemaVersion"), 2, 2) == 2L) { "请更新板端电机接口到版本 2" }
    require(root.shortString("requestId") == requestId && root.shortString("op") == op) { "电机接口响应编号不一致" }
    require(root.strictBoolean("simulation") == simulation) { "真实与模拟响应不一致" }
    require(parseConnection(root.getJSONObject("connection")) == connection) { "串口连接参数与响应不一致" }
    require(root.has("snapshot") && root.has("error") && root.has("actionResult"))
    require(!root.strictBoolean("ok") || result.exitCode == 0) { "进程退出状态与回执不一致" }
    return root
}

private fun responseError(root: JSONObject): String? = if (root.isNull("error")) null
    else motorConfigurationError(root.getJSONObject("error").shortString("code", 80))

internal fun motorConfigurationError(code: String): String = when (code) {
    "control_busy", "control_unavailable", "control_not_idle", "lock_busy", "serial_busy",
    "control_busy_or_unconfirmed", "control_process_running", "control_lock_busy_or_unavailable", "motor_serial_in_use" -> "控制程序或串口正在使用中，请先结束手动和自动跟随"
    "process_check_unavailable", "control_inspection_failed", "control_observation_unavailable", "motor_serial_check_failed" -> "无法确认板端控制占用，保持锁定，请检查后再读取"
    "root_required_for_complete_idle_probe" -> "电机配置桥接程序需要板端非交互 sudo 权限"
    "not_stationary", "write_gate_closed", "not_stopped", "nonzero_speed", "nonzero_pwm", "motor_motion_observed", "motor_pwm_active" -> "未确认左右电机速度和输出均为零，暂不能保存"
    "not_communication_control", "control_source_not_communication", "external_control_source" -> "驱动器当前不是通讯控制模式，仅允许查看"
    "snapshot_mismatch", "snapshot_stale", "stale_snapshot", "expected_value_mismatch", "snapshot_changed_refresh_required", "expected_value_changed" -> "配置已变化，请重新读取后再修改"
    "value_out_of_range", "invalid_value", "invalid_request", "invalid_change", "unsupported_field" -> "参数或请求不在允许范围内"
    "current_limit_exceeds_device", "current_below_parking", "parking_current_exceeds_limit", "device_current_limit_exceeded", "current_below_parking_current" -> "电流上限与驱动器额定值或驻车电流不匹配，请核对当前配置"
    "serial_timeout", "read_timeout", "timeout", "serial_read_timeout", "serial_write_timeout" -> "串口响应超时，请检查驱动器和接线后重新读取"
    "readback_mismatch", "readback_failed" -> "写入后的回读未确认，请重新读取；不要直接重复保存"
    "crc_mismatch", "response_mismatch", "response_length_mismatch", "write_ack_mismatch" -> "驱动器回执校验失败，请检查串口链路并重新读取"
    "serial_write_failed", "serial_session_unusable" -> "串口通讯已中断，请重新读取；写入结果以逐项状态为准"
    "write_outcome_unknown" -> "写入已发送但结果未确认，请重新读取；不要直接重复保存"
    "communication_reconnect_required", "factory_reset_reconnect_required" -> "命令已发送，通讯配置可能已改变；请核对串口连接参数后重新读取"
    "action_not_confirmed" -> "此动作需要明确确认后执行"
    "action_cleanup_failed" -> "结束动作的停止或取消回执未确认，请检查现场状态"
    "unsupported_write_mode" -> "此参数不支持当前写入方式，请使用完整的 FC10 参数保存"
    else -> if (code.matches(Regex("device_exception_[0-9]+"))) {
        val value = code.substringAfterLast('_').toIntOrNull()
        "驱动器异常码 $value：" + (mapOf(1 to "非法功能码", 2 to "非法寄存器地址", 3 to "非法数据值",
            4 to "从站设备故障", 5 to "请求已确认，仍需处理时间", 6 to "设备忙", 8 to "存储奇偶性差错",
            10 to "网关不可用", 11 to "网关目标设备未响应")[value] ?: "未定义异常，请核对固件")
    }
        else "电机配置操作未完成（${code.take(60).replace(Regex("[^a-zA-Z0-9_-]"), "_")}）"
}

/** Correlation, register widths and exact readback are checked before success reaches the UI. */
internal fun parseMotorConfigurationReply(result: CommandResult, requestId: String,
    plan: MotorConfigurationWritePlan? = null, simulation: Boolean = false,
    connection: MotorSerialSettings = MotorSerialSettings()): MotorConfigurationReply = try {
    val root = envelope(result, requestId, if (plan == null) "snapshot" else "write", simulation, connection)
    require(root.isNull("actionResult"))
    val ok = root.strictBoolean("ok")
    val mayHaveWritten = root.strictBoolean("writeMayHaveOccurred")
    val reconnect = root.strictBoolean("reconnectRequired")
    require(plan != null || !mayHaveWritten && !reconnect)
    val error = responseError(root)
    require(!ok || error == null && !reconnect)
    val snapshot = if (root.isNull("snapshot")) null else parseMotorSnapshot(root.getJSONObject("snapshot"), connection)
    val results = root.getJSONArray("results")
    require(results.length() <= MotorConfigurationField.entries.size)
    val parsed = (0 until results.length()).map { index ->
        val entry = results.getJSONObject(index)
        val field = MotorConfigurationField.entries.single { it.id == entry.shortString("id") }
        val change = requireNotNull(plan?.changes?.singleOrNull { it.field == field })
        val requested = wordsValue(entry.getJSONArray("requestedRaw"), field.words, field.signed)
        require(requested == change.raw)
        val actual = if (entry.isNull("actualRaw")) null else wordsValue(entry.getJSONArray("actualRaw"), field.words, field.signed)
        val outcome = when (entry.shortString("status")) {
            "verified" -> MotorConfigurationWriteOutcome.VERIFIED
            "acknowledged_unverified" -> MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED
            "write_outcome_unknown" -> MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN
            "failed" -> MotorConfigurationWriteOutcome.FAILED
            "not_attempted" -> MotorConfigurationWriteOutcome.NOT_ATTEMPTED
            else -> throw IOException("未知的配置写入状态")
        }
        require(outcome != MotorConfigurationWriteOutcome.VERIFIED || actual == requested)
        MotorConfigurationWriteResult(field, requested, outcome, actual,
            if (entry.isNull("error")) null else motorConfigurationError(entry.shortString("error", 80)))
    }
    require(parsed.map { it.field }.distinct().size == parsed.size)
    require(mayHaveWritten || parsed.none { it.outcome in setOf(MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED,
        MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN) })
    if (ok) {
        require(snapshot != null)
        if (plan != null) {
            require(parsed.size == plan.changes.size && parsed.all { it.outcome == MotorConfigurationWriteOutcome.VERIFIED })
            require(parsed.all { snapshot.values[it.field] == it.requestedRaw })
        } else require(parsed.isEmpty())
    }
    if (reconnect) require(snapshot == null && !ok && mayHaveWritten)
    MotorConfigurationReply(ok, snapshot, parsed, mayHaveWritten, error, reconnect)
} catch (failure: Exception) {
    throw IOException("电机配置响应校验失败，请确认板端已更新并重新读取；不能确认参数状态", protocolDiagnosticFailure(failure))
}

internal fun parseMotorActionReply(result: CommandResult, requestId: String, plan: MotorActionPlan,
    simulation: Boolean = false, connection: MotorSerialSettings = MotorSerialSettings()): MotorActionReply = try {
    val root = envelope(result, requestId, "action", simulation, connection)
    require(root.getJSONArray("results").length() == 0)
    val ok = root.strictBoolean("ok")
    val mayHaveWritten = root.strictBoolean("writeMayHaveOccurred")
    val reconnect = root.strictBoolean("reconnectRequired")
    val error = responseError(root)
    val snapshot = if (root.isNull("snapshot")) null else parseMotorSnapshot(root.getJSONObject("snapshot"), connection)
    val action = if (root.isNull("actionResult")) null else root.getJSONObject("actionResult").let { entry ->
        require(entry.shortString("actionId") == plan.action.id)
        val value = exactInteger(entry.get("value"), plan.action.minRaw, plan.action.maxRaw)
        val duration = exactInteger(entry.get("durationMs"), 0, 60_000).toInt()
        require(value == plan.value && duration == plan.durationMs)
        val outcome = when (entry.shortString("outcome")) {
            "acknowledged" -> MotorActionOutcome.ACKNOWLEDGED
            "write_outcome_unknown" -> MotorActionOutcome.WRITE_OUTCOME_UNKNOWN
            "not_attempted" -> MotorActionOutcome.NOT_ATTEMPTED
            else -> throw IOException("未知的电机动作状态")
        }
        val cleanup = if (entry.isNull("cleanupConfirmed")) null else entry.strictBoolean("cleanupConfirmed")
        require(entry.strictBoolean("reconnectRequired") == reconnect)
        if (outcome != MotorActionOutcome.NOT_ATTEMPTED) require(mayHaveWritten)
        if (ok && plan.action.durationRange(value) != 0..0) require(cleanup == true)
        MotorActionResult(plan.action, value, duration, outcome, cleanup, reconnect,
            if (entry.isNull("error")) null else motorConfigurationError(entry.shortString("error", 80)))
    }
    // A reset can acknowledge the command while requiring a new connection;
    // that ACK is not readback or proof that all defaults have taken effect.
    if (ok) require(error == null && action?.outcome == MotorActionOutcome.ACKNOWLEDGED)
    if (reconnect) require(snapshot == null && mayHaveWritten)
    MotorActionReply(ok, snapshot, action, mayHaveWritten, reconnect, error)
} catch (failure: Exception) {
    throw IOException("电机动作回执校验失败，执行结果未确认，请重新读取；不要直接重复执行", protocolDiagnosticFailure(failure))
}

private fun parseMotorSnapshot(json: JSONObject, connection: MotorSerialSettings): MotorConfigurationSnapshot {
    val snapshotId = json.shortString("snapshotId")
    require(snapshotId.matches(Regex("[a-f0-9]{64}")))
    val readAt = Instant.parse(json.shortString("capturedAt")).toEpochMilli()
    require(parseConnection(json.getJSONObject("connection")) == connection)
    val fields = json.getJSONArray("fields")
    require(fields.length() == MotorConfigurationField.entries.size)
    val values = linkedMapOf<MotorConfigurationField, Long>()
    repeat(fields.length()) { index ->
        val entry = fields.getJSONObject(index)
        val known = MotorConfigurationField.entries.single { it.id == entry.shortString("id", 80) }
        require(known !in values && entry.strictBoolean("writable"))
        require(exactInteger(entry.get("address"), 0, 65535) == known.address.toLong())
        require(exactInteger(entry.get("words"), 1, 2) == known.words.toLong())
        require(entry.strictBoolean("signed") == known.signed)
        require(exactInteger(entry.get("divisor"), 1, 32768) == known.divisor)
        require(entry.strictBoolean("supportsTemporary") == known.supportsTemporary)
        require(exactInteger(entry.get("minRaw"), -0x80000000L, 0xffffffffL) == known.minRaw)
        require(exactInteger(entry.get("maxRaw"), -0x80000000L, 0xffffffffL) == known.maxRaw)
        values[known] = wordsValue(entry.getJSONArray("raw"), known.words, known.signed)
    }
    val device = json.getJSONObject("device")
    val observed = json.getJSONObject("observation")
    val gate = json.getJSONObject("gate")
    val writeAllowed = gate.strictBoolean("writeAllowed")
    val stopAllowed = gate.strictBoolean("controlAvailable")
    if (writeAllowed) require(stopAllowed && gate.strictBoolean("communicationControl") && gate.strictBoolean("zeroSpeedObserved") &&
        gate.strictBoolean("zeroPwmObserved") && exactInteger(gate.get("samples"), 2, 100) >= 2)
    fun number(source: JSONObject, name: String, min: Long, max: Long, divisor: Long = 1, unit: String = ""): String =
        (BigDecimal.valueOf(exactInteger(source.get(name), min, max)).divide(BigDecimal.valueOf(divisor))
            .stripTrailingZeros().toPlainString() + " $unit").trim()
    fun detail(label: String, key: String, address: Int, min: Long, max: Long, divisor: Long = 1,
        unit: String = "", note: String? = null) = MotorConfigurationDetail(label,
            number(observed, key, min, max, divisor, unit), address, note)
    fun motor(side: String, start: Int): List<MotorConfigurationDetail> = listOf(
        detail("相电流", "${side}CurrentRaw", start, 0, 65535, 100, "A"),
        detail("速度", "${side}SpeedRpm", start + 1, -0x80000000L, 0x7fffffffL, unit = "RPM"),
        detail("故障码", "${side}Error", start + 3, 0, 65535),
        detail("PWM", "${side}PwmRaw", start + 4, 0, 65535, 10, "%",
            "按手册乘 0.1%；文档上限 10000 对应 1000%，保留原始倍率并需核对固件。"),
        detail("温度", "${side}TemperatureC", start + 5, -32768, 32767, unit = "℃"),
        detail("位移", "${side}DisplacementDegrees", start + 7, -0x80000000L, 0x7fffffffL, unit = "°"),
        detail("反向状态", "${side}Reverse", start + 9, 0, 65535),
    )
    return MotorConfigurationSnapshot(snapshotId, readAt, values,
        deviceInfo = listOf(
            MotorConfigurationDetail("设备标识", number(device, "deviceId", 0, 65535), 0),
            MotorConfigurationDetail("驱动器版本", device.shortString("version", 40), 1),
            MotorConfigurationDetail("额定最大电流", number(device, "maxCurrentRaw", 0, 65535, 100, "A"), 2),
            MotorConfigurationDetail("通讯", connection.label),
            detail("电源电压", "supplyVoltageV", 0x1c, 0, 65535, unit = "V"),
            detail("实时系统模式", "runtimeSystemMode", 0x1d, 0, 65535),
            detail("控制模式", "controlMode", 0x1e, 0, 65535),
            detail("IN1 电平", "in1", 0x1f, 0, 65535),
            detail("IN2 电平", "in2", 0x20, 0, 65535)),
        leftMotorInfo = motor("left", 0x12), rightMotorInfo = motor("right", 8),
        writeAllowed = writeAllowed,
        writeBlockedReason = if (writeAllowed) null else if (gate.isNull("reason")) "板端尚未确认可写状态，请重新读取"
            else motorConfigurationError(gate.shortString("reason", 80)),
        leftParkingCurrentRaw = values[MotorConfigurationField.LEFT_PARKING_CURRENT],
        rightParkingCurrentRaw = values[MotorConfigurationField.RIGHT_PARKING_CURRENT], connection = connection,
        stopAllowed = stopAllowed)
}
