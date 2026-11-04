package com.example.followingcar

import java.io.IOException
import java.math.BigDecimal
import org.json.JSONObject
import org.json.JSONTokener

internal const val BOARD_PARKING_COMMAND =
    "sudo -n /usr/bin/python3 -B /home/topeet/lianzhan/scripts/app_parking_bridge.py --request"
internal const val PARKING_CURRENT_SEMANTICS = "current_zero_disables"

internal data class ParkingRequest(val op: String,
    val leftRaw: Int? = null, val rightRaw: Int? = null) {
    init {
        require(op in setOf("read", "currents"))
        require((op == "currents") == (leftRaw != null && rightRaw != null))
        require(op == "currents" || leftRaw == null && rightRaw == null)
        require(leftRaw == null || leftRaw in 0..6000)
        require(rightRaw == null || rightRaw in 0..6000)
    }
    fun addTo(json: JSONObject): JSONObject = json.put("op", op).apply {
        leftRaw?.let { put("leftRaw", it) }
        rightRaw?.let { put("rightRaw", it) }
    }
}

data class ParkingWriteResult(val side: String, val status: String, val requestedRaw: Int,
    val actualRaw: Int?)

data class ParkingReply(val ok: Boolean, val mode: Boolean?, val modeSource: String,
    val leftRaw: Int?, val rightRaw: Int?, val maxRaw: Int?,
    val leftRpm: Int?, val rightRpm: Int?, val leftError: Int?, val rightError: Int?,
    val writeMayHaveOccurred: Boolean, val errorCode: String?, val results: List<ParkingWriteResult>,
    val simulation: Boolean)

/** Preserve verified per-side writes even when a subsequent safety check must close control. */
internal class ParkingResultException(val result: ParkingReply, cause: Exception) :
    IOException("驻车操作后控制通道已停止，请核对各侧回读结果", cause)

internal fun parkingDraftRaw(text: String): Int? = runCatching {
    text.trim().takeIf { it.isNotEmpty() }?.let { BigDecimal(it).movePointRight(2).intValueExact() }
        ?.takeIf { it in 0..6000 }
}.getOrNull()

internal fun parkingRawText(raw: Int): String = BigDecimal(raw).movePointLeft(2).stripTrailingZeros().toPlainString()

/** Mixed sides are deliberately neither fully enabled nor fully disabled. */
internal fun parkingCurrentMode(left: Int?, right: Int?): Boolean? = when {
    left == null || right == null -> null
    left == 0 && right == 0 -> false
    left > 0 && right > 0 -> true
    else -> null
}

internal fun parkingRequestBytes(requestId: String, request: ParkingRequest): ByteArray {
    require(requestId.matches(Regex("[A-Za-z0-9-]{1,80}")))
    return (request.addTo(JSONObject().put("schemaVersion", 2).put("requestId", requestId)).toString() + "\n")
        .toByteArray(Charsets.UTF_8)
}

internal suspend fun BoardSshClient.parkingRequest(request: ParkingRequest,
    diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    simulation: Boolean = false): ParkingReply {
    val result = execute(BOARD_PARKING_COMMAND + if (simulation) " --simulate" else "",
        timeoutMillis = 15_000L, kind = "parking_${request.op}", diagnostic = diagnostic,
        input = parkingRequestBytes(diagnostic.operationId, request), outputLimitBytes = 16 * 1024)
    return parseParkingReply(result, diagnostic.operationId, request, simulation)
}

internal fun parseParkingReply(result: CommandResult, requestId: String, request: ParkingRequest,
    simulation: Boolean = false): ParkingReply {
    try {
        require(!result.truncated && result.exitCode >= 0 && result.stdout.length <= 16 * 1024)
        val tokens = JSONTokener(result.stdout)
        val json = tokens.nextValue() as? JSONObject ?: throw IOException("驻车响应格式无效")
        require(tokens.nextClean() == '\u0000')
        return parseParkingReply(json, requestId, request, simulation).also { require(!it.ok || result.exitCode == 0) }
    } catch (failure: Exception) {
        throw IOException("驻车响应校验失败，请重新读取；不会自动重试设置", failure)
    }
}

