package com.example.followingcar

import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal const val MOTOR_CONFIGURATION_COMMAND =
    "sudo -n /usr/bin/python3 -B /home/topeet/lianzhan/scripts/app_motor_config_bridge.py --request"

internal data class MotorConfigurationReply(
    val ok: Boolean,
    val snapshot: MotorConfigurationSnapshot?,
    val results: List<MotorConfigurationWriteResult>,
    val writeMayHaveOccurred: Boolean,
    val error: String?,
)

internal suspend fun BoardSshClient.motorConfigurationRequest(plan: MotorConfigurationWritePlan? = null,
    diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId), simulation: Boolean = false): MotorConfigurationReply {
    val result = execute(MOTOR_CONFIGURATION_COMMAND + if (simulation) " --simulate" else "",
        timeoutMillis = 45_000, kind = if (plan == null) "motor_config_read" else "motor_config_write",
        diagnostic = diagnostic, input = motorConfigurationRequest(diagnostic.operationId, plan))
    return parseMotorConfigurationReply(result, diagnostic.operationId, plan, simulation)
}

internal fun motorConfigurationRequest(requestId: String, plan: MotorConfigurationWritePlan? = null): ByteArray {
    require(requestId.matches(Regex("[A-Za-z0-9-]{1,80}")))
    val request = JSONObject().put("schemaVersion", 1).put("requestId", requestId)
        .put("op", if (plan == null) "snapshot" else "write")
    if (plan != null) {
        require(plan.snapshotId.matches(Regex("[a-f0-9]{64}"))) { "配置快照无效，请重新读取" }
        require(plan.changes.size in 1..MotorConfigurationField.entries.size)
        require(plan.changes.map { it.field }.distinct().size == plan.changes.size)
        request.put("snapshotId", plan.snapshotId).put("changes", JSONArray().apply {
            plan.changes.forEach { change ->
                require(change.raw in change.field.minRaw..change.field.maxRaw)
                require(change.expectedRaw in change.field.minRaw..change.field.maxRaw)
                require(change.raw != change.expectedRaw)
                put(JSONObject().put("id", change.field.id)
                    .put("expectedRaw", motorWords(change.field, change.expectedRaw))
                    .put("raw", motorWords(change.field, change.raw)))
            }
        })
    }
    return (request.toString() + "\n").toByteArray(Charsets.UTF_8)
}

private fun motorWords(field: MotorConfigurationField, raw: Long): JSONArray = JSONArray().apply {
    if (field.maxRaw > 65535) put((raw ushr 16).toInt())
    put((raw and 65535).toInt())
}

private fun wordsValue(array: JSONArray, count: Int): Long {
    require(array.length() == count) { "配置寄存器长度不匹配" }
    var result = 0L
    repeat(count) { result = (result shl 16) or exactInteger(array.get(it), 0, 65535) }
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
    else -> if (code.matches(Regex("device_exception_[0-9]+"))) "驱动器返回异常码 ${code.substringAfterLast('_')}，请核对驱动器状态"
        else "电机配置操作未完成（${code.take(60).replace(Regex("[^a-zA-Z0-9_-]"), "_")}）"
}

