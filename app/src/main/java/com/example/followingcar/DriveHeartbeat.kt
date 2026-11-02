package com.example.followingcar

/** One request in flight. Slow ACKs consume the period instead of starting another 100 ms wait. */
internal fun driveHeartbeatWaitMs(startedNanos: Long, nowNanos: Long = System.nanoTime()): Long =
    (100L - ((nowNanos - startedNanos).coerceAtLeast(0L) / 1_000_000L)).coerceAtLeast(0L)

internal fun DriveInterlock.message(): String {
    val names = listOf("forward" to "前方", "left" to "左侧", "right" to "右侧")
        .filter { it.first in directions }.joinToString("、") { it.second }
    return "红外保护已停车（$names），请松开摇杆回中后重新操作。"
}
