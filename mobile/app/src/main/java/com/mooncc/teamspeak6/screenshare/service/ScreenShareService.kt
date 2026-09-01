package com.mooncc.teamspeak6.screenshare.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mooncc.teamspeak6.R
import com.mooncc.teamspeak6.notification.NotificationChannels
import com.mooncc.teamspeak6.ui.MainActivity

/**
 * Foreground service that must be running while the screen is captured.
 *
 * From API 29 on, [android.media.projection.MediaProjection] only starts while a
 * service with the `mediaProjection` foreground type is active, so this service
 * exists purely to hold that state — capture itself lives in
 * [com.mooncc.teamspeak6.screenshare.ScreenShareManager].
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundInternal()
        }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        NotificationChannels.ensureCreated(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NotificationChannels.SCREEN_SHARE)
            .setSmallIcon(R.drawable.ic_notification_screen_share)
            .setContentTitle("正在共享屏幕")
            .setContentText("点击返回 TeamSpeak")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, "停止共享", stopIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.mooncc.teamspeak6.action.STOP_SCREEN_SHARE"

        fun start(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
