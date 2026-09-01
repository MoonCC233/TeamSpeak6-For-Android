package com.mooncc.teamspeak9.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mooncc.teamspeak9.domain.model.Bookmark
import com.mooncc.teamspeak9.domain.model.Channel
import com.mooncc.teamspeak9.domain.model.ChannelTreeBuilder
import com.mooncc.teamspeak9.domain.model.ChannelTreeRow
import com.mooncc.teamspeak9.domain.model.ChatTarget
import com.mooncc.teamspeak9.domain.model.Client
import com.mooncc.teamspeak9.domain.model.ConnectionState
import com.mooncc.teamspeak9.domain.model.Conversation
import com.mooncc.teamspeak9.domain.model.LocalMediaState
import com.mooncc.teamspeak9.domain.model.ScreenShareConfig
import com.mooncc.teamspeak9.domain.model.ScreenShareMode
import com.mooncc.teamspeak9.domain.model.ScreenSharePrivacy
import com.mooncc.teamspeak9.domain.model.ScreenShareResolution
import com.mooncc.teamspeak9.domain.model.ScreenShareState
import com.mooncc.teamspeak9.domain.model.ServerEvent
import com.mooncc.teamspeak9.domain.repository.BookmarkRepository
import com.mooncc.teamspeak9.domain.repository.TeamSpeakRepository
import com.mooncc.teamspeak9.data.local.AppSettings
import com.mooncc.teamspeak9.data.local.SettingsStore
import com.mooncc.teamspeak9.screenshare.ScreenShareManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which pane of the server screen is visible. */
enum class ServerTab {
    CHANNELS,
    CHAT,
    SCREEN_SHARE,
}

/** Dialogs that can be layered over the server screen. */
sealed interface ServerDialog {
    data object None : ServerDialog
    data class ChannelPassword(val channel: Channel) : ServerDialog
    data class ClientActions(val client: Client) : ServerDialog
    data class ClientInfo(val client: Client) : ServerDialog

    /**
     * Local mute / volume for one client. Holds only the id so the dialog keeps
     * rendering live values as the sliders move.
     */
    data class ClientAudio(val clientId: Int) : ServerDialog
    data class ChannelActions(val channel: Channel) : ServerDialog
    data class ChannelInfo(val channel: Channel) : ServerDialog
    data class CreateChannel(val parentId: Int) : ServerDialog
    data class EditChannel(val channel: Channel) : ServerDialog
    data class Poke(val client: Client) : ServerDialog
    data class Kick(val client: Client, val fromServer: Boolean) : ServerDialog
    data class Ban(val client: Client) : ServerDialog
    data class MoveClient(val client: Client) : ServerDialog
    data class ServerGroups(val client: Client) : ServerDialog
    data object Nickname : ServerDialog
    data object AwayMessage : ServerDialog

    /** Composes the message sent along with a talk-power request. */
    data object TalkRequest : ServerDialog

    /** Pre-share options: mode, resolution, bitrate, privacy. */
    data object ScreenShareOptions : ServerDialog
}

