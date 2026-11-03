package com.example.followingcar

import java.util.concurrent.CancellationException

internal class DriveStopRequestedException(source: String) :
    CancellationException("手动控制已请求停止（$source）")

/** Serializes transaction admission with an intentional stop, including the pre-write race. */
internal class DriveTransactionGate {
    private var pending = false
    private var stopSource: String? = null
    private var interrupted = false

    @Synchronized fun begin(terminal: Boolean) {
        if (!terminal) stopSource?.let { throw DriveStopRequestedException(it) }
        check(!pending) { "上一个摇杆指令尚未收到回执" }
        pending = true
    }

    @Synchronized fun finish() { pending = false }

    /** True means an admitted transaction must be interrupted; future motion is refused either way. */
    @Synchronized fun requestStop(source: String): Boolean {
        if (stopSource == null) stopSource = source
        if (pending) interrupted = true
        return pending
    }

    @Synchronized fun isPending(): Boolean = pending
    @Synchronized fun requestedStopSource(): String? = stopSource
    @Synchronized fun interruptedStopSource(): String? = stopSource.takeIf { interrupted }
}

internal fun shouldInterruptDriveForStop(emergency: Boolean, source: ControlStopSource): Boolean =
    emergency || source != ControlStopSource.TRANSPORT_LOST
