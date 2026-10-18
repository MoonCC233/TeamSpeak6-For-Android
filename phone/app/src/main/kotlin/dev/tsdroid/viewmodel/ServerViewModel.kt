package dev.tsdroid.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import dev.tsdroid.bridge.AvatarCache
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.FileCache
import dev.tsdroid.bridge.IconCache
import dev.tsdroid.bridge.MicMode
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.bridge.TsFileEntry
import dev.tsdroid.bridge.VadGate
import dev.tsdroid.han.R
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.MessageStore
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.data.PrivateConversations
import dev.tsdroid.data.UserAudioStore
import dev.tsdroid.service.ReconnectStatus
import dev.tsdroid.service.TsConnectionService
import dev.tslib.Channel
import dev.tslib.ConnectionState
import dev.tslib.Event
import dev.tslib.ServerInfo
import dev.tslib.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileAttachment(
    val fileName: String,
    val fileSize: Long,
    val fileId: String,
    val isImage: Boolean,
    val channelId: Long = 0L,
)

data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean = false,
    val isPrivate: Boolean = false,
    val senderId: Int = 0,
    val fileAttachment: FileAttachment? = null,
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data class Done(val image: ImageBitmap?, val fileUri: android.net.Uri? = null) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ServerViewModel"
        private const val PENDING_ECHO_WINDOW_MS = 5000L
    }

    /**
     * Own messages are echoed locally before the server confirms them. The
     * server echo marks the pending entry confirmed (accepted), so a command
     * error rolls back only still-unconfirmed echoes — failures of unrelated
     * commands (channel moves etc.) can no longer delete an accepted message.
     */
    private class PendingEcho(val target: String, val peerKey: String?, val text: String, val at: Long) {
        var confirmed = false
    }
    private val pendingOwnEchoes = ArrayDeque<PendingEcho>()

    /**
     * Stable conversation key for a peer. Private conversations are keyed by the
     * peer's permanent uid rather than the session client id, because a client id
     * is reassigned on every reconnect — keying by it used to split one person
     * into several conversation tabs each time the connection dropped.
     *
     * Falls back to [PrivateConversations.CLID_PREFIX] + client id while the peer's
     * uid is still unknown, so an early message is never dropped.
     */
    private fun peerKeyForClientId(clientId: Int): String =
        _rawUsers.value.firstOrNull { it.id == clientId }?.uid?.takeIf { it.isNotBlank() }
            ?: "${PrivateConversations.CLID_PREFIX}$clientId"

    /** The peer's *current* client id for a conversation key, or null while offline. */
    private fun clientIdForPeerKey(peerKey: String): Int? =
        PrivateConversations.clientIdOf(peerKey)
            ?: _rawUsers.value.firstOrNull { it.uid == peerKey }?.id

    /**
     * One-time fold of a conversation that was persisted under a session client id
     * before conversations became uid-keyed. The sender nickname is the only link
     * that history still has to the peer's identity, so it is used to move those
     * messages into the uid conversation. Without this an upgrade would show the
     * same person twice: their old `clid:` tab next to their new one.
     *
     * Only folds when every message in the legacy conversation came from that same
     * nickname, so an ambiguous match is left alone rather than merged wrongly.
     */
    private fun foldLegacyConversation(peerKey: String, nickname: String?) {
        if (PrivateConversations.isLegacy(peerKey) || nickname.isNullOrBlank()) return
        val current = _privateMessages.value
        val match = current.keys.firstOrNull { key ->
            PrivateConversations.isLegacy(key) &&
                current[key].orEmpty().let { msgs ->
                    msgs.isNotEmpty() && msgs.filter { !it.isMe }.all { it.sender == nickname }
                }
        } ?: return
        mergeConversation(match, peerKey)
        Log.i(TAG, "Folded legacy client-id conversation into the uid-keyed one")
    }

    /** Moves every message (and the unread badge) from [from] into [into]. */
    private fun mergeConversation(from: String, into: String) {
        val current = _privateMessages.value
        val merged = (current[from].orEmpty() + current[into].orEmpty()).sortedBy { it.timestamp }
        _privateMessages.value = current.toMutableMap().apply {
            remove(from)
            put(into, merged)
        }
        val unread = _unreadPrivate.value.toMutableMap()
        val carried = unread.remove(from) ?: 0
        if (carried > 0) unread[into] = (unread[into] ?: 0) + carried
        _unreadPrivate.value = unread
    }

    /**
     * Maps a conversation key to a currently reachable peer, re-keying a legacy
     * client-id conversation onto the peer's uid when the nickname stored with the
     * history identifies them.
     *
     * A legacy key is stale by definition (client ids are reassigned on every
     * connect), so sending straight to the stored number could deliver a private
     * message to a stranger who now holds that id. Returning null instead makes the
     * caller drop the send.
     */
    private fun resolvePeerKey(peerKey: String): String? {
        if (!PrivateConversations.isLegacy(peerKey)) {
            return if (clientIdForPeerKey(peerKey) != null) peerKey else null
        }
        val nickname = _privateMessages.value[peerKey]
            ?.lastOrNull { !it.isMe }
            ?.sender
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val uid = _rawUsers.value.firstOrNull { it.nickname == nickname }
            ?.uid
            ?.takeIf { it.isNotBlank() }
            ?: return null
        mergeConversation(peerKey, uid)
        Log.i(TAG, "Re-keyed a legacy client-id conversation onto the peer's uid")
        return uid
    }

    private fun confirmPendingEcho(text: String) {
        val idx = pendingOwnEchoes.indexOfFirst { !it.confirmed && it.text == text }
        if (idx >= 0) pendingOwnEchoes[idx].confirmed = true
    }

    private fun rollbackLastPendingEcho() {
        val now = System.currentTimeMillis()
        while (pendingOwnEchoes.isNotEmpty() && now - pendingOwnEchoes.first().at > PENDING_ECHO_WINDOW_MS) {
            pendingOwnEchoes.removeFirst()
        }
        val idx = pendingOwnEchoes.indexOfFirst { !it.confirmed }
        if (idx < 0) return
        val pending = pendingOwnEchoes.removeAt(idx)
        if (pending.target == "channel") {
            val list = _channelMessages.value.toMutableList()
            val idx = list.indexOfFirst { it.isMe && it.text == pending.text && it.timestamp >= pending.at }
            if (idx >= 0) {
                list.removeAt(idx)
                _channelMessages.value = list
            }
        } else {
            val peerKey = pending.peerKey ?: return
            val existing = _privateMessages.value[peerKey] ?: return
            val list = existing.toMutableList()
            val idx = list.indexOfFirst { it.isMe && it.text == pending.text && it.timestamp >= pending.at }
            if (idx >= 0) {
                list.removeAt(idx)
                _privateMessages.value = _privateMessages.value.toMutableMap().apply { put(peerKey, list) }
            }
        }
    }

    private val messageStore = MessageStore(application)
    private val bookmarkStore = BookmarkStore(application)
    private val settingsStore = SettingsStore(application)
    private val userAudioStore = UserAudioStore(application)
    private val iconCache = IconCache(application.cacheDir)
    private val avatarCache = AvatarCache(application.cacheDir)
    private val fileCache = FileCache(application)
    private var serverAddress: String? = null

    private val _serverAddressLabel = MutableStateFlow<String?>(null)

    /** The address of the connected server, for display only. */
    val serverAddressLabel: StateFlow<String?> = _serverAddressLabel.asStateFlow()
    private var saveJob: Job? = null
    // In-memory cache: avoids re-reading from disk + re-decoding for the same image
    private val downloadCache = mutableMapOf<String, StateFlow<DownloadState>>()

    private val _previewImageBitmap = MutableStateFlow<ImageBitmap?>(null)
    val previewImageBitmap: StateFlow<ImageBitmap?> = _previewImageBitmap.asStateFlow()
    private val _previewImageName = MutableStateFlow<String?>(null)
    val previewImageName: StateFlow<String?> = _previewImageName.asStateFlow()

    private var tsClient: TsClient? = null
    private var audioBridge: AudioBridge? = null
    private var connectionService: TsConnectionService? = null

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    // Raw users from the server (isTalking always false in snapshots)
    private val _rawUsers = MutableStateFlow<List<User>>(emptyList())

    // Set of currently talking user IDs (tracked via talk_status events)
    private val _talkingUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val talkingUserIds: StateFlow<Set<Int>> = _talkingUserIds.asStateFlow()
    
    // Track local mic state for local user talking highlight
    private val _isLocalTalking = MutableStateFlow(false)

    private val _mutedUserIds = MutableStateFlow<Set<Int>>(emptySet())
    val mutedUserIds: StateFlow<Set<Int>> = _mutedUserIds.asStateFlow()

    // Users with isTalking patched from talk status events
    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private val _channelIcons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val channelIcons: StateFlow<Map<Long, ImageBitmap>> = _channelIcons.asStateFlow()

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars.asStateFlow()

    private val _channelMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val channelMessages: StateFlow<List<ChatMessage>> = _channelMessages.asStateFlow()

    /** Private conversations keyed by the peer's permanent uid (see [peerKeyForClientId]). */
    private val _privateMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<ChatMessage>>> = _privateMessages.asStateFlow()

    // Separate PTT mode from actual mute state
    val micMode: StateFlow<MicMode> = settingsStore.micMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, MicMode.PTT)

    val vadThresholdDb: StateFlow<Float> = settingsStore.vadThresholdDb
        .stateIn(viewModelScope, SharingStarted.Eagerly, VadGate.DEFAULT_THRESHOLD_DB)

    /** true = push-to-talk (hold), false = tap to toggle. */
    val isPttMode: StateFlow<Boolean> = micMode
        .map { it == MicMode.PTT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Per-user volume (dB) for the current session, keyed by client id. */
    private val _userVolumes = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val userVolumes: StateFlow<Map<Int, Float>> = _userVolumes.asStateFlow()

    // Persisted per-user audio settings (keyed by permanent uid), mirrored
    // onto session client ids in syncUserAudio()
    private val volumeByUid = HashMap<String, Float>()
    private val loadedVolumeUids = mutableSetOf<String>()
    private var persistedMutedUids = emptySet<String>()
    private var userAudioAddress: String? = null

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    // Stable placeholder so collectors before bindToService() don't get a
    // brand-new flow instance on every access
    private val _voiceInactiveFlow = MutableStateFlow(false)
    val isLocalVoiceActive: StateFlow<Boolean> get() = audioBridge?.isLocalVoiceActive ?: _voiceInactiveFlow

    /** Automatic reconnection progress, so the UI can stay put instead of bouncing out. */
    private val _reconnectStatus = MutableStateFlow(ReconnectStatus())
    val reconnectStatus: StateFlow<ReconnectStatus> = _reconnectStatus.asStateFlow()

    /** Microphone health reported by the audio bridge (self-healing capture). */
    private val _micStatus = MutableStateFlow(AudioBridge.MicStatus.IDLE)
    val micStatus: StateFlow<AudioBridge.MicStatus> = _micStatus.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    // Unread message counters
    private val _unreadChannel = MutableStateFlow(0)
    val unreadChannel: StateFlow<Int> = _unreadChannel.asStateFlow()
    private val _unreadPrivate = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadPrivate: StateFlow<Map<String, Int>> = _unreadPrivate.asStateFlow()

    val audioGain: StateFlow<Float> = settingsStore.audioGain
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val micGain: StateFlow<Float> = settingsStore.micGain
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val showLinkThumbnails: StateFlow<Boolean> = settingsStore.showLinkThumbnails
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoLoadImages: StateFlow<Boolean> = settingsStore.autoLoadImages
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val enableFloatingWindow: StateFlow<Boolean> = settingsStore.enableFloatingWindow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val animeBackground: StateFlow<Boolean> = settingsStore.animeBackground
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val noiseSuppression: StateFlow<Boolean> = settingsStore.noiseSuppression
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // File manager state
    private val _fileManagerOpen = MutableStateFlow(false)
    val fileManagerOpen: StateFlow<Boolean> = _fileManagerOpen.asStateFlow()

    private val _fileList = MutableStateFlow<List<TsFileEntry>>(emptyList())
    val fileList: StateFlow<List<TsFileEntry>> = _fileList.asStateFlow()

    private val _currentFilePath = MutableStateFlow("/")
    val currentFilePath: StateFlow<String> = _currentFilePath.asStateFlow()

    private val _fileManagerLoading = MutableStateFlow(false)
    val fileManagerLoading: StateFlow<Boolean> = _fileManagerLoading.asStateFlow()

    /** Permission hints for the current channel (bitflags from Channel.PERM_*) */
    val currentChannelPermissions: StateFlow<Long> = combine(_channels, _rawUsers) { channels, users ->
        val myId = tsClient?.clientId ?: return@combine 0L
        val channelId = users.find { it.id == myId }?.channelId ?: return@combine 0L
        channels.find { it.id == channelId }?.permissionHints ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // Track which channels we've already queried permissions for
    private val queriedPermChannels = mutableSetOf<Long>()

    // Track chat visibility to avoid incrementing unread for visible tab
    var isChatOpen = false
    var activeChatTab = 0
    var activePmPeerKey: String? = null

    private var bound = false

    init {
        // Combine raw users + talking set to produce patched user list
        viewModelScope.launch {
            combine(_rawUsers, _talkingUserIds, _isLocalTalking) { users, talking, localTalking ->
                // A talk_status_stop event can get dropped (event buffer
                // overflow); prune ids that are no longer in the user list so
                // nobody is stuck showing as talking forever
                val liveIds = users.map { it.id }.toSet()
                val pruned = talking.intersect(liveIds)
                if (pruned.size != talking.size) _talkingUserIds.value = pruned

                val myId = tsClient?.clientId

                users.map { user ->
                    val isLocallyTalking = (user.id == myId && localTalking)
                    val isRemoteTalking = user.id in pruned
                    val shouldBeTalking = isLocallyTalking || isRemoteTalking

                    if (shouldBeTalking && !user.isTalking) user.withTalking(true)
                    else if (!shouldBeTalking && user.isTalking) user.withTalking(false)
                    else user
                }
            }.collect { _users.value = it }
        }
        // When current channel changes, query permissions if not already known
        viewModelScope.launch {
            combine(_channels, _rawUsers) { channels, users ->
                val myId = tsClient?.clientId ?: return@combine null
                val channelId = users.find { it.id == myId }?.channelId ?: return@combine null
                val hints = channels.find { it.id == channelId }?.permissionHints ?: 0L
                channelId to hints
            }.collect { pair ->
                val (channelId, hints) = pair ?: return@collect
                if (hints == 0L && channelId !in queriedPermChannels) {
                    queriedPermChannels.add(channelId)
                    Log.i(TAG, "No permission hints for channel $channelId, querying permoverview...")
                    try {
                        tsClient?.queryChannelPermissions(channelId)
                    } catch (e: Exception) {
                        Log.e(TAG, "queryChannelPermissions failed", e)
                    }
                }
            }
        }
    }

    fun bindToService() {
        if (bound) return
        // Claim the bind up front: during the service-instance wait window a
        // second call would otherwise pass the guard and spawn duplicate
        // collectors (duplicated chat messages, double-counted unread)
        bound = true

        viewModelScope.launch {
            var attempts = 0
            while (TsConnectionService.instance == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            
            val service = TsConnectionService.instance
            if (service == null) {
                Log.e(TAG, "Failed to bind to TsConnectionService: instance is null")
                bound = false
                return@launch
            }

            tsClient = service.tsClient
            audioBridge = service.audioBridge
            audioBridge?.setMutedUserIds(_mutedUserIds.value)
            audioBridge?.setMicMode(micMode.value)
            audioBridge?.setVadThresholdDb(vadThresholdDb.value)
            viewModelScope.launch {
                settingsStore.micMode.collect { audioBridge?.setMicMode(it) }
            }
            viewModelScope.launch {
                settingsStore.vadThresholdDb.collect { audioBridge?.setVadThresholdDb(it) }
            }
            connectionService = service
            queriedPermChannels.clear()

            viewModelScope.launch {
                service.tsClient.channels.collect { channels ->
                    Log.d(TAG, "Channels updated: ${channels.size}")
                    _channels.value = channels
                    loadChannelIcons(channels)
                }
            }
            viewModelScope.launch {
                service.tsClient.users.collect {
                    _rawUsers.value = it
                    loadAvatars(it)
                    syncUserAudio(it)
                }
            }
            viewModelScope.launch {
                var bookmarkUpdated = false
                service.tsClient.serverInfo.collect { info ->
                    _serverInfo.value = info
                    if (info != null && !bookmarkUpdated) {
                        bookmarkUpdated = true
                        val addr = serverAddress ?: service.tsClient.serverAddress ?: ""
                        if (addr.isNotEmpty()) {
                            bookmarkStore.updateServerInfo(addr, info.name, info.iconId)
                            // Download server icon if needed
                            if (info.iconId != 0L) {
                                iconCache.loadIcon(info.iconId, service.tsClient)
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                service.tsClient.state.collect { _connectionState.value = it }
            }
            viewModelScope.launch {
                service.tsClient.events.collect { handleEvent(it) }
            }
            // Chat messages arrive on a replayed flow: events emitted between
            // connection and this subscription are replayed instead of lost.
            viewModelScope.launch {
                service.tsClient.chatEvents.collect { handleEvent(it) }
            }
            viewModelScope.launch {
                service.tsClient.commandErrors.collect { message ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                    }
                    rollbackLastPendingEcho()
                }
            }
            // Load persisted messages
            viewModelScope.launch {
                val addr = service.tsClient.serverAddress
                if (!addr.isNullOrEmpty()) {
                    serverAddress = addr
                    _serverAddressLabel.value = addr
                    val stored = messageStore.load(addr)
                    if (stored.channel.isNotEmpty()) _channelMessages.value = stored.channel.map { migrateMessage(it) }
                    if (stored.privateMessages.isNotEmpty()) {
                        _privateMessages.value = stored.privateMessages.mapValues { (_, msgs) -> msgs.map { migrateMessage(it) } }
                    }
                    if (stored.unreadPrivate.isNotEmpty()) _unreadPrivate.value = stored.unreadPrivate
                }
            }
            // Start audio capture if not already running. The bridge owns its own
            // capture scope, so the mic is no longer cancelled with this ViewModel.
            if (!service.audioBridge.isCapturing.value) {
                service.audioBridge.startCapture(noiseSuppression.value)
            }
            // Apply persisted audio gain
            service.audioBridge.gainFactor = audioGain.value
            // Observe audio gain changes and apply live
            viewModelScope.launch {
                audioGain.collect { gain ->
                    service.audioBridge.gainFactor = gain
                }
            }
            // Apply persisted mic gain
            service.audioBridge.micGainFactor = micGain.value
            viewModelScope.launch {
                micGain.collect { gain ->
                    service.audioBridge.micGainFactor = gain
                }
            }
            // Observe audio state for local talking status
            viewModelScope.launch {
                service.audioBridge.isLocalVoiceActive.collect { _isLocalTalking.value = it }
            }
            viewModelScope.launch {
                service.audioBridge.isOutputMuted.collect { _isOutputMuted.value = it }
            }
            viewModelScope.launch {
                service.reconnectStatus.collect { _reconnectStatus.value = it }
            }
            viewModelScope.launch {
                service.audioBridge.micStatus.collect { _micStatus.value = it }
            }

            // Start event loop (guarded by AtomicBoolean — safe if already running)
            service.tsClient.startEventLoop()
        }
    }

    private fun loadChannelIcons(channels: List<Channel>) {
        val client = tsClient ?: return
        val iconIds = channels.mapNotNull { ch ->
            if (ch.iconId != 0L) ch.iconId else null
        }.distinct()

        for (iconId in iconIds) {
            if (iconCache.getIcon(iconId) != null) {
                // Already in memory, make sure it's in the flow
                continue
            }
            viewModelScope.launch {
                iconCache.loadIcon(iconId, client)
                val icon = iconCache.getIcon(iconId)
                if (icon != null) {
                    _channelIcons.value = _channelIcons.value + (iconId to icon)
                }
            }
        }
        // Also emit icons already cached in memory
        val cached = mutableMapOf<Long, ImageBitmap>()
        for (iconId in iconIds) {
            iconCache.getIcon(iconId)?.let { cached[iconId] = it }
        }
        if (cached.isNotEmpty() && cached != _channelIcons.value) {
            _channelIcons.value = _channelIcons.value + cached
        }
    }

    private fun loadAvatars(users: List<User>) {
        val client = tsClient ?: return
        for (user in users) {
            val uid = user.uid ?: continue
            if (uid.isEmpty()) continue
            if (user.isQuery) continue
            if (user.avatarId.isNullOrEmpty()) continue  // no avatar on server
            if (avatarCache.getAvatar(uid) != null) continue
            if (avatarCache.hasNoAvatar(uid)) continue
            Log.d(TAG, "loadAvatars: launching download for ${user.nickname}")
            viewModelScope.launch {
                avatarCache.loadAvatar(uid, client)
                val avatar = avatarCache.getAvatar(uid)
                if (avatar != null) {
                    _avatars.value = _avatars.value + (uid to avatar)
                    Log.i(TAG, "loadAvatars: avatar loaded for ${user.nickname}")
                }
            }
        }
    }

    private fun handleEvent(event: Event) {
        try {
            when (event.type) {
                "talk_status_start" -> {
                    val userId = (event.data["user_id"] as? Number)?.toInt() ?: return
                    _talkingUserIds.value = _talkingUserIds.value + userId
                }
                "talk_status_stop" -> {
                    val userId = (event.data["user_id"] as? Number)?.toInt() ?: return
                    _talkingUserIds.value = _talkingUserIds.value - userId
                }
                "text_message" -> {
                    try {
                        // Safely extract message data with null checks
                        val target = event.data["target"] as? String
                        val sender = event.data["sender_name"] as? String
                        val senderId = (event.data["sender_id"] as? Number)?.toInt()
                        val text = event.data["message"] as? String

                        // Skip invalid messages early
                        if (target == null || sender == null || text == null) {
                            Log.w(TAG, "Invalid text_message: missing required fields")
                            return
                        }

                        // Server-side errors (flood/abuse protection etc.)
                        // surface as text_message without a real third-party
                        // sender. Only filter them there — otherwise regular
                        // user messages mentioning these words are dropped.
                        val myId = tsClient?.clientId
                        if ((senderId == null || senderId == myId) &&
                            (
                                text.contains("滥用保护") ||
                                text.contains("abuse protection") ||
                                text.contains("flood protection") ||
                                text.contains("spam protection") ||
                                text.contains("Cannot perform this action due to") ||
                                text.contains("无法采取此动作") ||
                                text.contains("Action currently not possible")
                            )
                        ) {
                            Log.i(TAG, "Skipping system abuse protection message: $text")
                            return
                        }

                        // Log safely with truncation to avoid huge strings
                        val safeText = if (text.length > 200) text.take(200) + "..." else text
                        Log.d(TAG, "Text message: target=$target sender=$sender text=$safeText")

                        // Skip our own messages — we already added them locally.
                        // The echo also proves the server accepted the send.
                        if (myId != null && senderId == myId) {
                            confirmPendingEcho(text)
                            return
                        }

                        // Safely parse file attachment with extra protection
                        val attachment = try {
                            if (text.length > 10000) {
                                Log.w(TAG, "Message too long, skipping file attachment parsing")
                                null
                            } else {
                                parseFileAttachment(text)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing file attachment", e)
                            null
                        }

                        // Use original text if attachment parsing fails, display safely
                        val displayText = if (attachment != null) {
                            attachment.fileName
                        } else {
                            // Sanitize text to avoid display issues
                            text.replace("\u0000", "").trim()
                        }

                        // Safely process based on message target
                        try {
                            when (target) {
                                "private" -> {
                                    val id = senderId ?: run {
                                        Log.w(TAG, "Private message without sender ID")
                                        return
                                    }

                                    // Key by the sender's permanent uid so a reconnect
                                    // does not open a second conversation with them
                                    val peerKey = peerKeyForClientId(id)
                                    foldLegacyConversation(peerKey, sender)

                                    // Safely create and add message
                                    val msg = ChatMessage(
                                        sender = sender, 
                                        text = displayText, 
                                        isPrivate = true, 
                                        senderId = id, 
                                        fileAttachment = attachment
                                    )
                                    
                                    val current = _privateMessages.value.toMutableMap()
                                    val existingMessages = current[peerKey] ?: emptyList()
                                    current[peerKey] = existingMessages + msg
                                    _privateMessages.value = current

                                    // Only increment if chat is closed or not on this user's PM.
                                    // Done before the save so the badge is persisted with the
                                    // message that caused it.
                                    if (!isChatOpen || activeChatTab != 1 || activePmPeerKey != peerKey) {
                                        val unread = _unreadPrivate.value.toMutableMap()
                                        val currentUnread = unread[peerKey] ?: 0
                                        unread[peerKey] = currentUnread + 1
                                        _unreadPrivate.value = unread
                                    }

                                    scheduleSave()
                                }
                                "channel" -> {
                                    // Safely create and add channel message
                                    val msg = ChatMessage(
                                        sender = sender, 
                                        text = displayText, 
                                        fileAttachment = attachment
                                    )
                                    
                                    val currentChannelMessages = _channelMessages.value
                                    _channelMessages.value = currentChannelMessages + msg
                                    
                                    scheduleSave()
                                    
                                    // Only increment if chat is closed or not on channel tab
                                    if (!isChatOpen || activeChatTab != 0) {
                                        val currentUnread = _unreadChannel.value
                                        _unreadChannel.value = currentUnread + 1
                                    }
                                }
                                else -> {
                                    Log.w(TAG, "Unknown message target: $target")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error adding message to UI", e)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing text_message event", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleEvent", e)
        }
    }

    private fun parseFileAttachment(text: String): FileAttachment? {
        // TS5 MyTeamSpeak JSON format
        if (text.startsWith("{\"msg_type\":")) {
            return try {
                val json = org.json.JSONObject(text)
                if (json.optString("msg_type") != "ts.file.myts") return null
                val fileName = json.optString("file_name", "")
                val fileSize = json.optLong("file_size", 0)
                val fileId = json.optString("file_id", "")
                if (fileName.isEmpty() || fileId.isEmpty()) return null
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                FileAttachment(fileName, fileSize, fileId, isImage)
            } catch (_: Exception) { null }
        }
        // TS3 file transfer URL format: ts3file://host?port=...&channel=...&filename=...&size=...
        val ts3Start = text.indexOf("ts3file://")
        if (ts3Start >= 0) {
            return try {
                // Extract the ts3file:// URL (until whitespace or end of string)
                val urlStr = text.substring(ts3Start).takeWhile { !it.isWhitespace() }
                val uri = android.net.Uri.parse(urlStr)
                val fileName = uri.getQueryParameter("filename") ?: return null
                val fileSize = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
                val channelId = uri.getQueryParameter("channel")?.toLongOrNull() ?: 0L
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                FileAttachment(fileName, fileSize, fileId = "", isImage, channelId = channelId)
            } catch (_: Exception) { null }
        }
        return null
    }

    /** Re-parse old saved messages that contain ts3file:// but have no fileAttachment. */
    private fun migrateMessage(msg: ChatMessage): ChatMessage {
        if (msg.fileAttachment != null) return msg
        val attachment = parseFileAttachment(msg.text) ?: return msg
        return msg.copy(text = attachment.fileName, fileAttachment = attachment)
    }

    fun setChatState(open: Boolean, tab: Int, pmPeerKey: String? = null) {
        isChatOpen = open
        activeChatTab = tab
        activePmPeerKey = pmPeerKey
        // Clear unread for the now-visible tab
        if (open) {
            if (tab == 0) clearUnreadChannel()
            else if (tab == 1 && pmPeerKey != null) clearUnreadPrivateUser(pmPeerKey)
        }
    }

    fun clearUnreadChannel() { _unreadChannel.value = 0 }

    fun clearUnreadPrivateUser(peerKey: String) {
        val unread = _unreadPrivate.value.toMutableMap()
        unread.remove(peerKey)
        _unreadPrivate.value = unread
        scheduleSave()
    }

    fun sendChannelMessage(text: String) {
        if (text.isBlank()) return
        tsClient?.sendChannelMessage(text)
        _channelMessages.value = _channelMessages.value + ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true,
        )
        pendingOwnEchoes.addLast(PendingEcho("channel", null, text, System.currentTimeMillis()))
        scheduleSave()
    }

    /**
     * Send to a conversation identified by [peerKey] (a uid). The client id is
     * resolved here, at send time, so the peer stays reachable across their
     * reconnects — the client id they had when the conversation started is gone.
     */
    fun sendPrivateMessage(peerKey: String, text: String) {
        if (text.isBlank()) return
        // Resolves a stale legacy key onto the peer's uid, or refuses the send
        // rather than delivering to whoever now holds that client id.
        val resolvedKey = resolvePeerKey(peerKey) ?: run {
            Log.w(TAG, "Cannot send private message: peer is not reachable")
            return
        }
        val clientId = clientIdForPeerKey(resolvedKey) ?: return
        tsClient?.sendPrivateMessage(clientId, text)
        val msg = ChatMessage(
            sender = getApplication<Application>().getString(R.string.me_sender), text = text, isMe = true, isPrivate = true, senderId = clientId,
        )
        val current = _privateMessages.value.toMutableMap()
        current[resolvedKey] = (current[resolvedKey] ?: emptyList()) + msg
        _privateMessages.value = current
        pendingOwnEchoes.addLast(PendingEcho("private", resolvedKey, text, System.currentTimeMillis()))
        scheduleSave()
    }

    fun moveToChannel(channelId: Long) {
        tsClient?.moveToChannel(channelId)
    }

    fun setAudioGain(gain: Float) {
        audioBridge?.gainFactor = gain
        viewModelScope.launch { settingsStore.setAudioGain(gain) }
    }

    fun setShowLinkThumbnails(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setShowLinkThumbnails(enabled) }
    }

    fun setAutoLoadImages(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoLoadImages(enabled) }
    }

    fun setEnableFloatingWindow(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setEnableFloatingWindow(enabled) }
    }

    fun setAnimeBackground(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAnimeBackground(enabled) }
    }

    fun setNoiseSuppression(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNoiseSuppression(enabled) }
    }

    fun toggleVoiceMode() {
        // Quick toggle between hold-to-talk and an open microphone; the full
        // three-way choice (including voice activation) lives in Settings.
        val newMode = if (isPttMode.value) MicMode.OPEN else MicMode.PTT
        viewModelScope.launch { settingsStore.setMicMode(newMode) }
        // When switching to PTT mode, mute. When switching to an open mic, unmute.
        audioBridge?.setMuted(newMode == MicMode.PTT)
    }

    fun toggleOutputMute() {
        audioBridge?.toggleOutputMute()
    }

    /** Local user id, or null before the connection is established. */
    val myClientId: Int?
        get() = tsClient?.clientId

    fun toggleMuteUser(clientId: Int) {
        val user = _rawUsers.value.find { it.id == clientId }
        if (user == null || user.uid.isNullOrEmpty()) {
            // No permanent identity to persist against — session-only fallback
            val updated = if (clientId in _mutedUserIds.value) {
                _mutedUserIds.value - clientId
            } else {
                _mutedUserIds.value + clientId
            }
            _mutedUserIds.value = updated
            audioBridge?.setMutedUserIds(updated)
            return
        }
        val addr = tsClient?.serverAddress ?: return
        val nowMuted = user.uid !in persistedMutedUids
        persistedMutedUids = if (nowMuted) persistedMutedUids + user.uid else persistedMutedUids - user.uid
        viewModelScope.launch {
            userAudioStore.setMuted(addr, user.uid, if (nowMuted) true else null)
        }
        syncUserAudio(_rawUsers.value)
    }

    /**
     * Apply a per-user volume in dB. Drag updates pass commit=false (bridge
     * only); the final value is committed with commit=true to persist it for
     * future sessions.
     */
    fun setUserVolumeDb(clientId: Int, db: Float, commit: Boolean) {
        audioBridge?.setUserGainDb(clientId, db)
        _userVolumes.value = if (db == 0f) _userVolumes.value - clientId else _userVolumes.value + (clientId to db)
        if (!commit) return
        val user = _rawUsers.value.find { it.id == clientId } ?: return
        val addr = tsClient?.serverAddress ?: return
        if (user.uid.isNullOrEmpty()) return
        if (db == 0f) volumeByUid.remove(user.uid) else volumeByUid[user.uid] = db
        viewModelScope.launch {
            userAudioStore.setVolumeDb(addr, user.uid, if (db == 0f) null else db)
        }
    }

    /**
     * Mirror persisted per-user volume/mute (keyed by permanent uid) onto the
     * current session's client ids and the audio bridge. Called on every
     * users-list update; also prunes bridge state of departed clients.
     */
    private fun syncUserAudio(users: List<User>) {
        val bridge = audioBridge ?: return
        val addr = tsClient?.serverAddress ?: return
        val myId = tsClient?.clientId
        if (userAudioAddress != addr) {
            userAudioAddress = addr
            volumeByUid.clear()
            loadedVolumeUids.clear()
            persistedMutedUids = emptySet()
        }
        viewModelScope.launch {
            for (user in users) {
                if (user.id == myId || user.uid.isNullOrEmpty() || user.uid in loadedVolumeUids) continue
                loadedVolumeUids.add(user.uid)
                userAudioStore.volumeDb(addr, user.uid)?.let { volumeByUid[user.uid] = it }
                if (userAudioStore.muted(addr, user.uid) == true) {
                    persistedMutedUids = persistedMutedUids + user.uid
                }
            }
            val uidToClientId = users.filter { !it.uid.isNullOrEmpty() }.associate { it.uid to it.id }
            val derived = volumeByUid.entries.mapNotNull { (uid, db) ->
                uidToClientId[uid]?.let { it to db }
            }.toMap()
            // In-flight (uncommitted) slider drags win over persisted values,
            // so a users-list refresh mid-drag cannot snap the slider back
            _userVolumes.value = derived + _userVolumes.value.filterKeys { clientId ->
                clientId in uidToClientId.values
            }
            val mutedClientIds = users.filter { it.uid in persistedMutedUids }.map { it.id }.toSet()
            _mutedUserIds.value = mutedClientIds
            bridge.setMutedUserIds(mutedClientIds)
            for ((uid, db) in volumeByUid) {
                uidToClientId[uid]?.let { bridge.setUserGainDb(it, db) }
            }
            bridge.pruneUsers(users.map { it.id }.toSet())
        }
    }

    fun setPushToTalk(pressed: Boolean) {
        // Only changes mute state, NOT isPttMode — avoids UI recomposition swap
        audioBridge?.setMuted(!pressed)
    }

    private fun currentChannelId(): Long {
        val myId = tsClient?.clientId ?: return 0
        return _rawUsers.value.find { it.id == myId }?.channelId ?: 0
    }

    /** Build a ts3file:// link with URL-encoded parameters so names with
     *  spaces, '&', '=' or CJK survive Uri.getQueryParameter on the receiver. */
    private fun buildTs3FileUrl(
        host: String,
        port: String,
        channelId: Long,
        path: String,
        fileName: String,
        size: Long,
        fileDateTime: Long? = null,
    ): String {
        fun enc(raw: String): String = java.net.URLEncoder.encode(raw, "UTF-8")
        return "ts3file://${enc(host)}?port=${enc(port)}&channel=$channelId" +
            "&path=${enc(path)}&filename=${enc(fileName)}&isDir=0&size=$size" +
            (fileDateTime?.let { "&fileDateTime=$it" } ?: "")
    }

    fun uploadAndSendFile(fileName: String, data: ByteArray, isPrivate: Boolean, targetPeerKey: String?) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        if (channelId == 0L) return
        viewModelScope.launch {
            val path = "/$fileName"
            val success = client.uploadFile(channelId, path, data, overwrite = true)
            if (success) {
                val addr = serverAddress ?: "localhost"
                val host = addr.substringBefore(':')
                val port = addr.substringAfter(':', "9987")
                val fileDateTime = System.currentTimeMillis() / 1000
                val ts3Url = buildTs3FileUrl(host, port, channelId, "/", fileName, data.size.toLong(), fileDateTime)

                val ext = fileName.substringAfterLast('.', "").lowercase()
                val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                val attachment = FileAttachment(fileName, data.size.toLong(), fileId = "", isImage, channelId = channelId)
                val meSender = getApplication<Application>().getString(R.string.me_sender)

                val resolvedKey = if (isPrivate && targetPeerKey != null) resolvePeerKey(targetPeerKey) else null
                val privateTargetId = resolvedKey?.let { clientIdForPeerKey(it) }
                if (resolvedKey != null && privateTargetId != null) {
                    tsClient?.sendPrivateMessage(privateTargetId, ts3Url)
                    val msg = ChatMessage(sender = meSender, text = fileName, isMe = true, isPrivate = true, senderId = privateTargetId, fileAttachment = attachment)
                    val current = _privateMessages.value.toMutableMap()
                    current[resolvedKey] = (current[resolvedKey] ?: emptyList()) + msg
                    _privateMessages.value = current
                } else {
                    tsClient?.sendChannelMessage(ts3Url)
                    val msg = ChatMessage(sender = meSender, text = fileName, isMe = true, fileAttachment = attachment)
                    _channelMessages.value = _channelMessages.value + msg
                }
                scheduleSave()
            }
        }
    }

    fun downloadAttachment(attachment: FileAttachment): StateFlow<DownloadState> {
        val channelId = if (attachment.channelId != 0L) attachment.channelId else currentChannelId()
        val host = serverAddress?.substringBefore(':') ?: "unknown"
        // Include the channel in the cache path: identical file names in
        // different channels must not collide
        val cachePath = "ch$channelId/" + attachment.fileName.trimStart('/')
        val cacheKey = "$host/$cachePath"

        // Return existing in-memory result if already loaded
        downloadCache[cacheKey]?.let { existing ->
            if (existing.value is DownloadState.Done) return existing
        }

        val state = MutableStateFlow<DownloadState>(DownloadState.Downloading)
        val client = tsClient ?: run {
            state.value = DownloadState.Error(getApplication<Application>().getString(R.string.error_not_connected))
            return state
        }

        // The channelId comes from the sender's message text; refuse targets
        // that don't exist on this server so a crafted link can't make the
        // client pull files from an arbitrary channel
        if (attachment.channelId != 0L && _channels.value.isNotEmpty() && _channels.value.none { it.id == attachment.channelId }) {
            state.value = DownloadState.Error(getApplication<Application>().getString(R.string.download_failed))
            return state
        }

        downloadCache[cacheKey] = state

        viewModelScope.launch(Dispatchers.IO) {
            // Check disk cache first
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, "/${attachment.fileName}")

            if (bytes != null) {
                // Save to disk cache if this was a fresh download
                if (cached == null) {
                    fileCache.put(host, cachePath, bytes)
                }

                if (attachment.isImage) {
                    val bmp = decodeSampledBitmap(bytes, 1280)
                    val uri = fileCache.getUri(host, cachePath)
                    state.value = DownloadState.Done(bmp?.asImageBitmap(), uri)
                } else {
                    val dlUri = saveToDownloads(attachment.fileName.substringAfterLast('/'), bytes)
                    state.value = DownloadState.Done(null, dlUri)
                }
            } else {
                downloadCache.remove(cacheKey)
                state.value = DownloadState.Error(getApplication<Application>().getString(R.string.download_failed))
            }
        }
        return state
    }

    private suspend fun saveToDownloads(fileName: String, data: ByteArray): android.net.Uri? {
        val context = getApplication<Application>()
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.file_saved, fileName), Toast.LENGTH_SHORT).show()
            }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "saveToDownloads failed for $fileName", e)
            null
        }
    }

    private fun decodeSampledBitmap(data: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
        // First pass: decode only bounds
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        // Calculate sample size (power of 2)
        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        if (width > maxDimension || height > maxDimension) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / sampleSize >= maxDimension || halfHeight / sampleSize >= maxDimension) {
                sampleSize *= 2
            }
        }

        // Second pass: decode with sample size
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
    }

    fun toggleFileManager() {
        if (_fileManagerOpen.value) {
            closeFileManager()
        } else {
            _fileManagerOpen.value = true
            refreshFileList()
        }
    }

    fun closeFileManager() {
        _fileManagerOpen.value = false
        _currentFilePath.value = "/"
        _fileList.value = emptyList()
    }

    fun refreshFileList() {
        val client = tsClient ?: run { Log.w(TAG, "refreshFileList: tsClient is null"); return }
        val channelId = currentChannelId()
        if (channelId == 0L) { Log.w(TAG, "refreshFileList: channelId is 0"); return }
        Log.d(TAG, "refreshFileList: channelId=$channelId path=${_currentFilePath.value}")
        _fileManagerLoading.value = true
        viewModelScope.launch {
            val files = client.listFiles(channelId, _currentFilePath.value)
            Log.d(TAG, "refreshFileList: got ${files?.size ?: "null"} files")
            _fileList.value = files ?: emptyList()
            _fileManagerLoading.value = false
        }
    }

    fun navigateToFolder(folderName: String) {
        val current = _currentFilePath.value
        _currentFilePath.value = if (current.endsWith("/")) "$current$folderName/" else "$current/$folderName/"
        refreshFileList()
    }

    fun navigateUp() {
        val current = _currentFilePath.value.trimEnd('/')
        if (current == "" || current == "/") return
        val parent = current.substringBeforeLast('/', "/")
        _currentFilePath.value = if (parent.endsWith("/")) parent else "$parent/"
        refreshFileList()
    }

    /** Native file ops are fire-and-forget: refresh shortly after, then once
     *  more in case the server was slow applying the change. */
    private fun refreshFileListAfterOperation() {
        refreshFileListAfterOperation()
        viewModelScope.launch { delay(2000); refreshFileList() }
    }

    fun deleteFileInChannel(name: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val fullPath = _currentFilePath.value + name
        client.deleteFile(channelId, fullPath)
        refreshFileListAfterOperation()
    }

    fun renameFileInChannel(oldName: String, newName: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val currentPath = _currentFilePath.value
        client.renameFile(channelId, currentPath + oldName, currentPath + newName)
        refreshFileListAfterOperation()
    }

    fun createDirectoryInChannel(dirName: String) {
        val client = tsClient ?: run { Log.w(TAG, "createDirectory: tsClient is null"); return }
        val channelId = currentChannelId()
        if (channelId == 0L) { Log.w(TAG, "createDirectory: channelId is 0"); return }
        val fullPath = _currentFilePath.value + dirName
        Log.d(TAG, "createDirectory: channelId=$channelId path=$fullPath")
        try {
            client.createDirectory(channelId, fullPath)
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory failed", e)
        }
        refreshFileListAfterOperation()
    }

    fun shareFile(targetPeerKey: String?, fileName: String, fileSize: Long) {
        val channelId = currentChannelId()
        if (channelId == 0L) return
        val addr = serverAddress ?: return
        val host = addr.substringBefore(':')
        val port = addr.substringAfter(':', "9987")
        val currentPath = _currentFilePath.value
        val ts3Url = buildTs3FileUrl(host, port, channelId, currentPath, fileName, fileSize)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        val attachment = FileAttachment(fileName, fileSize, fileId = "", isImage, channelId = channelId)
        val meSender = getApplication<Application>().getString(R.string.me_sender)

        val resolvedKey = targetPeerKey?.let { resolvePeerKey(it) }
        val privateTargetId = resolvedKey?.let { clientIdForPeerKey(it) }
        if (resolvedKey != null && privateTargetId != null) {
            tsClient?.sendPrivateMessage(privateTargetId, ts3Url)
            val msg = ChatMessage(
                sender = meSender, text = fileName, isMe = true,
                isPrivate = true, senderId = privateTargetId, fileAttachment = attachment,
            )
            val current = _privateMessages.value.toMutableMap()
            current[resolvedKey] = (current[resolvedKey] ?: emptyList()) + msg
            _privateMessages.value = current
        } else {
            tsClient?.sendChannelMessage(ts3Url)
            _channelMessages.value = _channelMessages.value + ChatMessage(
                sender = meSender, text = fileName, isMe = true, fileAttachment = attachment,
            )
        }
        scheduleSave()
    }

    fun downloadFileFromManager(fileName: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val currentPath = _currentFilePath.value
        val fullName = currentPath.trimStart('/') + fileName
        val host = serverAddress?.substringBefore(':') ?: "unknown"
        val cachePath = "ch$channelId/$fullName"

        viewModelScope.launch(Dispatchers.IO) {
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, "/$fullName")
            if (bytes != null) {
                if (cached == null) {
                    fileCache.put(host, cachePath, bytes)
                }
                val dlUri = saveToDownloads(fileName, bytes)
                if (dlUri != null) {
                    withContext(Dispatchers.Main) {
                        openFileUri(dlUri, fileName)
                    }
                }
            }
        }
    }

    fun previewImageFile(fileName: String) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        val currentPath = _currentFilePath.value
        val fullName = currentPath.trimStart('/') + fileName
        val host = serverAddress?.substringBefore(':') ?: "unknown"
        val cachePath = "ch$channelId/$fullName"

        viewModelScope.launch(Dispatchers.IO) {
            val cached = fileCache.get(host, cachePath)
            val bytes = cached ?: client.downloadFile(channelId, "/$fullName")
            if (bytes != null) {
                if (cached == null) {
                    fileCache.put(host, cachePath, bytes)
                }
                // Decode off the main thread, sampled to a sane size
                val bitmap = withContext(Dispatchers.Default) {
                    decodeSampledBitmap(bytes, 2048)?.asImageBitmap()
                }
                if (bitmap != null) {
                    _previewImageBitmap.value = bitmap
                    _previewImageName.value = fileName
                }
            }
        }
    }

    fun closePreview() {
        _previewImageBitmap.value = null
        _previewImageName.value = null
    }

    private fun openFileUri(uri: android.net.Uri, fileName: String) {
        val context = getApplication<Application>()
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun uploadFileToChannel(fileName: String, data: ByteArray) {
        val client = tsClient ?: return
        val channelId = currentChannelId()
        if (channelId == 0L) return
        val path = _currentFilePath.value + fileName
        viewModelScope.launch {
            val success = client.uploadFile(channelId, path, data, overwrite = true)
            if (success) {
                refreshFileListAfterOperation()
            }
        }
    }

    fun disconnect() {
        saveNow()
        queriedPermChannels.clear()
        connectionService?.disconnect()
    }

    override fun onCleared() {
        saveNow()
        bound = false
        tsClient = null
        audioBridge = null
        connectionService = null
        super.onCleared()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(2000)
            saveNow()
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val addr = serverAddress ?: return
        messageStore.save(addr, _channelMessages.value, _privateMessages.value, _unreadPrivate.value)
    }
}

/** Create a copy of User with isTalking changed (User fields are final). */
private fun User.withTalking(talking: Boolean): User = User(
    id, uid, databaseId, channelId, nickname, clientType,
    talking, isInputMuted, isOutputMuted, hasInputHardware, hasOutputHardware,
    isAway, isRecording, isPrioritySpeaker, isChannelCommander, isTalker,
    talkPower, awayMessage, serverGroups, channelGroup,
    platform, version, country, description, avatarId, iconId,
)
