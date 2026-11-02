package com.example.followingcar

/** A negotiated board-side infrared stop, distinct from a broken control session. */
data class DriveInterlock(val blocked: Boolean, val directions: Set<String>)

/** Validate typed protocol values without relaxing legacy motor-target checks. */
internal fun validatedDriveInterlock(
    supported: Boolean,
    command: String,
    requestedLeft: Int,
    requestedRight: Int,
    actualLeft: Int,
    actualRight: Int,
    stopped: Boolean,
    blocked: Boolean?,
    directions: List<String>?,
): DriveInterlock? {
    require(stopped == (actualLeft == 0 && actualRight == 0)) { "开发板停车回执不一致" }
    if (!supported) {
        // Old boards need not send these fields. Unnegotiated metadata never authorizes
        // target changes; an explicit false/empty report is harmless on a dry-run board.
        require(blocked != true && (directions == null || directions.isEmpty())) {
            "开发板未协商红外保护，却返回了红外拦停"
        }
        require(actualLeft == requestedLeft && actualRight == requestedRight) {
            "开发板回执速度与指令不一致"
        }
        return null
    }
    require(blocked != null && directions != null) { "开发板红外保护回执字段缺失" }
    require(directions.size <= 3 && directions.toSet().size == directions.size &&
        directions.all { it in setOf("forward", "right", "left") }) {
        "开发板红外保护方向无效"
    }
    if (blocked) {
        require(command == "drive" && (requestedLeft != 0 || requestedRight != 0) &&
            actualLeft == 0 && actualRight == 0 && stopped && directions.isNotEmpty()) {
            "开发板红外拦停回执与指令不一致"
        }
    } else {
        require(directions.isEmpty()) { "开发板未拦停却返回了红外保护方向" }
        require(actualLeft == requestedLeft && actualRight == requestedRight) {
            "开发板回执速度与指令不一致"
        }
    }
    return DriveInterlock(blocked, directions.toSet())
}
