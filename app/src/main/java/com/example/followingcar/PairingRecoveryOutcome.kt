package com.example.followingcar

/** Releasing a reconciled request is not proof that this operation successfully provisioned it.
 * Only a fresh submission that actually reached the writer is presented as a failed attempt;
 * querying an older request or confirming cancellation remains a neutral recovery outcome. */
internal fun PairingState.withReleasedPairingOutcome(
    resultCode: String,
    submittedThisOperation: Boolean,
    firstFailureCode: String?,
): PairingState {
    require(pairingLeaseExpired(resultCode))
    val cancelled = firstFailureCode in setOf("cancelled", "join_cancelled")
    val failedAttempt = submittedThisOperation && !cancelled
    val restored = if (resultCode == "network_restored") "小车已恢复原网络。" else pairingErrorMessage(resultCode)
    val first = firstFailureCode?.takeUnless { cancelled }?.let(::pairingErrorMessage)
    return copy(
        stage = if (failedAttempt) PairingStage.ERROR else PairingStage.IDLE,
        hotspotActive = false,
        boardIp = null,
        confirmationRequired = false,
        status = if (failedAttempt) "本次配网未成功，$restored" else pairingErrorMessage(resultCode),
        error = if (failedAttempt) first?.let { "本次配网失败：$it $restored" }
            ?: "本次未能接入手机热点，$restored" else null,
        firstError = if (cancelled) null else first ?: firstError,
    )
}
