package com.example.followingcar

/** Existing driver configuration is read-only during a drive session; enabled is a stop mode. */
data class ParkingSettings(
    val enabled: Boolean = false,
    val leftCurrentAmps: Double = 0.0,
    val rightCurrentAmps: Double = 0.0,
    val maxCurrentAmps: Double = 0.0,
) {
    init {
        require(listOf(leftCurrentAmps, rightCurrentAmps, maxCurrentAmps).all { it.isFinite() && it >= 0.0 }) {
            "驻车电流读数必须为有限非负数"
        }
    }

    val canEnable: Boolean get() = maxCurrentAmps > 0.0 && maxCurrentAmps <= 60.0 &&
        leftCurrentAmps > 0.0 && rightCurrentAmps > 0.0 &&
        leftCurrentAmps <= minOf(maxCurrentAmps, 60.0) && rightCurrentAmps <= minOf(maxCurrentAmps, 60.0)
}