internal fun parseParkingReply(json: JSONObject, requestId: String, request: ParkingRequest,
    simulation: Boolean = false): ParkingReply {
    fun integer(value: Any?, min: Int, max: Int): Int {
        require(value is Number) { "驻车数值类型无效" }
        return BigDecimal(value.toString()).intValueExact().also { require(it in min..max) { "驻车数值范围无效" } }
    }
    fun optionalInt(name: String, min: Int, max: Int): Int? {
        require(json.has(name)) { "驻车响应字段缺失" }
        return json.get(name).takeUnless { it == JSONObject.NULL }?.let { integer(it, min, max) }
    }
    fun bool(name: String) = (json.get(name) as? Boolean) ?: throw IOException("驻车布尔字段无效")
    require(json.opt("type") == "parking" && integer(json.opt("schemaVersion"), 2, 2) == 2 &&
        json.opt("parkingSemantics") == PARKING_CURRENT_SEMANTICS) { "请更新开发板驻车电流协议" }
    require(json.opt("requestId") == requestId && json.opt("op") == request.op) { "驻车响应与请求不匹配" }
    val ok = bool("ok")
    require(bool("simulation") == simulation) { "驻车模拟模式不匹配" }
    require(json.has("mode"))
    val modeValue = json.get("mode")
    require(modeValue == JSONObject.NULL || modeValue is Boolean)
    val mode = modeValue as? Boolean
    val source = json.get("modeSource")
    require(source in setOf("unknown", "current_readback"))
    val left = optionalInt("leftRaw", 0, 6000)
    val right = optionalInt("rightRaw", 0, 6000)
    val maximum = optionalInt("maxRaw", 0, 6000)
    require(source == if (left != null && right != null) "current_readback" else "unknown") {
        "驻车电流状态来源不一致"
    }
    require(mode == parkingCurrentMode(left, right)) { "驻车状态与左右电流不一致" }
    val leftRpm = optionalInt("leftRpm", Int.MIN_VALUE, Int.MAX_VALUE)
    val rightRpm = optionalInt("rightRpm", Int.MIN_VALUE, Int.MAX_VALUE)
    val leftError = optionalInt("leftError", 0, 65535)
    val rightError = optionalInt("rightError", 0, 65535)
    if (ok) require(listOf(left, right, maximum, leftRpm, rightRpm, leftError, rightError).all { it != null })
    val mayWrite = bool("writeMayHaveOccurred")
    require(request.op != "read" || !mayWrite)
    require(json.has("errorCode"))
    val rawError = json.get("errorCode")
    require(rawError == JSONObject.NULL || rawError is String && rawError.matches(Regex("[a-z0-9_]{1,80}")))
    val error = rawError as? String
    require(!ok || error == null)
    val rows = json.getJSONArray("results")
    require(rows.length() in 0..2 && (request.op == "currents" || rows.length() == 0))
    val results = (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val side = row.get("side")
        val status = row.get("status")
        require(side in setOf("left", "right") && status in setOf("verified", "unknown", "not_attempted"))
        val wanted = integer(row.get("requestedRaw"), 0, 6000)
        require(wanted == if (side == "left") request.leftRaw else request.rightRaw)
        val actual = row.get("actualRaw").takeUnless { it == JSONObject.NULL }?.let { integer(it, 0, 6000) }
        if (status == "verified") require(actual == wanted)
        if (actual != null) require(actual == if (side == "left") left else right)
        ParkingWriteResult(side as String, status as String, wanted, actual)
    }
    require(results.map { it.side }.distinct().size == results.size)
    if (ok && request.op == "currents") require(results.size == 2 && results.all { it.status == "verified" } &&
        left == request.leftRaw && right == request.rightRaw)
    return ParkingReply(ok, mode, source as String, left, right, maximum, leftRpm, rightRpm,
        leftError, rightError, mayWrite, error, results, simulation)
}

internal fun parkingReplyError(reply: ParkingReply): String = when (reply.errorCode) {
    "control_busy", "busy", "lock_busy", "motor_busy" -> "其他程序正在控制电机，请结束占用后重试"
    "invalid_current", "current_out_of_range", "current_above_max", "current_exceeds_max", "current_limit_exceeded" -> "驻车电流超出当前最大输出电流，请调整输入"
    "invalid_parking_current", "parking_current_zero" -> "驻车电流为零或无效，请先保存有效电流"
    "communication_control_required", "control_source_not_communication", "external_control_source" -> "电机控制来源不是通讯控制，请核对配置"
    "motor_fault" -> "电机报告故障，请先核对故障信息"
    "invalid_current_limit", "current_limit_changed" -> "最大输出电流无效或已变化，请重新读取核对"
    "zero_target_unconfirmed", "motion_targets_active" -> "零转速目标尚未确认，请松手后重试"
    "mode_write_unconfirmed" -> "停止模式指令结果未确认，请重新读取核对；不会自动重试"
    "readback_mismatch" -> "电流回读与本次设置不一致，请核对各侧结果；不会自动重试"
    else -> if (reply.writeMayHaveOccurred) "设置结果未全部确认，请读取核对；不会自动重试"
        else "本次驻车操作未完成，请读取状态后重试"
}
