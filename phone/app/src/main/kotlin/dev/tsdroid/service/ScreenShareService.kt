package dev.tsdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.tsdroid.MainActivity
import dev.tsdroid.han.R
import dev.tsdroid.screen.ScreenShareController

/**
 * 屏幕共享前台服务（A3 发布端）。
 *
 * 职责：承载 MediaProjection 授权会话。Android 14+ 要求：
 * 1. `mediaProjection` 类型的前台服务必须在 `createVirtualDisplay` 前启动；
 * 2. 每次共享都必须重新征求用户同意（授权结果通过 Intent 传入）。
 *
 * 采集与信令编排见 [ScreenShareController]。
 */
class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 0x5353

        private const val ACTION_STOP = "dev.tsdroid.action.STOP_SCREEN_SHARE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STREAM_NAME = "stream_name"
        const val EXTRA_WITH_AUDIO = "with_audio"

        /** 启动共享（必须在用户同意 MediaProjection 授权之后调用）。 */
        fun start(context: Context, resultCode: Int, data: Intent, streamName: String, withAudio: Boolean) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_STREAM_NAME, streamName)
                putExtra(EXTRA_WITH_AUDIO, withAudio)
            }
            context.startForegroundService(intent)
        }

        /** 停止共享（通知栏按钮 / 程序内停止均走这里）。 */
        fun stop(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "stop service failed", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from notification")
            ScreenShareController.stopPublishing()
            return START_NOT_STICKY
        }

        val streamName = intent.getStringExtra(EXTRA_STREAM_NAME)
            ?: getString(R.string.screen_share_default_name)
        startForegroundCompat(streamName)

        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false)

        val tsClient = TsConnectionService.instance?.tsClient
        if (resultData == null || tsClient == null || !tsClient.isConnected) {
            Log.e(TAG, "Cannot start screen share (data=$resultData connected=${tsClient?.isConnected})")
            stopSelf()
            return START_NOT_STICKY
        }

        ScreenShareController.startPublisher(
            context = applicationContext,
            tsClient = tsClient,
            resultCode = resultCode,
            resultData = resultData,
            streamName = streamName,
            withAudio = withAudio,
        )
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(streamName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.screen_share_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.screen_share_notification_title))
            .setContentText(streamName)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                getString(R.string.screen_share_notification_stop),
                stopIntent,
            )
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        // 服务被系统或用户关闭时兜底停止发布（幂等）
        ScreenShareController.onServiceDestroyed()
        super.onDestroy()
    }
}
