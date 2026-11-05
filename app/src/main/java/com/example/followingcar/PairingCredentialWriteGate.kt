package com.example.followingcar

/** An observation must be captured under the same lock used by hotspot loss callbacks. */
internal data class PairingCredentialLease(
    val active: Boolean,
    val binding: Any?,
    val requestId: String?,
)

/** Arbitrates cancellation against the first possible credential byte, not against task enqueue.
 * Once WRITING wins, even a failed or cancelled write must retain its recovery record. */
internal class PairingCredentialWriteGate(
    private val binding: Any,
    val requestId: String,
) {
    private enum class State { PENDING, WRITING, CANCELLED }
    private var state = State.PENDING

    @Synchronized fun matches(lease: PairingCredentialLease): Boolean =
        lease.active && lease.binding === binding && lease.requestId == requestId

    @Synchronized fun beginWriting(lease: PairingCredentialLease): Boolean {
        if (state != State.PENDING) return false
        if (!matches(lease)) {
            state = State.CANCELLED
            return false
        }
        state = State.WRITING
        return true
    }

    @Synchronized fun cancelBeforeWrite() {
        if (state == State.PENDING) state = State.CANCELLED
    }

    val hasStarted: Boolean
        @Synchronized get() = state == State.WRITING
}
