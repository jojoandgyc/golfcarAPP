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

/** Keeps an explicit Bluetooth provisioning transaction alive through system pairing dialogs.
 * It does not own, create or stop the user's system hotspot. */
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
            .setContentTitle("正在配对小车")
            .setContentText("正在通过蓝牙核对入网结果，手机热点由系统管理。")
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
