package com.example.followingcar

/** Only a board that separates its motion lease from session expiry may wait longer. */
internal fun driveAckTimeoutMillis(networkGraceMs: Int?): Long {
    require(networkGraceMs == null || networkGraceMs in setOf(5_000, 15_000)) { "开发板网络恢复参数不匹配" }
    return when (networkGraceMs) {
        null -> 900L
        5_000 -> 4_250L
        else -> 8_000L
    }
}

/** Waiting for an ACK may tolerate jitter; a blocked socket write still has a short deadline. */
internal const val DRIVE_WRITE_TIMEOUT_MS = 2_500L
internal const val DRIVE_ACK_WARNING_MS = 1_200L

internal class DriveAckTimeoutException(message: String, val transportRetained: Boolean) :
    java.net.SocketTimeoutException(message)

internal fun validatedDriveRecovery(
    supported: Boolean, reported: Boolean?, left: Int, right: Int, stopped: Boolean,
): Boolean {
    require(!supported || reported != null) { "开发板网络恢复回执字段缺失" }
    require(supported || reported != true) { "开发板未协商网络恢复能力" }
    if (reported == true) require(left == 0 && right == 0 && stopped) { "网络恢复时必须保持停车" }
    return reported == true
}

/** An old held gesture cannot become motion again after a board-side network stop. */
internal class DriveRecoveryGate {
    private var inputHeld = false
    private var awaitingNeutral = false
    var needsRelease = false
        private set

    fun input(): Boolean {
        inputHeld = true
        return !needsRelease
    }

    /** False means this only confirms the neutral request from the same recovery. */
    fun recovered(neutralRequest: Boolean = false): Boolean {
        if (awaitingNeutral && neutralRequest) {
            awaitingNeutral = false
            return false
        }
        awaitingNeutral = !neutralRequest
        needsRelease = needsRelease || inputHeld
        return true
    }
    fun healthyAck(neutralRequest: Boolean) { if (neutralRequest) awaitingNeutral = false }
    fun released() { inputHeld = false; needsRelease = false }
    fun reset() { released(); awaitingNeutral = false }
}
