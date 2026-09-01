package com.mooncc.teamspeak6.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Single place where every notification channel is declared.
 *
 * Channels are created eagerly at process start so the user can adjust them in
 * system settings before anything is ever posted, and so no posting site has to
 * remember to create its own channel first.
 */
object NotificationChannels {

    /** Ongoing notification for the active voice connection. */
    const val VOICE = "voice_session"

    /** Ongoing notification held while the screen is being captured. */
    const val SCREEN_SHARE = "screen_share"

    /** Pokes and private messages that arrive while the app is backgrounded. */
    const val EVENTS = "server_events"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.create(
            id = VOICE,
            name = "语音连接",
            description = "已连接到服务器时的常驻通知",
            importance = NotificationManager.IMPORTANCE_LOW,
        )
        manager.create(
            id = SCREEN_SHARE,
            name = "屏幕共享",
            description = "屏幕共享进行中的常驻通知",
            importance = NotificationManager.IMPORTANCE_LOW,
        )
        manager.create(
            id = EVENTS,
            name = "服务器消息",
            description = "私聊消息与 poke 提醒",
            importance = NotificationManager.IMPORTANCE_HIGH,
            showBadge = true,
        )
    }

    private fun NotificationManager.create(
        id: String,
        name: String,
        description: String,
        importance: Int,
        showBadge: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Importance and name are only honoured on creation; recreating an existing
        // channel would silently discard the user's own overrides.
        if (getNotificationChannel(id) != null) return
        createNotificationChannel(
            NotificationChannel(id, name, importance).also {
                it.description = description
                it.setShowBadge(showBadge)
            },
        )
    }
}
