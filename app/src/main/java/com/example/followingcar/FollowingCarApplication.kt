package com.example.followingcar

import android.app.Application
import android.app.ActivityManager
import java.time.Instant

class FollowingCarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
        // Historical exit lookup uses a system service; keep it off the startup/control thread.
        Thread({
            runCatching {
                val previousExit = getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(packageName, 0, 1)
                    .firstOrNull()
                if (previousExit != null) {
                    // Never read the description or trace: either may contain arbitrary app data.
                    Diagnostics.event("app.previous_process_exit", fields = mapOf(
                        "reason" to previousExit.reason,
                        "exitStatus" to previousExit.status,
                        "exitTimestampMs" to previousExit.timestamp,
                        "exitTime" to Instant.ofEpochMilli(previousExit.timestamp).toString(),
                        "exitPid" to previousExit.pid,
                    ), critical = true)
                }
            }.onFailure { error ->
                Diagnostics.event("app.previous_process_exit_unavailable", fields = mapOf(
                    "exceptionType" to error.javaClass.name,
                ))
            }
        }, "diagnostic-exit-history").apply { isDaemon = true }.start()
    }
}
