package com.mooncc.teamspeak6.voice.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mooncc.teamspeak6.R
import com.mooncc.teamspeak6.di.ApplicationScope
import com.mooncc.teamspeak6.domain.model.Channel
import com.mooncc.teamspeak6.domain.model.ConnectionState
import com.mooncc.teamspeak6.domain.model.ConnectionStatus
import com.mooncc.teamspeak6.domain.model.LocalMediaState
import com.mooncc.teamspeak6.domain.repository.TeamSpeakRepository
import com.mooncc.teamspeak6.notification.NotificationChannels
import com.mooncc.teamspeak6.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Keeps the voice connection alive while the app is backgrounded.
 *
 * Android stops handing CPU time to a plain background process, which would stall
 * the capture/playback loops and let the server time the client out, so an
 * ongoing `microphone`-typed foreground notification is mandatory rather than a
 * nicety. The notification doubles as the remote control: mute and disconnect are
 * reachable without reopening the app.
 *
 * The service owns no connection state — it mirrors [TeamSpeakRepository] and
 * stops itself once the repository reports the session is gone.
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject
    lateinit var repository: TeamSpeakRepository

    /**
     * Used for actions that outlive the service: [ACTION_DISCONNECT] tears down the
     * session, which stops this service, so the call cannot run on a scope that
     * dies with it.
     */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob()) + Dispatchers.Main.immediate
    private var observeJob: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                val muted = repository.localMediaState.value.micMuted
                applicationScope.launch { repository.setMicMuted(!muted) }
            }

            ACTION_DISCONNECT -> {
                applicationScope.launch { repository.disconnect() }
                // The observer below would also stop us, but doing it eagerly keeps
                // the notification from lingering while disconnect() unwinds.
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }

        ensureForeground()
        observeSession()
        return START_STICKY
    }

    private fun ensureForeground() {
        if (started) return
        NotificationChannels.ensureCreated(this)
        val notification = buildNotification(
            repository.connectionState.value,
            repository.localMediaState.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        started = true
    }

    private fun observeSession() {
        if (observeJob?.isActive == true) return
        observeJob = serviceScope.launch {
            combine(
                repository.connectionState,
                repository.localMediaState,
                repository.channelTree,
            ) { connection, media, tree ->
                Snapshot(connection, media, channelName(tree, connection.currentChannelId))
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot.connection.status == ConnectionStatus.DISCONNECTED ||
                        snapshot.connection.status == ConnectionStatus.ERROR
                    ) {
                        stopForegroundAndSelf()
                        return@collect
                    }
                    NotificationManagerCompat.from(this@VoiceService).runCatching {
                        notify(
                            NOTIFICATION_ID,
                            buildNotification(
                                snapshot.connection,
                                snapshot.media,
                                snapshot.channelName,
                            ),
                        )
                    }
                }
        }
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        started = false
        stopSelf()
    }

    private fun buildNotification(
        connection: ConnectionState,
        media: LocalMediaState,
        channelName: String? = null,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val muteIntent = action(REQUEST_MUTE, ACTION_TOGGLE_MUTE)
        val disconnectIntent = action(REQUEST_DISCONNECT, ACTION_DISCONNECT)

        val title = connection.server?.name?.takeIf { it.isNotBlank() }
            ?: connection.bookmark?.label?.takeIf { it.isNotBlank() }
            ?: "TeamSpeak"

        val text = when (connection.status) {
            ConnectionStatus.CONNECTING -> "正在连接…"
            ConnectionStatus.RECONNECTING -> "连接中断，正在重连…"
            else -> buildString {
                append(channelName ?: "已连接")
                if (media.micMuted) append(" · 麦克风已静音")
                if (media.speakerMuted) append(" · 扬声器已静音")
            }
        }

        return NotificationCompat.Builder(this, NotificationChannels.VOICE)
            .setSmallIcon(R.drawable.ic_notification_voice)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, if (media.micMuted) "取消静音" else "静音", muteIntent)
            .addAction(0, "断开", disconnectIntent)
            .build()
    }

    private fun action(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, VoiceService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onDestroy() {
        observeJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class Snapshot(
        val connection: ConnectionState,
        val media: LocalMediaState,
        val channelName: String?,
    )

    companion object {
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_MUTE = 1
        private const val REQUEST_DISCONNECT = 2

        const val ACTION_TOGGLE_MUTE = "com.mooncc.teamspeak6.action.TOGGLE_MIC_MUTE"
        const val ACTION_DISCONNECT = "com.mooncc.teamspeak6.action.DISCONNECT"

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            // Starting from the background throws on API 31+; a connection is always
            // user-initiated, but a reconnect must not take the process down with it.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        }

        private fun channelName(tree: List<Channel>, channelId: Int): String? {
            if (channelId == 0) return null
            tree.forEach { channel ->
                if (channel.id == channelId) return channel.displayName
                channelName(channel.subChannels, channelId)?.let { return it }
            }
            return null
        }
    }
}