/** Responses are correlated and validated before exposing either current values or write success. */
internal fun parseMotorConfigurationReply(result: CommandResult, requestId: String,
    plan: MotorConfigurationWritePlan? = null, simulation: Boolean = false): MotorConfigurationReply {
    if (result.truncated) throw IOException("电机配置响应不完整，请重新读取")
    if (result.stdout.isBlank()) {
        throw IOException("无法运行电机配置桥接程序（退出码 ${result.exitCode}），请检查板端部署")
    }
    try {
        val root = JSONObject(result.stdout.trim())
        require(root.has("snapshot") && root.has("error")) { "配置响应字段不完整" }
        require(root.shortString("type") == "motor_config" && exactInteger(root.get("schemaVersion"), 1, 1) == 1L)
        require(root.shortString("requestId") == requestId) { "电机配置响应编号不一致" }
        require(root.shortString("op") == if (plan == null) "snapshot" else "write")
        require(root.strictBoolean("simulation") == simulation) { "真实与模拟响应不一致" }
        val ok = root.strictBoolean("ok")
        require(!ok || result.exitCode == 0) { "退出状态与配置回执不一致" }
        val mayHaveWritten = root.strictBoolean("writeMayHaveOccurred")
        require(plan != null || !mayHaveWritten)
        val error = if (root.isNull("error")) null else motorConfigurationError(root.getJSONObject("error").shortString("code", 80))
        require(!ok || error == null)
        val snapshot = if (root.isNull("snapshot")) null else parseMotorSnapshot(root.getJSONObject("snapshot"))
        val results = root.getJSONArray("results")
        require(results.length() <= MotorConfigurationField.entries.size)
        val parsed = (0 until results.length()).map { index ->
            val entry = results.getJSONObject(index)
            val field = MotorConfigurationField.entries.single { it.id == entry.shortString("id") }
            val change = requireNotNull(plan?.changes?.singleOrNull { it.field == field })
            val words = if (field.maxRaw > 65535) 2 else 1
            val requested = wordsValue(entry.getJSONArray("requestedRaw"), words)
            require(requested == change.raw)
            val actual = if (entry.isNull("actualRaw")) null else wordsValue(entry.getJSONArray("actualRaw"), words)
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
        require(mayHaveWritten || parsed.none { it.outcome in setOf(MotorConfigurationWriteOutcome.VERIFIED,
            MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED, MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN) })
        if (ok) {
            require(snapshot != null)
            if (plan != null) {
                require(parsed.size == plan.changes.size && parsed.all { it.outcome == MotorConfigurationWriteOutcome.VERIFIED })
                require(parsed.all { snapshot.values[it.field] == it.requestedRaw })
            } else require(parsed.isEmpty())
        }
        return MotorConfigurationReply(ok, snapshot, parsed, mayHaveWritten, error)
    } catch (failure: Exception) {
        throw IOException("电机配置响应校验失败，不能确认参数状态，请重新读取", protocolDiagnosticFailure(failure))
    }
}

private fun parseMotorSnapshot(json: JSONObject): MotorConfigurationSnapshot {
    val snapshotId = json.shortString("snapshotId")
    require(snapshotId.matches(Regex("[a-f0-9]{64}")))
    val readAt = Instant.parse(json.shortString("capturedAt")).toEpochMilli()
    val fields = json.getJSONArray("fields")
    require(fields.length() in 5..120)
    val values = linkedMapOf<MotorConfigurationField, Long>()
    val details = mutableListOf<MotorConfigurationDetail>()
    var leftParking: Long? = null
    var rightParking: Long? = null
    val ids = mutableSetOf<String>()
    repeat(fields.length()) { index ->
        val entry = fields.getJSONObject(index)
        val id = entry.shortString("id", 80)
        require(ids.add(id))
        val known = MotorConfigurationField.entries.singleOrNull { it.id == id }
        if (known != null) {
            require(entry.strictBoolean("writable"))
            val expectedWords = if (known.maxRaw > 65535) 2 else 1
            require(exactInteger(entry.get("words"), 1, 2) == expectedWords.toLong())
            require(exactInteger(entry.get("minRaw"), 0, 0xffffffffL) == known.minRaw)
            require(exactInteger(entry.get("maxRaw"), 0, 0xffffffffL) == known.maxRaw)
            values[known] = wordsValue(entry.getJSONArray("raw"), expectedWords)
        } else {
            require(!entry.strictBoolean("writable")) { "配置写权限版本不匹配" }
            val raw = entry.getJSONArray("raw")
            require(raw.length() in 1..3)
            if (raw.length() == 3) require(id in setOf("left_phase_sequence", "right_phase_sequence"))
            val rawNumber = wordsValue(raw, raw.length())
            if (id == "left_parking_current") leftParking = rawNumber
            if (id == "right_parking_current") rightParking = rawNumber
            val display = if (raw.length() == 3) (0 until 3).joinToString(" · ") { "0x%04X".format(raw.getInt(it)) }
                else if (entry.isNull("value")) rawNumber.toString() else {
                val numeric = entry.get("value")
                require(numeric is Number && numeric.toDouble().isFinite())
                BigDecimal(numeric.toString()).stripTrailingZeros().toPlainString()
            }
            details += MotorConfigurationDetail(entry.shortString("label", 80),
                "$display ${entry.shortString("unit", 24)}".trim())
        }
    }
    require(values.size == MotorConfigurationField.entries.size)
    val device = json.getJSONObject("device")
    val observed = json.getJSONObject("observation")
    val gate = json.getJSONObject("gate")
    val writeAllowed = gate.strictBoolean("writeAllowed")
    if (writeAllowed) require(gate.strictBoolean("controlAvailable") && gate.strictBoolean("zeroSpeedObserved") &&
        gate.strictBoolean("zeroPwmObserved") && gate.strictBoolean("communicationControl") &&
        exactInteger(gate.get("samples"), 2, 100) >= 2)
    fun number(source: JSONObject, name: String, unit: String = ""): String {
        val numeric = source.get(name)
        require(numeric is Number && numeric.toDouble().isFinite())
        return "${BigDecimal(numeric.toString()).stripTrailingZeros().toPlainString()} $unit".trim()
    }
    return MotorConfigurationSnapshot(snapshotId, readAt, values,
        deviceInfo = listOf(
            MotorConfigurationDetail("设备标识", number(device, "deviceId")),
            MotorConfigurationDetail("驱动器版本", device.shortString("version", 40)),
            MotorConfigurationDetail("额定最大电流", number(device, "maxCurrentA", "A")),
            MotorConfigurationDetail("通讯", "${json.shortString("port", 60)} · ${number(json, "baudRate")} baud · 从站 ${number(json, "slave")}"),
            MotorConfigurationDetail("电源电压", number(observed, "supplyVoltageV", "V")),
            MotorConfigurationDetail("控制模式", number(observed, "controlMode")),
            MotorConfigurationDetail("实时系统模式", number(observed, "runtimeSystemMode"))),
        leftMotorInfo = listOf(MotorConfigurationDetail("速度", number(observed, "leftSpeedRpm", "RPM")),
            MotorConfigurationDetail("PWM 原值", number(observed, "leftPwmRaw")),
            MotorConfigurationDetail("故障码", number(observed, "leftError"))),
        rightMotorInfo = listOf(MotorConfigurationDetail("速度", number(observed, "rightSpeedRpm", "RPM")),
            MotorConfigurationDetail("PWM 原值", number(observed, "rightPwmRaw")),
            MotorConfigurationDetail("故障码", number(observed, "rightError"))),
        readOnlyInfo = details,
        writeAllowed = writeAllowed,
        writeBlockedReason = if (writeAllowed) null else if (gate.isNull("reason")) "板端尚未确认可写状态，请停车后重新读取"
            else motorConfigurationError(gate.shortString("reason", 80)),
        leftParkingCurrentRaw = leftParking, rightParkingCurrentRaw = rightParking)
}
