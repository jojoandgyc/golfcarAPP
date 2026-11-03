package com.example.followingcar

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Complete v2 safety metadata for memory-only channels; never added by production code. */
internal fun JSONObject.withDriveSafetyFixture(): JSONObject = apply {
    val left = optInt("left", 0)
    val right = optInt("right", 0)
    if (!has("left")) put("left", 0)
    if (!has("right")) put("right", 0)
    if (!has("stopped")) put("stopped", left == 0 && right == 0)
    put("targetStopped", left == 0 && right == 0)
    put("feedbackLeftRpm", left).put("feedbackRightRpm", right)
    put("feedbackLeftError", 0).put("feedbackRightError", 0)
    put("feedbackAgeMs", 0).put("feedbackSamples", 2).put("feedbackSource", "simulated")
    put("parkingEnabled", optBoolean("parkingEnabled", false))
    put("parkingLeftCurrentAmps", 5.0).put("parkingRightCurrentAmps", 5.0).put("parkingMaxCurrentAmps", 60.0)
    if (optString("type") == "ready") {
        put("safetyVersion", 2).put("parkingCurrentReadOnly", true).put("parkingSupported", true)
    }
}

/** Ordinary neutral ACKs may precede the second real/simulated zero-speed sample. */
internal suspend fun awaitSimulatedDriveStop(channel: BoardDriveChannel): DriveAck = withTimeout(2_500L) {
    check(channel.ready.dryRun)
    var ack = channel.exchange(0, 0)
    while (!ack.stopped) {
        delay(30)
        ack = channel.exchange(0, 0)
    }
    check(ack.targetStopped && ack.feedback?.zeroSpeedConfirmed == true)
    ack
}
