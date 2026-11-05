package com.example.followingcar

/** Fixed reasons shared by button availability, its explanation and diagnostic events. */
internal enum class DriveStartBlock(val message: String) {
    DISCONNECTED("请先在连接页连接小车"),
    BUSY("连接或其他操作正在进行，请稍候"),
    PAIRING_BUSY("正在配网，完成后可启用手动控制"),
    LOCAL_PAIRING_UNCONFIRMED("本地配网结果待核对，请恢复配对；也可切换远程连接"),
    MANUAL_ACTIVE("手动控制正在运行或切换状态"),
    VIDEO_BUSY("正在处理视频服务，请稍候"),
    FOLLOW_ACTIVE("请先停止自动跟随，再启用手动控制"),
    FOLLOW_STOPPING("自动跟随正在停止，请等待结束"),
    FOLLOW_UNCONFIRMED("上次跟随退出待确认，请在跟随页检查控制占用"),
    MOTOR_UNCONFIRMED("上次电机操作待确认，请在电机页核对操作结果"),
    DRIVE_UNCONFIRMED("上次手动控制退出待确认，请检查控制占用"),
}

internal fun BoardUiState.driveStartBlock(): DriveStartBlock? = when {
    !connected -> DriveStartBlock.DISCONNECTED
    busy -> DriveStartBlock.BUSY
    pairingBusy -> DriveStartBlock.PAIRING_BUSY
    pairingBlocksControl -> DriveStartBlock.LOCAL_PAIRING_UNCONFIRMED
    manualActive -> DriveStartBlock.MANUAL_ACTIVE
    videoServiceBusy -> DriveStartBlock.VIDEO_BUSY
    followActive -> DriveStartBlock.FOLLOW_ACTIVE
    followStopping -> DriveStartBlock.FOLLOW_STOPPING
    followUncertain -> DriveStartBlock.FOLLOW_UNCONFIRMED
    motorActionUnconfirmed -> DriveStartBlock.MOTOR_UNCONFIRMED
    driveCleanupUnconfirmed -> DriveStartBlock.DRIVE_UNCONFIRMED
    else -> null
}
