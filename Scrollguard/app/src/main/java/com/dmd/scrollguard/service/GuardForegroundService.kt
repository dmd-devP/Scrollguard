package com.dmd.scrollguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Minimal foreground service: its only job is the persistent low-priority
 * notification that keeps Android from killing the guard.
 */
class GuardForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "scrollguard_keeper"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "ScrollGuard", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ScrollGuard is watching the feed")
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

/** Re-arm after reboot (accessibility re-binds on its own once enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                context.startService(Intent(context, GuardForegroundService::class.java))
            } catch (_: Exception) { /* fg-from-background limits; a11y service will restart it */ }
        }
    }
}
