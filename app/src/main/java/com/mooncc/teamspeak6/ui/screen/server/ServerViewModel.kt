package com.mooncc.teamspeak6.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mooncc.teamspeak6.domain.model.Bookmark
import com.mooncc.teamspeak6.domain.model.Channel
import com.mooncc.teamspeak6.domain.model.ChannelTreeBuilder
import com.mooncc.teamspeak6.domain.model.ChannelTreeRow
import com.mooncc.teamspeak6.domain.model.ChatTarget
import com.mooncc.teamspeak6.domain.model.Client
import com.mooncc.teamspeak6.domain.model.ConnectionState
import com.mooncc.teamspeak6.domain.model.Conversation
import com.mooncc.teamspeak6.domain.model.LocalMediaState
import com.mooncc.teamspeak6.domain.model.ServerEvent
import com.mooncc.teamspeak6.domain.repository.BookmarkRepository
import com.mooncc.teamspeak6.domain.repository.TeamSpeakRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    val uiState: StateFlow<ServerUiState> = combine(
        repository.connectionState,
        treeState,
        repository.localMediaState,
        repository.conversations,
        panelState,
    ) { connection, rows, media, conversations, panel ->
        ServerUiState(
            connection = connection,
            rows = rows,
            selectedTab = panel.tab,
            collapsedChannelIds = collapsedChannelIds.value,
            media = media,
            conversations = conversations,
            activeConversationKey = panel.conversationKey,
            dialog = panel.dialog,
            statusMessage = panel.status,
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
}
