package com.example.followingcar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps the process holding the local-hotspot reservation alive while the board uses it.
 * Restarting this service never starts a new hotspot, pairing attempt or control session. */
class PairingHotspotService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "小车连接", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("小车配对与热点连接")
            .setContentText("点击返回 App 查看连接；后台保留热点，控制仍遵循停车规则。")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "car_local_hotspot"
        private const val ID = 142
        fun setActive(context: Context, active: Boolean) {
            val intent = Intent(context.applicationContext, PairingHotspotService::class.java)
            if (active) ContextCompat.startForegroundService(context.applicationContext, intent)
            else context.applicationContext.stopService(intent)
        }
    }
}
