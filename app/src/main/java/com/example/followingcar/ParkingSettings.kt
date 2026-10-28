package com.example.followingcar

/** Matches the keyboard controller's parking-current setting, in whole amperes. */
data class ParkingSettings(val enabled: Boolean = false, val currentAmps: Int = 5) {
    init {
        require(currentAmps in 0..60) { "驻车电流必须在 0–60 A 之间" }
        require(!enabled || currentAmps > 0) { "驻车电流为 0 A 时不能开启驻车" }
    }
}
