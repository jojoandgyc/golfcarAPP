package com.example.followingcar

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class WheelSpeeds(val left: Int = 0, val right: Int = 0) {
    val stopped: Boolean get() = left == 0 && right == 0
}

/** Matches keyboard_drive_auto_115200_linux.sh's --swap-turns wiring. */
fun joystickToWheels(x: Float, forward: Float, speedLimit: Int): WheelSpeeds {
    if (!x.isFinite() || !forward.isFinite()) return WheelSpeeds()
    val distance = hypot(x.toDouble(), forward.toDouble())
    val deadZone = 0.12
    if (distance <= deadZone) return WheelSpeeds()
    val strength = ((distance.coerceAtMost(1.0) - deadZone) / (1.0 - deadZone))
    val turn = x / distance * strength
    val throttle = forward / distance * strength
    val left = throttle + turn
    val right = -throttle + turn
    val divisor = maxOf(1.0, abs(left), abs(right))
    val limit = speedLimit.coerceIn(0, 100)
    return WheelSpeeds((left / divisor * limit).roundToInt(), (right / divisor * limit).roundToInt())
}
