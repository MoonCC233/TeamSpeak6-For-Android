package dev.tsdroid.service

import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.tsdroid.MainActivity
import dev.tsdroid.han.R
import dev.tsdroid.TsDroidApp
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.SettingsStore
import dev.tslib.ConnectionState
import dev.tslib.Identity
import dev.tslib.Channel
import dev.tslib.User
import dev.tsdroid.ui.component.AnimeWallpaperState
import dev.tsdroid.ui.icon.AppIcons
import dev.tsdroid.ui.theme.TsDroidTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * State of the automatic reconnection loop, exposed so the UI can stay on the
 * server screen (instead of bouncing the user back) while we retry.
 */
data class ReconnectStatus(
    val active: Boolean = false,
    val attempt: Int = 0,
    val nextRetrySeconds: Int = 0,
)

class TsConnectionService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore get() = serviceViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        private const val TAG = "TsConnService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.flammedemon.ts6droid.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.flammedemon.ts6droid.TOGGLE_MUTE"
        private const val SPEAKER_DELAY_MS = 500L
        private const val AVATAR_REFRESH_INTERVAL_MS = 30000L // 30 seconds
        private const val MIC_WATCHDOG_INTERVAL_MS = 30000L // 30 seconds
        private const val RECONNECT_FIRST_DELAY_MS = 3000L
        private const val RECONNECT_MAX_DELAY_MS = 60000L
        private const val RECONNECT_CHANNEL_RESTORE_DELAY_MS = 1500L

        /** One network handover fires several callbacks; collapse them into one reconnect. */
        private const val NETWORK_SWITCH_DEBOUNCE_MS = 4000L

        /**
         * Longest push-to-talk hold the service will honour.
         *
         * A release event that never arrives (a gesture the system cancelled without telling us,
         * a window torn down mid-hold) would otherwise leave the microphone open for good.
         */
        private const val MAX_TALK_HOLD_MS = 120_000L

        /** Gap kept between the docked floating window and the screen edge. */
        private const val EDGE_MARGIN_DP = 8f

        /** Must match the overlay's reveal animation; the anchor lives for this long after a toggle. */
        private const val REVEAL_ANIMATION_MS = 200L

        /** Poke notifications get unique ids above the fixed connection one. */
        private const val POKE_NOTIFICATION_BASE_ID = 1000

        /** Reconnect at most once per burst of network-change callbacks. */
        private const val NETWORK_RECONNECT_DEBOUNCE_MS = 5_000L

        var instance: TsConnectionService? = null
            private set
    }

    /**
     * Service-scope failures are logged, not fatal.
     *
     * This scope drives a live voice connection and a floating window over other apps. An unhandled
     * exception in a `launch` goes to the thread's crash handler and takes the whole process down —
     * which is how two floating-window bugs ended a call instead of just mis-drawing a bubble. The
     * connection paths handle their own errors; anything that still escapes is a UI-level mistake and
     * must not be allowed to kill the session.
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main +
            CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "Unhandled failure in the service, keeping the session alive", error)
            },
    )

    val tsClient = TsClient()
    lateinit var audioBridge: AudioBridge
        private set

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private var overlayConnected by mutableStateOf(false)
    private var overlayChannelName by mutableStateOf<String?>(null)
    private var overlayActiveSpeakerId by mutableStateOf<Int?>(null)
    private var overlayActiveSpeakerAvatar by mutableStateOf<ImageBitmap?>(null)
    
    // Delay mechanism for overlay speaker state changes
    private var pendingSpeakerId: Int? = null
    private var speakerUpdateJob: kotlinx.coroutines.Job? = null
    
    // Delay mechanism for local user speaking state changes
    private var pendingLocalSpeaking: Boolean? = null
    private var localSpeakingJob: kotlinx.coroutines.Job? = null
    private var delayedLocalSpeaking by mutableStateOf(false)
    
    private lateinit var avatarCache: AvatarCache

    // ── Floating window state ────────────────────────────────────────────────
    /** Whether the Activity is on screen. The window is for the times it is not. */
    private var appInForeground = true
    /** The user's "启用悬浮窗" setting, mirrored here so the service can act on changes. */
    private var overlayEnabled = false
    /** Messages that arrived while the app was away, shown as a badge on the bubble. */
    private var overlayUnread by mutableStateOf(0)
    private var isOverlayExpanded by mutableStateOf(false)
    private var overlayList by mutableStateOf(OverlayView.Members)
    /**
     * True while the bubble is held: the microphone is open *because of* that hold.
     *
     * The bubble reads it to show the user that they are live (and the service uses it to know
     * whether it may close a microphone it opened — never one the user opened themselves).
     */
    private var overlayTalking by mutableStateOf(false)
    /** When the current hold started, so a hold whose release went missing cannot stay open. */
    private var overlayTalkStartedAt = 0L
    private var overlayWidth = 0
    private var overlayHeight = 0
    /** Where the window sat before expanding/collapsing, so the docked edge can stay put. */
    private var overlayToggleAnchorX = 0
    private var overlayToggleAnchorWidth = 0
    private var overlayToggleDockedRight = false

    /** Sub-pixel drag left over from the last frame; a window moves in whole pixels only. */
    private var overlayDragRemainderX = 0f
    private var overlayDragRemainderY = 0f

    /** Set by an expand/collapse, applied once the new size has been laid out. */
    private var overlayAnchorPending = false
    private var lastSavedX = 100
    private var lastSavedY = 300
    
    private var isIntentionalDisconnect = false
    private var latestStartId = 0
    @Volatile private var isStopping = false
    @Volatile private var restartRequestedWhileStopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Automatic reconnection ────────────────────────────────────────────────
    private data class SessionParams(
        val address: String,
        val identity: Identity,
        val nickname: String,
        val password: String?,
    )

    /** Credentials of the last successful session, used to re-establish it. */
    @Volatile private var sessionParams: SessionParams? = null
    private var reconnectJob: Job? = null

    /** Cached preference so the disconnect handler can react without suspending. */
    @Volatile private var autoReconnectEnabled = false

    private val _reconnect = MutableStateFlow(ReconnectStatus())
    val reconnectStatus: StateFlow<ReconnectStatus> = _reconnect.asStateFlow()

    // Network-switch handling: reconnect as soon as a usable network appears
    // instead of waiting for the keepalive to time out.
    private var lastNetworkSwitchReconnectAt = 0L
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            forceImmediateReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        restartRequestedWhileStopping = false
        instance = this
        Log.d(TAG, "Foreground Service Created")
        // Created here rather than in Application.onCreate: this service is the only thing that posts
        // on the channel, and creating it on every app start was a binder round trip on the way to the
        // first frame — for users who never connect at all, too.
        createNotificationChannel()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        avatarCache = AvatarCache(applicationContext.cacheDir)
        audioBridge = AudioBridge(applicationContext, tsClient)
        audioBridge.initialize()

        // Load saved floating window position
        loadSavedPosition()

        // Reconnect the moment the platform hands us a different network. Failure to
        // register is not fatal: the keepalive timeout still recovers the session.
        try {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed; falling back to timeout reconnect", e)
        }

        // Listen for audio events, talk status, and play per-user mixing
        tsClient.events.onEach { event ->
            when (event.type) {
                "audio_received" -> {
                    val userId = (event.data["user_id"] as? Number)?.toInt() ?: return@onEach
                    val data = event.data["data"]
                    if (data is ByteArray) {
                        audioBridge.playAudio(userId, data)
                    } else if (data is Array<*>) {
                        val bytes = ByteArray(data.size) { (data[it] as? Number)?.toByte() ?: 0 }
                        audioBridge.playAudio(userId, bytes)
                    }
                }
                "talk_status_start" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null) {
                        // Cancel any pending speaker stop
                        speakerUpdateJob?.cancel()
                        pendingSpeakerId = speakerId
                        
                        // Delay speaker update to avoid flickering
                        speakerUpdateJob = serviceScope.launch {
                            delay(SPEAKER_DELAY_MS)
                            // Only update if still the pending speaker
                            if (pendingSpeakerId == speakerId) {
                                overlayActiveSpeakerId = speakerId
                                val speakerUser = tsClient.users.value.find { it.id == speakerId }
                                val uid = speakerUser?.uid
                                if (!uid.isNullOrEmpty()) {
                                    serviceScope.launch(Dispatchers.IO) {
                                        // Force refresh speaker avatar
                                        avatarCache.clearMemoryCache(uid)
                                        avatarCache.loadAvatar(uid, tsClient)
                                        val avatar = avatarCache.getAvatar(uid)
                                        withContext(Dispatchers.Main) {
                                            if (overlayActiveSpeakerId == speakerId) {
                                                overlayActiveSpeakerAvatar = avatar
                                            }
                                        }
                                    }
                                } else {
                                    overlayActiveSpeakerAvatar = null
                                }
                            }
                        }
                    }
                }
                "talk_status_stop" -> {
                    val speakerId = (event.data["user_id"] as? Number)?.toInt()
                    if (speakerId != null && speakerId == overlayActiveSpeakerId) {
                        // Use delayed mechanism for speaker state changes
                        pendingSpeakerId = null
                        speakerUpdateJob?.cancel()
                        speakerUpdateJob = serviceScope.launch {
                            delay(SPEAKER_DELAY_MS)
                            // Only update if still no pending speaker
                            if (pendingSpeakerId == null) {
                                overlayActiveSpeakerId = null
                                overlayActiveSpeakerAvatar = null
                            }
                        }
                    }
                }
            }
        }.launchIn(serviceScope)

        tsClient.state.onEach { state ->
            overlayConnected = state == dev.tslib.ConnectionState.CONNECTED
            if (state == dev.tslib.ConnectionState.CONNECTED) {
            }
            updateOverlayChannelName()
            updateNotification()
            // A session that drops while the window is up has to take it away, and one that comes back
            // (after an automatic reconnect) has to bring it back.
            updateOverlayVisibility()
        }.launchIn(serviceScope)

        tsClient.users.onEach {
            updateOverlayChannelName()
        }.launchIn(serviceScope)

        tsClient.channels.onEach {
            updateOverlayChannelName()
        }.launchIn(serviceScope)

        // Messages that arrive while the app is away become a badge on the bubble; the count is
        // cleared the moment the app comes back to the front.
        tsClient.events.onEach { event ->
            if (event.type != "text_message") return@onEach
            val senderId = (event.data["sender_id"] as? Number)?.toInt()
            if (senderId != null && senderId == tsClient.clientId) return@onEach
            overlayUnread += 1
        }.launchIn(serviceScope)

        // The setting can change while a session runs, and it changes what the window does.
        SettingsStore(this).enableFloatingWindow.onEach { enabled ->
            overlayEnabled = enabled
            updateOverlayVisibility()
        }.launchIn(serviceScope)
        
        // Listen to local voice activity and apply delay mechanism
        audioBridge.isLocalVoiceActive.onEach { isSpeaking ->
            // Cancel any pending local speaking state change
            localSpeakingJob?.cancel()
            pendingLocalSpeaking = isSpeaking
            
            // Delay local speaking state update to avoid flickering
            localSpeakingJob = serviceScope.launch {
                delay(SPEAKER_DELAY_MS)
                // Only update if still the pending state
                if (pendingLocalSpeaking == isSpeaking) {
                    delayedLocalSpeaking = isSpeaking
                    
                    // Force refresh local user avatar when speaking starts
                    if (isSpeaking) {
                        val myId = tsClient.clientId
                        val localUser = tsClient.users.value.find { it.id == myId }
                        val localUid = localUser?.uid
                        if (!localUid.isNullOrEmpty()) {
                            serviceScope.launch(Dispatchers.IO) {
                                // Force refresh local avatar
                                avatarCache.clearMemoryCache(localUid)
                                avatarCache.loadAvatar(localUid, tsClient)
                                val avatar = avatarCache.getAvatar(localUid)
                                withContext(Dispatchers.Main) {
                                    if (overlayActiveSpeakerId == myId) {
                                        overlayActiveSpeakerAvatar = avatar
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.launchIn(serviceScope)
        
        // Periodic avatar refresh — force re-download ALL user avatars to keep them fresh
        serviceScope.launch {
            while (true) {
                delay(AVATAR_REFRESH_INTERVAL_MS)
                val myId = tsClient.clientId
                if (myId == null) continue
                
                val currentUsers = tsClient.users.value
                val currentChannelId = currentUsers.find { it.id == myId }?.channelId
                val channelUsers = currentUsers.filter { it.channelId == currentChannelId }
                
                // Collect all UIDs in the current channel
                val uids = channelUsers.mapNotNull { it.uid }.filter { it.isNotEmpty() }.toList()
                if (uids.isEmpty()) continue
                
                serviceScope.launch(Dispatchers.IO) {
                    // Clear memory cache for all channel users to force re-download
                    avatarCache.clearMemoryCache(*uids.toTypedArray())
                    
                    // Re-download all avatars
                    for (uid in uids) {
                        avatarCache.loadAvatar(uid, tsClient)
                    }
                    
                    // Update the current speaker avatar if someone is speaking
                    val currentSpeakerId = overlayActiveSpeakerId
                    if (currentSpeakerId != null) {
                        val speakerUser = currentUsers.find { it.id == currentSpeakerId }
                        val speakerUid = speakerUser?.uid
                        if (!speakerUid.isNullOrEmpty()) {
                            val updatedAvatar = avatarCache.getAvatar(speakerUid)
                            withContext(Dispatchers.Main) {
                                if (overlayActiveSpeakerId == currentSpeakerId) {
                                    overlayActiveSpeakerAvatar = updatedAvatar
                                }
                            }
                        }
                    }
                }
            }
        }

        startMicWatchdog()
        startConnectionWatchdog()

        // Keep the auto-reconnect preference cached (see maybeStartAutoReconnect).
        BookmarkStore(this).autoReconnect
            .onEach { autoReconnectEnabled = it }
            .launchIn(serviceScope)

        // Surface microphone trouble in the notification while the app is in the background.
        audioBridge.micStatus
            .onEach { if (tsClient.isConnected || _reconnect.value.active) updateNotification() }
            .launchIn(serviceScope)
    }

    /**
     * Watches for an unexpected connection loss and drives the automatic
     * reconnection loop. A mid-session drop used to leave the user sitting in the
     * channel with no audio at all: sendAudio() was silently dropped while the
     * microphone kept working locally, so nothing ever recovered the session.
     */
    private fun startConnectionWatchdog() {
        tsClient.state
            .onEach { state ->
                if (state == ConnectionState.DISCONNECTED) {
                    maybeStartAutoReconnect()
                }
            }
            .launchIn(serviceScope)
    }

    private fun maybeStartAutoReconnect() {
        val params = sessionParams ?: return
        if (isStopping || isIntentionalDisconnect) return
        if (reconnectJob?.isActive == true) return
        if (!autoReconnectEnabled) {
            Log.i(TAG, "Connection lost; automatic reconnection is disabled in settings")
            return
        }
        Log.i(TAG, "Connection lost; scheduling automatic reconnection to ${params.address}")
        // Announce synchronously with the state change so the UI keeps the user on
        // the server screen instead of navigating back to the connection screen.
        _reconnect.value = ReconnectStatus(active = true)
        val channelId = currentChannelId()
        reconnectJob = serviceScope.launch { runAutoReconnect(params, channelId) }
    }

    /**
     * A WiFi↔cellular handover kills the UDP session, but the client only notices
     * when its keepalive times out — several seconds of silence. Reconnecting as
     * soon as the platform reports a usable network turns that into a blip.
     */
    private fun forceImmediateReconnect() {
        val params = sessionParams ?: return
        if (isStopping || isIntentionalDisconnect) return
        if (!autoReconnectEnabled) return
        if (reconnectJob?.isActive == true) return
        // A handover fires several callbacks (one per network); collapse them.
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkSwitchReconnectAt < NETWORK_SWITCH_DEBOUNCE_MS) return
        lastNetworkSwitchReconnectAt = now

        Log.i(TAG, "Network changed; forcing an immediate reconnect to ${params.address}")
        _reconnect.value = ReconnectStatus(active = true)
        val channelId = currentChannelId()
        serviceScope.launch {
            // Drop the dead socket first so the new attempt is not competing with it.
            // That also flips the state to DISCONNECTED, which normally starts the
            // loop through maybeStartAutoReconnect(); starting it here covers the case
            // where the state did not change because the socket still looked alive.
            runCatching { tsClient.disconnect() }
            if (reconnectJob?.isActive != true) {
                reconnectJob = launch { runAutoReconnect(params, channelId, immediateFirstAttempt = true) }
            }
        }
    }

    private fun currentChannelId(): Long? {
        val myId = tsClient.clientId ?: return null
        return tsClient.users.value.find { it.id == myId }?.channelId
    }

    /** Put the user back into the channel they were in before the connection dropped. */
    private fun restoreChannelAfterReconnect(channelId: Long?) {
        if (channelId == null) return
        serviceScope.launch {
            // Let the new session settle before moving.
            delay(RECONNECT_CHANNEL_RESTORE_DELAY_MS)
            if (!tsClient.isConnected || isStopping) return@launch
            val current = currentChannelId()
            if (current != channelId) {
                Log.i(TAG, "Restoring channel $channelId after reconnect (currently $current)")
                tsClient.moveToChannel(channelId)
            }
        }
    }

    private suspend fun runAutoReconnect(
        params: SessionParams,
        channelId: Long?,
        immediateFirstAttempt: Boolean = false,
    ) {
        Log.i(TAG, "Auto-reconnect loop started (address=${params.address})")
        // Offline: no point capturing, and let the CPU sleep between attempts.
        audioBridge.stopCapture()
        releaseWakeLock()
        var attempt = 0
        var delayMs = RECONNECT_FIRST_DELAY_MS
        try {
            while (true) {
                attempt++
                _reconnect.value = ReconnectStatus(
                    active = true,
                    attempt = attempt,
                    nextRetrySeconds = (delayMs / 1000).toInt(),
                )
                updateNotification()
                // A network handover already told us the old socket is dead, so the
                // first attempt goes out at once; the backoff only applies after a
                // failed try.
                if (attempt > 1 || !immediateFirstAttempt) {
                    delay(delayMs)
                }
                if (isStopping || isIntentionalDisconnect) break

                Log.i(TAG, "Auto-reconnect attempt $attempt to ${params.address}")
                // Keep the CPU awake for the attempt itself; connectInternal releases
                // the lock again if the attempt fails.
                acquireWakeLock()
                val failure = connectInternal(
                    address = params.address,
                    identity = params.identity,
                    nickname = params.nickname,
                    password = params.password,
                    isReconnect = true,
                )
                if (failure == null) {
                    Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                    restoreChannelAfterReconnect(channelId)
                    break
                }
                Log.w(TAG, "Auto-reconnect attempt $attempt failed: ${failure.message}")
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        } finally {
            _reconnect.value = ReconnectStatus()
            reconnectJob = null
            updateNotification()
            Log.i(TAG, "Auto-reconnect loop finished")
        }
    }

    // Microphone watchdog: a connected voice session must always be capturing.
    // If capture ever stops while we are connected (and not on our way out),
    // bring it back instead of leaving the user silently unheard.
    private fun startMicWatchdog() {
        serviceScope.launch {
            while (true) {
                delay(MIC_WATCHDOG_INTERVAL_MS)
                if (isStopping || !tsClient.isConnected) continue
                if (overlayTalking &&
                    SystemClock.elapsedRealtime() - overlayTalkStartedAt > MAX_TALK_HOLD_MS
                ) {
                    Log.w(TAG, "Push-to-talk hold outlived its release; closing the microphone")
                    stopOverlayTalk()
                }
                if (!audioBridge.isCapturing.value) {
                    Log.w(TAG, "Capture is not running while connected; restarting it")
                    audioBridge.startCapture(noiseSuppressionSetting())
                }
            }
        }
    }

    // ── Push to talk from the bubble ─────────────────────────────────────────

    /**
     * Opens the microphone for a hold on the bubble.
     *
     * Deliberately small and synchronous: the bubble's ring switches to "live" on this call, so
     * what the user sees is the microphone's actual state and not a hope about it.
     */
    private fun startOverlayTalk() {
        overlayTalkStartedAt = SystemClock.elapsedRealtime()
        overlayTalking = true
        audioBridge.setMuted(false)
    }

    /**
     * Closes it again. Idempotent, and it only ever touches a microphone this hold opened — a
     * release that arrives twice, or after the window has already been torn down, must not mute
     * a user who is transmitting in voice-activity mode.
     */
    private fun stopOverlayTalk() {
        if (!overlayTalking) return
        overlayTalking = false
        audioBridge.setMuted(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            Log.d(TAG, "Ignoring sticky restart without an explicit intent")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        latestStartId = startId
        startServiceForeground()

        if (isStopping) {
            if (intent.action != ACTION_DISCONNECT) {
                Log.d(TAG, "Start requested while service is stopping; will reopen after disconnect completes")
                restartRequestedWhileStopping = true
            }
            return START_NOT_STICKY
        }

        if (instance == null) {
            instance = this
        }
        
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_TOGGLE_MUTE -> {
                audioBridge.toggleMute()
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Binding is not used: the UI reaches this service through [instance] and the state
     * flows exposed by [tsClient]. This override exists only because Service declares
     * onBind abstract, so it deliberately reports that no binder is available.
     */
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val muteIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TsConnectionService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val serverName = tsClient.serverInfo.value?.name ?: getString(R.string.connecting)
        val reconnect = _reconnect.value
        val statusText = when {
            reconnect.active ->
                getString(R.string.reconnect_notification, maxOf(reconnect.attempt, 1))
            audioBridge.micStatus.value == AudioBridge.MicStatus.UNAVAILABLE ->
                getString(R.string.mic_unavailable_short)
            else -> serverName
        }
        val muteLabel = getString(if (audioBridge.isMuted.value) R.string.notif_unmute else R.string.notif_mute)

        return NotificationCompat.Builder(this, TsDroidApp.CHANNEL_ID_CONNECTION)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, muteLabel, muteIntent)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .build()
    }

    /**
     * The notification channel this service's foreground notification is posted on.
     *
     * `createNotificationChannel` is idempotent, so this costs nothing after the first start.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TsDroidApp.CHANNEL_ID_CONNECTION,
            getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_connection_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun hasActiveConnection(address: String? = null): Boolean {
        return !isStopping &&
            tsClient.isConnected &&
            (address == null || tsClient.serverAddress == address)
    }

    suspend fun connect(address: String, identity: Identity, nickname: String, password: String?): Throwable? {
        if (isStopping) {
            return IllegalStateException("Connection service is still stopping. Please try again.")
        }

        // A manual connection supersedes any pending automatic retry.
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnect.value = ReconnectStatus()
        sessionParams = null

        isIntentionalDisconnect = false
        val failure = connectInternal(address, identity, nickname, password, isReconnect = false)
        if (failure == null) {
            sessionParams = SessionParams(address, identity, nickname, password)
        }
        return failure
    }

    private suspend fun connectInternal(
        address: String,
        identity: Identity,
        nickname: String,
        password: String?,
        isReconnect: Boolean,
    ): Throwable? {
        return try {
            tsClient.connect(address, identity, nickname, password)
            acquireWakeLock()
            audioBridge.startCapture(noiseSuppressionSetting())
            // Sync initial mute state with server
            if (audioBridge.isMuted.value) {
                tsClient.setInputMuted(true)
            }
            // Start event loop
            tsClient.startEventLoop()
            null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Connection error", e)
            if (isReconnect) {
                // Keep the foreground service (and its retry loop) alive while offline.
                releaseWakeLock()
                audioBridge.stopCapture()
            } else {
                cleanupFailedConnection()
            }
            e
        }
    }

    fun disconnect() {
        disconnectAndStop()
    }

    private suspend fun noiseSuppressionSetting(): Boolean =
        runCatching { SettingsStore(this).noiseSuppression.first() }.getOrDefault(true)

    /**
     * A live voice connection must keep the CPU awake; without it some devices
     * suspend the capture path soon after the screen turns off.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TS6Droid:connection")
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
            Log.i(TAG, "Partial wake lock acquired")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
    }

    private fun disconnectAndStop() {
        if (isStopping) return
        val stopStartId = latestStartId
        prepareToStop()
        isIntentionalDisconnect = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                tsClient.disconnect()
            } finally {
                withContext(Dispatchers.Main) {
                    finishStopOrRestart(stopStartId)
                }
            }
        }
    }

    private fun prepareToStop() {
        isStopping = true
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnect.value = ReconnectStatus()
        sessionParams = null
        if (instance == this) {
            instance = null
        }
        unregisterNetworkCallback()
        hideFloatingWindow()
        audioBridge.stopCapture()
        releaseWakeLock()
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Never registered, or the platform already dropped it.
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        super.onDestroy()
    }

    private fun cleanupFailedConnection() {
        hideFloatingWindow()
        audioBridge.stopCapture()
        releaseWakeLock()
        isStopping = false
        restartRequestedWhileStopping = false
        instance = this
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun finishStopOrRestart(stopStartId: Int) {
        if (restartRequestedWhileStopping || latestStartId != stopStartId) {
            restartRequestedWhileStopping = false
            isStopping = false
            instance = this
            updateNotification()
            return
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopStartId != 0) {
            stopSelf(stopStartId)
        } else {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed; disconnecting foreground TS session")
        disconnectAndStop()
        super.onTaskRemoved(rootIntent)
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    // ── Floating window ──────────────────────────────────────────────────────────────

    /**
     * The Activity reports whether it is on screen. The floating window is for the times it is *not*,
     * so this — together with the setting, the overlay permission and a live connection — is the only
     * thing that decides whether it shows. Nothing else shows or hides it any more.
     */
    fun setAppInForeground(foreground: Boolean) {
        if (appInForeground == foreground) return
        appInForeground = foreground
        // The badge counts what arrived while the user was away.
        if (foreground) overlayUnread = 0
        updateOverlayVisibility()
    }

    private fun updateOverlayVisibility() {
        val wanted = !appInForeground &&
            overlayEnabled &&
            hasOverlayPermission() &&
            !isStopping &&
            tsClient.isConnected
        if (wanted) showFloatingWindow() else hideFloatingWindow()
    }

    fun showFloatingWindow() {
        if (overlayView != null) return
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Cannot show floating window because overlay permission is missing")
            return
        }

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
            x = lastSavedX
            y = lastSavedY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The window positions itself by hand: system-bar insets must not push it around.
                fitInsetsTypes = 0
            }
        }

        val strings = overlayStringsContext()
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TsConnectionService)
            setViewTreeViewModelStoreOwner(this@TsConnectionService)
            setViewTreeSavedStateRegistryOwner(this@TsConnectionService)

            setContent {
                val channels by tsClient.channels.collectAsStateWithLifecycle()
                val users by tsClient.users.collectAsStateWithLifecycle()
                val serverInfo by tsClient.serverInfo.collectAsStateWithLifecycle()
                val isMicMuted by audioBridge.isMuted.collectAsStateWithLifecycle()
                val isOutputMuted by audioBridge.isOutputMuted.collectAsStateWithLifecycle()
                val reconnect by _reconnect.collectAsStateWithLifecycle()
                val micStatus by audioBridge.micStatus.collectAsStateWithLifecycle()
                // Not read here on purpose: the level meter reads it in its draw lambda, so a
                // moving needle does not recompose the member list behind it.
                val inputLevel = audioBridge.inputLevel.collectAsStateWithLifecycle()

                // The app's own theme, so the window does not look like a different product.
                TsDroidTheme(seedColor = AnimeWallpaperState.dominantColor.value) {
                    CompositionLocalProvider(LocalContext provides strings) {
                        FloatingOverlay(
                            state = overlayState(
                                channels = channels,
                                users = users,
                                serverName = serverInfo?.name,
                                micMuted = isMicMuted,
                                outputMuted = isOutputMuted,
                                reconnecting = reconnect.active,
                                reconnectAttempt = reconnect.attempt,
                                micUnavailable = micStatus == AudioBridge.MicStatus.UNAVAILABLE,
                                talking = overlayTalking,
                                micReady = micStatus == AudioBridge.MicStatus.RUNNING,
                            ),
                            inputLevel = inputLevel,
                            expanded = isOverlayExpanded,
                            view = overlayList,
                            onViewChange = { overlayList = it },
                            onToggleExpand = { toggleOverlayExpanded() },
                            onDrag = { dx, dy -> moveOverlayBy(dx, dy) },
                            onDragEnd = { snapOverlayToEdge() },
                            onSizeChange = { width, height -> onOverlaySizeChanged(width, height) },
                            onToggleMic = { audioBridge.toggleMute() },
                            onToggleOutput = { audioBridge.toggleOutputMute() },
                            // Holding the bubble talks: the app's own push-to-talk behaviour, which the
                            // bubble only offers while the microphone starts out closed. Going through
                            // the service keeps the microphone's state and the bubble's "live" ring in
                            // step, and lets a window that disappears mid-hold close the microphone.
                            onTalkStart = { startOverlayTalk() },
                            onTalkStop = { stopOverlayTalk() },
                            onOpenApp = { openAppFromOverlay() },
                            onMoveToChannel = { channelId ->
                                serviceScope.launch { runCatching { tsClient.moveToChannel(channelId) } }
                            },
                            onDisconnect = { disconnect() },
                        )
                    }
                }
            }
        }

        overlayView = composeView
        overlayLayoutParams = params
        try {
            windowManager.addView(composeView, params)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to add the floating window", e)
            overlayView = null
            overlayLayoutParams = null
        }
    }

    /** One snapshot of everything the window draws. */
    private fun overlayState(
        channels: List<Channel>,
        users: List<User>,
        serverName: String?,
        micMuted: Boolean,
        outputMuted: Boolean,
        reconnecting: Boolean,
        reconnectAttempt: Int,
        micUnavailable: Boolean,
        talking: Boolean,
        micReady: Boolean,
    ): FloatingOverlayState {
        val myId = tsClient.clientId
        val myUser = users.find { it.id == myId }
        val myChannelId = myUser?.channelId ?: 0L
        val speakerId = overlayActiveSpeakerId
        val localSpeaking = delayedLocalSpeaking || speakerId == myId

        return FloatingOverlayState(
            connected = overlayConnected,
            serverName = serverName,
            channelName = overlayChannelName,
            members = users
                .filter { it.channelId == myChannelId && it.clientType.toInt() == 0 }
                .map { user ->
                    FloatingOverlayMember(
                        id = user.id,
                        nickname = user.nickname,
                        avatar = user.uid?.takeIf { it.isNotEmpty() }?.let { avatarCache.getAvatar(it) },
                        speaking = if (user.id == myId) localSpeaking else user.id == speakerId,
                        micMuted = user.isInputMuted,
                        outputMuted = user.isOutputMuted,
                    )
                },
            channels = channels.map { FloatingOverlayChannel(it.id, it.name) },
            currentChannelId = myChannelId,
            localAvatar = myUser?.uid?.takeIf { it.isNotEmpty() }?.let { avatarCache.getAvatar(it) },
            speakingAvatar = overlayActiveSpeakerAvatar,
            speakingIsLocal = speakerId != null && speakerId == myId,
            localSpeaking = localSpeaking,
            remoteSpeaking = speakerId != null && speakerId != myId,
            talking = talking,
            micReady = micReady,
            micMuted = micMuted,
            outputMuted = outputMuted,
            reconnecting = reconnecting,
            reconnectAttempt = reconnectAttempt,
            micUnavailable = micUnavailable,
            unread = overlayUnread,
        )
    }

    private fun updateOverlayChannelName() {
        val myId = tsClient.clientId ?: return
        val currentChannelId = tsClient.users.value.find { it.id == myId }?.channelId
        overlayChannelName = currentChannelId?.let { channelId ->
            tsClient.channels.value.find { it.id == channelId }?.name
        }
    }

    /**
     * Moves the window by one drag delta, clamped to the screen.
     *
     * A window is positioned in whole pixels, and a deliberately slow drag moves the finger by less
     * than one of them per event. Truncating each delta on its own (which is what this did) threw
     * that movement away event after event, so the bubble simply refused to follow a careful drag;
     * the leftover fraction is carried to the next delta instead.
     */
    private fun moveOverlayBy(dx: Float, dy: Float) {
        val layout = overlayLayoutParams ?: return
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
        val pendingX = dx + overlayDragRemainderX
        val pendingY = dy + overlayDragRemainderY
        val stepX = pendingX.toInt()
        val stepY = pendingY.toInt()
        overlayDragRemainderX = pendingX - stepX
        overlayDragRemainderY = pendingY - stepY
        layout.x = (layout.x + stepX).coerceIn(0, maxX)
        layout.y = (layout.y + stepY).coerceIn(0, maxY)
        lastSavedX = layout.x
        lastSavedY = layout.y
        try {
            windowManager.updateViewLayout(view, layout)
        } catch (_: Exception) {
        }
    }

    /**
     * Docks the window to the nearer side edge once a drag ends — what every floating bubble does, and
     * what keeps it out of the way of whatever is underneath.
     *
     * Snapping is instant on purpose: moving a window frame by frame from an animation was one more
     * way to touch the WindowManager mid-frame, and it has to survive being dragged first.
     */
    private fun snapOverlayToEdge() {
        val layout = overlayLayoutParams ?: return
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        val margin = (EDGE_MARGIN_DP * metrics.density).toInt()
        layout.x = if (layout.x + view.width / 2 < metrics.widthPixels / 2) {
            margin
        } else {
            (metrics.widthPixels - view.width - margin).coerceAtLeast(margin)
        }
        layout.y = layout.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
        lastSavedX = layout.x
        lastSavedY = layout.y
        try {
            windowManager.updateViewLayout(view, layout)
        } catch (_: Exception) {
        }
        saveCachedPosition()
    }

    /**
     * Expanding and collapsing changes the window's size, which only lands after the next layout —
     * and, since the content animates its size, over the whole [REVEAL_ANIMATION_MS] that follows.
     *
     * The anchor captured here is therefore applied on *every* size change until the animation window
     * closes, so the docked edge stays put while the window grows or shrinks. It is applied from
     * [onOverlaySizeChanged], posted, never inline: a window must not be moved from inside a layout
     * callback, and the service's coroutine scope has no frame clock — waiting for a frame with
     * `withFrameNanos` throws and takes the process with it.
     */
    private fun toggleOverlayExpanded() {
        val layout = overlayLayoutParams
        val view = overlayView
        if (layout != null && view != null) {
            overlayToggleAnchorX = layout.x
            overlayToggleAnchorWidth = view.width
            overlayToggleDockedRight =
                layout.x + view.width / 2 >= resources.displayMetrics.widthPixels / 2
            overlayAnchorPending = true
            // A little longer than the content animation, so the last frame is anchored too.
            view.postDelayed({ overlayAnchorPending = false }, REVEAL_ANIMATION_MS + 150L)
        }
        isOverlayExpanded = !isOverlayExpanded
    }

    private fun applyOverlayToggleAnchor() {
        val layout = overlayLayoutParams ?: return
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        val width = view.width
        val height = view.height
        layout.x = if (overlayToggleDockedRight) {
            overlayToggleAnchorX + overlayToggleAnchorWidth - width
        } else {
            overlayToggleAnchorX
        }
        layout.x = layout.x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        lastSavedX = layout.x
        lastSavedY = layout.y
        try {
            windowManager.updateViewLayout(view, layout)
        } catch (_: Exception) {
        }
    }

    /**
     * Only records the size — and, while an expand/collapse is pending, applies its anchor *posted*.
     *
     * This is called from the layout pass: calling `updateViewLayout` from here is what killed the
     * process as soon as the bubble was dragged (a drag and a size change land in the same frame).
     * `View.post` is the safe way out of that callback.
     */
    private fun onOverlaySizeChanged(width: Int, height: Int) {
        overlayWidth = width
        overlayHeight = height
        // While a reveal is animating, the window has to be re-anchored for every frame of it.
        if (overlayAnchorPending) {
            overlayView?.post { applyOverlayToggleAnchor() }
        }
    }

    /** Brings the app to the front from the overlay: the bubble's long press, or the header's ⌂. */
    private fun openAppFromOverlay() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Could not open the app from the overlay", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = overlayView ?: return
        // Rotation must not leave the window half off screen, and the re-dock is posted for the same
        // reason as the toggle anchor: this callback can arrive mid-layout, and the scope has no frame
        // clock to wait on.
        overlayWidth = 0
        view.post {
            snapOverlayToEdge()
            clampOverlayIntoBounds()
        }
    }

    private fun clampOverlayIntoBounds() {
        val layout = overlayLayoutParams ?: return
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        layout.x = layout.x.coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(view, layout)
        } catch (_: Exception) {
        }
    }

    /**
     * A context with the app's chosen language applied.
     *
     * The service's own resources follow the system locale, while the rest of the app runs in the
     * language picked in settings (the Activity applies it in `attachBaseContext`) — without this the
     * floating window would be the one surface left in the wrong language.
     */
    private fun overlayStringsContext(): Context {
        val languageTag = runCatching {
            runBlocking { SettingsStore(this@TsConnectionService).language.first() }
        }.getOrDefault("zh")
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = resources.configuration
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return createConfigurationContext(configuration)
    }

    fun hideFloatingWindow() {
        // The bubble's hold opens the microphone, and a window that goes away takes the finger's
        // release event with it. Closing here as well means the microphone can never be left open
        // by a hold nobody is holding any more.
        stopOverlayTalk()
        overlayView?.let { view ->
            try {
                // Save the current position before removing the view.
                overlayLayoutParams?.let { params ->
                    lastSavedX = params.x
                    lastSavedY = params.y
                    saveCachedPosition()
                }
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayLayoutParams = null
        overlayWidth = 0
        overlayHeight = 0
        // The next time it appears it should be the bubble again, not a panel the user left open.
        isOverlayExpanded = false
    }

    /** Remembered per orientation: a docked spot in portrait makes no sense in landscape. */
    private fun saveCachedPosition() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt(if (landscape) "position_x_land" else "position_x", lastSavedX)
            .putInt(if (landscape) "position_y_land" else "position_y", lastSavedY)
            .apply()
    }

    private fun loadSavedPosition() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE)
        lastSavedX = prefs.getInt(
            if (landscape) "position_x_land" else "position_x",
            if (landscape) 100 else 100,
        )
        lastSavedY = prefs.getInt(
            if (landscape) "position_y_land" else "position_y",
            if (landscape) 100 else 300,
        )
    }
}
