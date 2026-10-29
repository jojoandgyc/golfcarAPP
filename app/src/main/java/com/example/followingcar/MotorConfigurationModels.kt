package com.example.followingcar

import java.math.BigDecimal
import java.math.RoundingMode

/** The five persistent configuration fields verified against the board GUI and motor manual. */
enum class MotorConfigurationField(
    val id: String,
    val label: String,
    val unit: String,
    val decimalPlaces: Int,
    val minRaw: Long,
    val maxRaw: Long,
) {
    MAX_OUTPUT_CURRENT("max_output_current", "最大输出电流", "A", 2, 50, 6000),
    MAX_FORWARD_SPEED("max_forward_speed", "正转速度上限", "RPM", 0, 0, 110000),
    MAX_REVERSE_SPEED("max_reverse_speed", "反转速度上限", "RPM", 0, 0, 110000),
    CLOSED_LOOP_ACCELERATION("closed_loop_acceleration", "闭环加速度", "RPM/s", 0, 10, 65535),
    CLOSED_LOOP_DECELERATION("closed_loop_deceleration", "闭环减速度", "RPM/s", 0, 10, 65535);

    fun display(raw: Long): String = BigDecimal.valueOf(raw, decimalPlaces).stripTrailingZeros().toPlainString()
    val rangeLabel: String get() = "${display(minRaw)}–${display(maxRaw)} $unit"

    /** Exact decimal-to-raw conversion; no rounding, exponent notation or locale guessing. */
    fun parse(text: String): Long? {
        val value = text.trim()
        if (value.length !in 1..24 || !value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return null
        return try {
            BigDecimal(value).movePointRight(decimalPlaces).setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact().takeIf { it in minRaw..maxRaw }
        } catch (_: ArithmeticException) { null }
          catch (_: NumberFormatException) { null }
    }
}

/** Values are a snapshot from a deliberate read, not live telemetry or proof of disabled outputs. */
data class MotorConfigurationDetail(val label: String, val value: String?)

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
)

/** Codec owns register word order; these are complete numeric raw values. */
data class MotorConfigurationChange(val field: MotorConfigurationField, val expectedRaw: Long, val raw: Long)
data class MotorConfigurationWritePlan(val snapshotId: String, val changes: List<MotorConfigurationChange>)

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
) {
    val busy: Boolean get() = reading || applying
}

data class MotorConfigurationDraftAnalysis(
    val changes: List<MotorConfigurationChange>,
    val errors: Map<MotorConfigurationField, String>,
)

fun analyzeMotorConfigurationDraft(state: MotorConfigurationUiState): MotorConfigurationDraftAnalysis {
    val changes = mutableListOf<MotorConfigurationChange>()
    val errors = linkedMapOf<MotorConfigurationField, String>()
    MotorConfigurationField.entries.forEach { field ->
        val text = state.draftValues[field] ?: return@forEach
        val original = state.snapshot?.values?.get(field)
        val raw = field.parse(text)
        when {
            original == null -> errors[field] = "尚未读取此项，请先读取配置"
            original !in field.minRaw..field.maxRaw -> errors[field] = "原读值超出已确认范围，请核对驱动器版本"
            raw == null -> errors[field] = "请输入 ${field.rangeLabel}，最多 ${field.decimalPlaces} 位小数"
            field == MotorConfigurationField.MAX_OUTPUT_CURRENT && raw != original &&
                listOfNotNull(state.snapshot?.leftParkingCurrentRaw, state.snapshot?.rightParkingCurrentRaw).any { it > raw } ->
                errors[field] = "最大输出电流不能低于当前任一侧的驻车电流"
            field == MotorConfigurationField.MAX_OUTPUT_CURRENT && raw < original &&
                (state.snapshot?.leftParkingCurrentRaw == null || state.snapshot?.rightParkingCurrentRaw == null) ->
                errors[field] = "左右驻车电流尚未完整读取，暂不能降低最大输出电流"
            raw != original -> changes += MotorConfigurationChange(field, original, raw)
        }
    }
    return MotorConfigurationDraftAnalysis(changes, errors)
}

/** Both the UI preview and the owner must reject stale, busy, empty or invalid write plans. */
fun motorConfigurationWritePlan(state: MotorConfigurationUiState): MotorConfigurationWritePlan? {
    val snapshot = state.snapshot ?: return null
    if (state.busy || state.requiresRead || !snapshot.writeAllowed || snapshot.snapshotId.isBlank()) return null
    val analysis = analyzeMotorConfigurationDraft(state)
    if (analysis.errors.isNotEmpty() || analysis.changes.isEmpty()) return null
    return MotorConfigurationWritePlan(snapshot.snapshotId, analysis.changes)
}
