package com.example.followingcar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the existing follow owner and its heartbeat runnable; it never creates a board session. */
class FollowBackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var renewal: Job? = null
    private var requestObservation: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var generation = -1L
    private var latestStartId = 0
    private var foreground = false
    private var lastNotificationText: String? = null
    private var publishedNotification: Notification? = null
    private var creationFailure: Exception? = null
    private val owner: BoardViewModel get() = (application as FollowingCarApplication).boardViewModel

    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "自动跟随后台控制", NotificationManager.IMPORTANCE_LOW))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "$packageName:follow-heartbeat").apply { setReferenceCounted(false) }
        } catch (failure: Exception) {
            // onCreate has no request token. Handle the failure when onStartCommand identifies
            // its owner, so a cancelled older start cannot stop a newer follow run.
            creationFailure = failure
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val token = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        if (intent?.action == ACTION_STOP) {
            // Ignore notifications from a previous run/generation. No implicit connect or start.
            if (AndroidFollowBackgroundController.requests.isCurrent(token)) {
                owner.stopFollow("已从通知请求停止跟随", ControlStopSource.USER_STOP)
                update(owner.state.value)
            } else if (!foreground && !AndroidFollowBackgroundController.requests.hasPendingRequest()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            if (!foreground && !AndroidFollowBackgroundController.requests.hasPendingRequest()) stopSelf(startId)
            return START_NOT_STICKY
        }
        val current = AndroidFollowBackgroundController.requests.isCurrent(token)
        if (current) generation = token
        try {
            // EVERY delivered startForegroundService request must promote, even when its
            // generation was cancelled before delivery. Never replace a newer live notification
            // with an older request's placeholder, and never create an owner for cancelled work.
            val initialNotification = if (current) notification(owner.state.value)
                else publishedNotification ?: cancelledStartNotification()
            startForeground(ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            publishedNotification = initialNotification
            foreground = true
            observeRequests()
            if (!current || !AndroidFollowBackgroundController.requests.isCurrent(token)) {
                retireIfUnrequested()
                return START_NOT_STICKY
            }
            creationFailure?.let { throw it }
            observation?.cancel()
            renewal?.cancel()
            observation = scope.launch { owner.state.collect { update(it) } }
            renewal = scope.launch {
                try {
                    while (isActive) {
                        delay(20_000)
                        if (needsCpu(owner.state.value) && AndroidFollowBackgroundController.requests.isCurrent(generation))
                            wakeLock?.acquire(60_000)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
            Diagnostics.event("follow.background_started", fields = mapOf("generation" to token), critical = true)
        } catch (failure: Exception) {
            fail(failure)
        }
        return START_NOT_STICKY
    }

    private fun needsCpu(state: BoardUiState) = (state.followActive || state.followStopping) && !state.followTerminal

    private fun observeRequests() {
        if (requestObservation?.isActive == true) return
        requestObservation = scope.launch {
            AndroidFollowBackgroundController.requests.changes.collect {
                try {
                    if (!AndroidFollowBackgroundController.requests.isCurrent(generation)) {
                        releaseCpu()
                        retireIfUnrequested()
                    }
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
        }
    }

    private fun retireIfUnrequested() {
        // A newer start can be queued for this instance. Its generation must reach onStartCommand
        // and satisfy Android's promotion requirement before this service can be stopped.
        if (AndroidFollowBackgroundController.requests.hasPendingRequest()) return
        observation?.cancel()
        renewal?.cancel()
        releaseCpu()
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishedNotification = null
        lastNotificationText = null
        foreground = false
        if (latestStartId != 0) stopSelf(latestStartId) else stopSelf()
    }

    private fun update(state: BoardUiState) {
        try {
            // A new explicit start may already be queued for this same Service instance.
            // The old observation must not stop that newer request before it is delivered.
            if (!AndroidFollowBackgroundController.requests.isCurrent(generation) &&
                AndroidFollowBackgroundController.requests.hasPendingRequest()) return
            if (!AndroidFollowBackgroundController.requests.isCurrent(generation) || !needsCpu(state)) {
                AndroidFollowBackgroundController.requests.finish(generation)
                retireIfUnrequested()
                return
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(60_000)
            val text = notificationText(state)
            if (text != lastNotificationText) {
                val updated = notification(state)
                getSystemService(NotificationManager::class.java).notify(ID, updated)
                publishedNotification = updated
                lastNotificationText = text
            }
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun notificationText(state: BoardUiState): String = when {
        state.followDetached || state.followUncertain -> "通信状态待确认，请返回 App 查看原会话。"
        state.followStopPending -> "停止请求已发送，正在等待会话结束。"
        state.followStatus?.runState == "STARTING" || state.followStatus == null -> "正在准备自动跟随，可返回 App 查看或停止。"
        else -> "自动跟随心跳保持中，可返回控制或请求停止。"
    }

    private fun cancelledStartNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_car).setContentTitle("自动跟随后台控制")
        .setContentText("正在结束已取消的后台请求。")
        .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build()

    private fun notification(state: BoardUiState): Notification {
        val open = PendingIntent.getActivity(this, 143, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Immutable action identity includes the generation; an old notification cannot stop a new run.
        val stop = PendingIntent.getService(this, generation.toInt(), Intent(this, FollowBackgroundService::class.java)
            .setAction(ACTION_STOP).setData(Uri.parse("followingcar://follow-stop/$generation"))
            .putExtra(EXTRA_GENERATION, generation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("自动跟随后台控制").setContentText(notificationText(state))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "返回控制", open).build())
            .addAction(Notification.Action.Builder(null, "停止跟随", stop).build()).build()
    }

    private fun releaseCpu() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun fail(failure: Exception) {
        val owned = AndroidFollowBackgroundController.requests.finish(generation, "跟随后台服务不可用")
        Diagnostics.failure(Diagnostics.newContext(), "follow.background_service_failed", failure)
        releaseCpu()
        if (owned) runCatching { owner.onFollowBackgroundUnavailable(failure) }
        runCatching { retireIfUnrequested() }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseCpu()
        foreground = false
        publishedNotification = null
        val unexpectedlyEnded = AndroidFollowBackgroundController.requests.finish(generation, "跟随后台服务已中断")
        if (unexpectedlyEnded) runCatching {
            owner.onFollowBackgroundUnavailable(IllegalStateException("跟随后台服务已中断"))
        }
        Diagnostics.event("follow.background_stopped", fields = mapOf("generation" to generation), critical = true)
        super.onDestroy()
    }

    /** Same-process inspection/binding; the component is not exported. */
    internal inner class LocalBinder : Binder() {
        val controlOwner: BoardViewModel get() = owner
        val cpuHeld: Boolean get() = wakeLock?.isHeld == true
        val isForeground: Boolean get() = foreground
        val notificationSnapshot: Notification? get() = publishedNotification
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    companion object {
        internal const val CHANNEL = "car_follow_control"
        internal const val ID = 143
        internal const val ACTION_START = "com.example.followingcar.FOLLOW_BACKGROUND_START"
        internal const val ACTION_STOP = "com.example.followingcar.FOLLOW_BACKGROUND_STOP"
        internal const val EXTRA_GENERATION = "generation"
    }
}
