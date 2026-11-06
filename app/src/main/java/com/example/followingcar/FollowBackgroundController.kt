package com.example.followingcar

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface FollowBackgroundController {
    fun start()
    fun stop()

    object None : FollowBackgroundController {
        override fun start() = Unit
        override fun stop() = Unit
    }
}

/** In-process requests only. A stale queued service start cannot revive a finished follow loop. */
internal class FollowBackgroundRequestGate {
    private var revision = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    private var requested = false
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    @Volatile var failure: String? = null
        private set

    @Synchronized fun start(): Long {
        revision++
        requested = true
        failure = null
        mutableChanges.value++
        return revision
    }

    @Synchronized fun stop() {
        revision++
        requested = false
        mutableChanges.value++
    }

    @Synchronized fun isCurrent(token: Long): Boolean = requested && token == revision

    @Synchronized fun hasPendingRequest(): Boolean = requested

    /** Only the owning generation can end a request or report its failed promotion. */
    @Synchronized fun finish(token: Long, error: String? = null): Boolean {
        if (!isCurrent(token)) return false
        requested = false
        failure = error
        mutableChanges.value++
        return true
    }
}

internal class AndroidFollowBackgroundController(context: Context) : FollowBackgroundController {
    private val app = context.applicationContext

    override fun start() {
        val token = requests.start()
        try {
            ContextCompat.startForegroundService(app, Intent(app, FollowBackgroundService::class.java)
                .setAction(FollowBackgroundService.ACTION_START).putExtra(FollowBackgroundService.EXTRA_GENERATION, token))
        } catch (failure: Exception) {
            requests.finish(token, "无法启动跟随后台服务")
            Diagnostics.failure(Diagnostics.newContext(), "follow.background_start_failed", failure)
            throw IllegalStateException("无法启动跟随后台服务，请保持 App 前台并重试", failure)
        }
    }

    override fun stop() {
        // Do not stopService while an ACTION_START is still queued. Its Android foreground
        // promotion obligation survives cancellation, especially while another client binds.
        // The service promotes first, then observes this cancellation and retires itself.
        requests.stop()
    }

    companion object {
        internal val requests = FollowBackgroundRequestGate()
    }
}