data class ServerUiState(
    val connection: ConnectionState = ConnectionState(),
    val rows: List<ChannelTreeRow> = emptyList(),
    val selectedTab: ServerTab = ServerTab.CHANNELS,
    val collapsedChannelIds: Set<Int> = emptySet(),
    val media: LocalMediaState = LocalMediaState(),
    val conversations: List<Conversation> = emptyList(),
    val activeConversationKey: String? = null,
    val dialog: ServerDialog = ServerDialog.None,
    val statusMessage: String? = null,
    val screenShare: ScreenShareState = ScreenShareState(),
) {
    val totalUnread: Int get() = conversations.sumOf { it.unreadCount }
    val activeConversation: Conversation?
        get() = conversations.firstOrNull { it.key == activeConversationKey }
            ?: conversations.firstOrNull()
}

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val repository: TeamSpeakRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val screenShareManager: ScreenShareManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val collapsedChannelIds = MutableStateFlow<Set<Int>>(emptySet())
    private val selectedTab = MutableStateFlow(ServerTab.CHANNELS)
    private val dialog = MutableStateFlow<ServerDialog>(ServerDialog.None)
    private val activeConversationKey = MutableStateFlow<String?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)

    val events: SharedFlow<ServerEvent> = repository.events
    val serverGroups = repository.serverGroups
    val channelGroups = repository.channelGroups
    val clients = repository.clients
    val channelTree = repository.channelTree

    private val treeState = combine(
        repository.channelTree,
        collapsedChannelIds,
    ) { tree, collapsed -> ChannelTreeBuilder.flatten(tree, collapsed) }

    private val panelState = combine(
        selectedTab,
        dialog,
        activeConversationKey,
        statusMessage,
    ) { tab, currentDialog, conversationKey, status ->
        PanelState(tab, currentDialog, conversationKey, status)
    }

    /** Remote video tracks keyed by publisher id, consumed by the renderers. */
    val remoteScreenTracks = screenShareManager.remoteTracks
    val eglBaseContext get() = screenShareManager.eglBaseContext

    /** Signals the Activity to show the MediaProjection consent dialog. */
    val screenSharePermissionRequests: SharedFlow<Unit> = screenShareManager.permissionRequests

    val uiState: StateFlow<ServerUiState> = combine(
        repository.connectionState,
        treeState,
        repository.localMediaState,
        repository.conversations,
        panelState,
        screenShareManager.state,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val connection = values[0] as ConnectionState
        val rows = values[1] as List<ChannelTreeRow>
        val media = values[2] as LocalMediaState
        val conversations = values[3] as List<Conversation>
        val panel = values[4] as PanelState
        val screenShare = values[5] as ScreenShareState
        ServerUiState(
            connection = connection,
            rows = rows,
            selectedTab = panel.tab,
            collapsedChannelIds = collapsedChannelIds.value,
            media = media.copy(isSharingScreen = screenShare.isSharing),
            conversations = conversations,
            activeConversationKey = panel.conversationKey,
            dialog = panel.dialog,
            statusMessage = panel.status,
            screenShare = screenShare,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerUiState())

    private data class PanelState(
        val tab: ServerTab,
        val dialog: ServerDialog,
        val conversationKey: String?,
        val status: String?,
    )

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    init {
        // The signaling room is derived from the TeamSpeak channel, so re-dial on
        // every channel change and drop out when the connection goes away.
        viewModelScope.launch {
            repository.connectionState
                .map { RoomKey(it.isConnected, it.server?.uniqueIdentifier.orEmpty(), it.currentChannelId, it.localClientId) }
                .distinctUntilChanged()
                .collect { key ->
                    if (!key.connected || key.channelId == 0) {
                        screenShareManager.leaveRoom()
                        return@collect
                    }
                    val settings = settingsStore.settings.first()
                    screenShareManager.updateConfig { settings.toShareConfig() }
                    val local = repository.clients.value.firstOrNull { it.id == key.localClientId }
                    screenShareManager.enterRoom(
                        signalingUrl = settings.signalingUrl,
                        serverUid = key.serverUid,
                        channelId = key.channelId,
                        clientUid = local?.uniqueIdentifier.orEmpty(),
                        tsClientId = key.localClientId,
                        nickname = local?.nickname ?: settings.defaultNickname,
                    )
                }
        }
        viewModelScope.launch {
            screenShareManager.messages.collect { statusMessage.value = it }
        }
    }

    private data class RoomKey(
        val connected: Boolean,
        val serverUid: String,
        val channelId: Int,
        val localClientId: Int,
    )

    // ----------------------------------------------------------- connection

    fun connectToBookmark(bookmarkId: Long) {
        if (repository.connectionState.value.isConnected) return
        viewModelScope.launch {
            _isConnecting.value = true
            val bookmark = bookmarkRepository.getBookmark(bookmarkId)
            if (bookmark == null) {
                statusMessage.value = "书签不存在"
            } else {
                repository.connect(bookmark)
            }
            _isConnecting.value = false
        }
    }

    fun connect(bookmark: Bookmark) {
        viewModelScope.launch {
            _isConnecting.value = true
            repository.connect(bookmark)
            _isConnecting.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun refresh() {
        viewModelScope.launch { repository.refreshNow() }
    }

    // ------------------------------------------------------------- channels

    fun toggleChannelCollapsed(channelId: Int) {
        collapsedChannelIds.value = collapsedChannelIds.value.toMutableSet().apply {
            if (!add(channelId)) remove(channelId)
        }
    }

    fun joinChannel(channel: Channel) {
        if (channel.isSpacer) return
        if (channel.hasPassword) {
            dialog.value = ServerDialog.ChannelPassword(channel)
            return
        }
        viewModelScope.launch { repository.joinChannel(channel.id) }
    }

    fun joinChannelWithPassword(channelId: Int, password: String) {
        dismissDialog()
        viewModelScope.launch { repository.joinChannel(channelId, password) }
    }

    fun createChannel(
        name: String,
        parentId: Int,
        password: String,
        topic: String,
        description: String,
        permanent: Boolean,
        semiPermanent: Boolean,
        maxClients: Int,
    ) {
        dismissDialog()
        viewModelScope.launch {
            repository.createChannel(
                name = name,
                parentId = parentId,
                password = password,
                topic = topic,
                description = description,
                permanent = permanent,
                semiPermanent = semiPermanent,
                maxClients = maxClients,
            ).onFailure { statusMessage.value = it.message }
        }
    }

    fun editChannel(channelId: Int, properties: Map<String, String>) {
        dismissDialog()
        viewModelScope.launch {
            repository.editChannel(channelId, properties)
                .onFailure { statusMessage.value = it.message }
        }
    }

    fun deleteChannel(channelId: Int) {
        dismissDialog()
        viewModelScope.launch {
            repository.deleteChannel(channelId, force = true)
                .onFailure { statusMessage.value = it.message }
        }
    }

    // -------------------------------------------------------------- clients

    fun onClientClicked(client: Client) {
        dialog.value = ServerDialog.ClientActions(client)
    }

    fun onChannelLongPressed(channel: Channel) {
        dialog.value = ServerDialog.ChannelActions(channel)
    }

    fun showClientInfo(client: Client) {
        dialog.value = ServerDialog.ClientInfo(client)
    }

    fun showChannelInfo(channel: Channel) {
        dialog.value = ServerDialog.ChannelInfo(channel)
    }

    fun showCreateChannel(parentId: Int) {
        dialog.value = ServerDialog.CreateChannel(parentId)
    }

    fun showEditChannel(channel: Channel) {
        dialog.value = ServerDialog.EditChannel(channel)
    }

    fun showPoke(client: Client) {
        dialog.value = ServerDialog.Poke(client)
    }

    fun showKick(client: Client, fromServer: Boolean) {
        dialog.value = ServerDialog.Kick(client, fromServer)
    }

    fun showBan(client: Client) {
        dialog.value = ServerDialog.Ban(client)
    }

    fun showServerGroups(client: Client) {
        dialog.value = ServerDialog.ServerGroups(client)
    }

    fun showMoveClient(client: Client) {
        dialog.value = ServerDialog.MoveClient(client)
    }

    fun showNicknameDialog() {
        dialog.value = ServerDialog.Nickname
    }

    fun showAwayDialog() {
        dialog.value = ServerDialog.AwayMessage
    }

    fun dismissDialog() {
        dialog.value = ServerDialog.None
    }

    fun poke(clientId: Int, message: String) {
        dismissDialog()
        viewModelScope.launch {
            repository.poke(clientId, message).onFailure { statusMessage.value = it.message }
        }
    }

    fun kick(clientId: Int, reason: String, fromServer: Boolean) {
        dismissDialog()
        viewModelScope.launch {
            val result = if (fromServer) {
                repository.kickFromServer(clientId, reason)
            } else {
                repository.kickFromChannel(clientId, reason)
            }
            result.onFailure { statusMessage.value = it.message }
        }
    }

    fun ban(clientId: Int, durationSeconds: Long, reason: String) {
        dismissDialog()
        viewModelScope.launch {
            repository.banClient(clientId, durationSeconds, reason)
                .onFailure { statusMessage.value = it.message }
        }
    }

    fun moveClient(clientId: Int, channelId: Int) {
        dismissDialog()
        viewModelScope.launch {
            repository.moveClient(clientId, channelId)
                .onFailure { statusMessage.value = it.message }
        }
    }

    fun toggleServerGroup(client: Client, groupId: Int, add: Boolean) {
        viewModelScope.launch {
            val result = if (add) {
                repository.addToServerGroup(groupId, client.databaseId)
            } else {
                repository.removeFromServerGroup(groupId, client.databaseId)
            }
            result.onFailure { statusMessage.value = it.message }
        }
    }

    // ----------------------------------------------------------------- chat

    fun selectTab(tab: ServerTab) {
        selectedTab.value = tab
    }

    fun openConversation(target: ChatTarget, conversationId: Int, title: String) {
        repository.openConversation(target, conversationId, title)
        activeConversationKey.value = "${target.name}:$conversationId"
        selectedTab.value = ServerTab.CHAT
        repository.markConversationRead(target, conversationId)
    }

    fun selectConversation(conversation: Conversation) {
        activeConversationKey.value = conversation.key
        repository.markConversationRead(conversation.target, conversation.conversationId)
    }

    fun closeConversation(conversation: Conversation) {
        repository.closeConversation(conversation.target, conversation.conversationId)
        if (activeConversationKey.value == conversation.key) {
            activeConversationKey.value = null
        }
    }

    fun sendMessage(text: String) {
        val conversation = uiState.value.activeConversation ?: return
        viewModelScope.launch {
            repository.sendMessage(conversation.target, conversation.conversationId, text)
        }
    }

    // ------------------------------------------------------------ local媒体

    fun toggleMic() {
        viewModelScope.launch {
            repository.setMicMuted(!repository.localMediaState.value.micMuted)
        }
    }

    fun toggleSpeaker() {
        viewModelScope.launch {
            repository.setSpeakerMuted(!repository.localMediaState.value.speakerMuted)
        }
    }

    fun setPushToTalkActive(active: Boolean) {
        viewModelScope.launch { repository.setPushToTalkActive(active) }
    }

    fun togglePushToTalk() {
        viewModelScope.launch {
            repository.setPushToTalkEnabled(!repository.localMediaState.value.pushToTalkEnabled)
        }
    }

    fun setAway(away: Boolean, message: String) {
        dismissDialog()
        viewModelScope.launch { repository.setAway(away, message) }
    }

    fun toggleChannelCommander() {
        viewModelScope.launch {
            repository.setChannelCommander(!repository.localMediaState.value.isChannelCommander)
                .onFailure { statusMessage.value = it.message }
        }
    }

    fun togglePrioritySpeaker() {
        viewModelScope.launch {
            repository.setPrioritySpeaker(!repository.localMediaState.value.isPrioritySpeaker)
                .onFailure { statusMessage.value = it.message }
        }
    }

    /** Opens the talk-request dialog, or withdraws an outstanding request. */
    fun onTalkPowerButton() {
        if (repository.localMediaState.value.isRequestingTalkPower) {
            viewModelScope.launch {
                repository.requestTalkPower(false)
                    .onSuccess { statusMessage.value = "已取消发言申请" }
                    .onFailure { statusMessage.value = it.message }
            }
        } else {
            dialog.value = ServerDialog.TalkRequest
        }
    }

    fun requestTalkPower(message: String) {
        dismissDialog()
        viewModelScope.launch {
            repository.requestTalkPower(true, message)
                .onSuccess { statusMessage.value = "已向频道管理者申请发言权" }
                .onFailure { statusMessage.value = it.message }
        }
    }

    fun showClientAudio(client: Client) {
        dialog.value = ServerDialog.ClientAudio(client.id)
    }

    fun setClientLocalMuted(clientId: Int, muted: Boolean) {
        viewModelScope.launch { repository.setClientLocalMuted(clientId, muted) }
    }

    fun setClientVolume(clientId: Int, percent: Int) {
        viewModelScope.launch { repository.setClientVolume(clientId, percent) }
    }

    fun clearLocalClientOverrides() {
        viewModelScope.launch {
            repository.clearLocalClientOverrides()
            statusMessage.value = "已清除所有本地静音与音量设置"
        }
    }

    fun setNickname(nickname: String) {
        dismissDialog()
        viewModelScope.launch {
            repository.setNickname(nickname).onFailure { statusMessage.value = it.message }
        }
    }

    fun consumeStatusMessage() {
        statusMessage.value = null
    }

    fun showStatus(message: String) {
        statusMessage.value = message
    }

    // --------------------------------------------------------- screen share

    /** Toggles the local share, going through the system consent dialog when starting. */
    fun onToggleScreenShare() {
        screenShareManager.requestSharing()
    }

    fun showScreenShareOptions() {
        dialog.value = ServerDialog.ScreenShareOptions
    }

    /** Persists the picked options so they survive restarts, then applies them. */
    fun applyScreenShareConfig(config: ScreenShareConfig) {
        dismissDialog()
        screenShareManager.applyLiveConfig(config)
        viewModelScope.launch {
            settingsStore.update { it.withShareConfig(config) }
        }
    }

    fun onScreenSharePermissionGranted(intent: android.content.Intent) {
        screenShareManager.onPermissionGranted(intent)
    }

    fun onScreenSharePermissionDenied() {
        screenShareManager.onPermissionDenied()
    }

    fun watchShare(publisherId: String) {
        screenShareManager.watch(publisherId)
    }

    fun stopWatchingShare(publisherId: String) {
        screenShareManager.stopWatching(publisherId)
    }

    fun approveViewer(peerId: String) {
        screenShareManager.approveViewer(peerId)
    }

    fun denyViewer(peerId: String) {
        screenShareManager.denyViewer(peerId)
    }

    override fun onCleared() {
        super.onCleared()
        screenShareManager.leaveRoom()
    }
}

private fun AppSettings.toShareConfig() = ScreenShareConfig(
    mode = runCatching { ScreenShareMode.valueOf(screenShareMode) }
        .getOrDefault(ScreenShareMode.P2P),
    privacy = runCatching { ScreenSharePrivacy.valueOf(screenSharePrivacy) }
        .getOrDefault(ScreenSharePrivacy.PUBLIC),
    resolution = runCatching { ScreenShareResolution.valueOf(screenShareResolution) }
        .getOrDefault(ScreenShareResolution.P720),
    fps = screenShareFps,
    videoBitrateKbps = screenShareBitrateKbps,
    captureAudio = screenShareAudio,
    audioBitrateKbps = screenShareAudioBitrateKbps,
    viewerLimit = screenShareViewerLimit,
)

private fun AppSettings.withShareConfig(config: ScreenShareConfig) = copy(
    screenShareMode = config.mode.name,
    screenSharePrivacy = config.privacy.name,
    screenShareResolution = config.resolution.name,
    screenShareFps = config.fps,
    screenShareBitrateKbps = config.videoBitrateKbps,
    screenShareAudio = config.captureAudio,
    screenShareAudioBitrateKbps = config.audioBitrateKbps,
    screenShareViewerLimit = config.viewerLimit,
)
