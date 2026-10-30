package com.example.followingcar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Only a fresh authenticated connection and read-only status may be replayed. */
internal suspend fun <T> withPairingReadRetry(
    delays: List<Long> = listOf(750, 1500),
    onRetry: suspend (Int, Exception) -> Unit = { _, _ -> },
    read: suspend () -> T,
): T {
    require(delays.size <= 3 && delays.all { it in 0..5000 })
    var attempt = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try { return read() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (!pairingTransportRetryable(failure) || attempt >= delays.size) throw failure
            onRetry(attempt + 2, failure)
            delay(delays[attempt++])
        }
    }
}

internal fun pairingTransportRetryable(failure: Exception): Boolean =
    failure is TimeoutCancellationException || (failure is PairingFailure && failure.code in setOf(
        "bluetooth_failed", "bluetooth_timeout", "bluetooth_eof", "partial_reply"))

/** The submit block is called exactly once, including when its write/ACK is interrupted. */
internal suspend fun <T> submitPairingOnce(
    submit: suspend () -> T,
    recover: suspend (Exception) -> T,
): T = try { submit() } catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
    if (!pairingTransportRetryable(failure) && (failure !is PairingFailure || failure.code !in setOf(
            "protocol_error", "empty_reply", "invalid_reply_encoding", "reply_too_large",
            "network_address_unconfirmed", "address_unverified", "provisioning_pending", "hotspot_stopped"))) throw failure
    recover(failure)
}

/** Poll only an existing request. A null observation never grants permission to resend credentials. */
internal suspend fun <T : Any> awaitPairingRecovery(
    timeoutMs: Long = 90_000,
    intervalMs: Long = 2500,
    onPending: suspend () -> Unit = {},
    query: suspend () -> T?,
): T = withTimeoutOrNull(timeoutMs) {
    var result: T? = null
    while (result == null) {
        currentCoroutineContext().ensureActive()
        result = query()
        if (result == null) { onPending(); delay(intervalMs) }
    }
    result
} ?: throw PairingFailure("recovery_timeout")
