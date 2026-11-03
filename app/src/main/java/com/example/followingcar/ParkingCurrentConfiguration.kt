package com.example.followingcar

private val parkingCurrentFields = setOf(MotorConfigurationField.LEFT_PARKING_CURRENT,
    MotorConfigurationField.RIGHT_PARKING_CURRENT)

/** A separate editor: unrelated motor-page drafts and its temporary-write mode are never used. */
private fun parkingCurrentDraft(state: MotorConfigurationUiState, leftText: String,
    rightText: String): MotorConfigurationUiState = state.copy(
    draftValues = mapOf(MotorConfigurationField.LEFT_PARKING_CURRENT to leftText,
        MotorConfigurationField.RIGHT_PARKING_CURRENT to rightText),
    writeMode = MotorConfigurationWriteMode.PERSISTENT,
)

fun parkingCurrentDraftAnalysis(state: MotorConfigurationUiState, leftText: String,
    rightText: String): MotorConfigurationDraftAnalysis {
    val analysis = analyzeMotorConfigurationDraft(parkingCurrentDraft(state, leftText, rightText))
    val maximum = state.snapshot?.values?.get(MotorConfigurationField.MAX_OUTPUT_CURRENT)
    if (maximum != null && MotorConfigurationField.MAX_OUTPUT_CURRENT.accepts(maximum)) return analysis
    return analysis.copy(errors = analysis.errors + parkingCurrentFields.associateWith {
        "尚无有效的最大输出电流读值，请先重新读取配置"
    })
}

/** Creates FC10 writes for changed parking-current registers only, with the original read baseline. */
fun parkingCurrentWritePlan(state: MotorConfigurationUiState, leftText: String,
    rightText: String): MotorConfigurationWritePlan? {
    if (parkingCurrentDraftAnalysis(state, leftText, rightText).errors.isNotEmpty()) return null
    return motorConfigurationWritePlan(parkingCurrentDraft(state, leftText, rightText))
}

/** Recheck a reviewed plan immediately before dispatch; no stale identity, raw value or extra field passes. */
internal fun parkingCurrentWritePlanIsCurrent(state: MotorConfigurationUiState,
    plan: MotorConfigurationWritePlan): Boolean {
    val snapshot = state.snapshot ?: return false
    if (plan.writeMode != MotorConfigurationWriteMode.PERSISTENT || plan.changes.isEmpty() ||
        plan.changes.any { it.field !in parkingCurrentFields || !it.field.accepts(it.raw) } ||
        plan.changes.map { it.field }.distinct().size != plan.changes.size) return false
    fun proposed(field: MotorConfigurationField): String? =
        (plan.changes.singleOrNull { it.field == field }?.raw ?: snapshot.values[field])?.let(field::display)
    val left = proposed(MotorConfigurationField.LEFT_PARKING_CURRENT) ?: return false
    val right = proposed(MotorConfigurationField.RIGHT_PARKING_CURRENT) ?: return false
    return plan == parkingCurrentWritePlan(state, left, right)
}

/** Last read values are history only; they never enable the runtime parking switch. */
fun parkingCurrentSettings(snapshot: MotorConfigurationSnapshot?): ParkingSettings? {
    snapshot ?: return null
    val left = snapshot.values[MotorConfigurationField.LEFT_PARKING_CURRENT] ?: return null
    val right = snapshot.values[MotorConfigurationField.RIGHT_PARKING_CURRENT] ?: return null
    val maximum = snapshot.values[MotorConfigurationField.MAX_OUTPUT_CURRENT] ?: return null
    if (left !in 0L..6000L || right !in 0L..6000L || maximum !in 50L..6000L) return null
    return ParkingSettings(false, left / 100.0, right / 100.0, maximum / 100.0)
}
