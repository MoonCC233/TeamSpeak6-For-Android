package com.mooncc.teamspeak6.data.repository

import com.mooncc.teamspeak6.data.local.ChatMessageDao
import com.mooncc.teamspeak6.data.local.SettingsStore
import com.mooncc.teamspeak6.data.local.toDomain
import com.mooncc.teamspeak6.data.local.toEntity
import com.mooncc.teamspeak6.data.remote.TeamSpeakQueryApi
import com.mooncc.teamspeak6.data.remote.TeamSpeakQueryException
import com.mooncc.teamspeak6.data.remote.TeamSpeakTransportException
import com.mooncc.teamspeak6.data.remote.WebQueryClient
import com.mooncc.teamspeak6.data.remote.WebQueryClient.Companion.int
import com.mooncc.teamspeak6.di.ApplicationScope
import com.mooncc.teamspeak6.di.QueryHttpClient
import com.mooncc.teamspeak6.domain.model.Bookmark
import com.mooncc.teamspeak6.domain.model.Channel
import com.mooncc.teamspeak6.domain.model.ChannelGroup
import com.mooncc.teamspeak6.domain.model.ChannelTreeBuilder
import com.mooncc.teamspeak6.domain.model.ChatMessage
import com.mooncc.teamspeak6.domain.model.ChatTarget
import com.mooncc.teamspeak6.domain.model.Client
import com.mooncc.teamspeak6.domain.model.ConnectionState
import com.mooncc.teamspeak6.domain.model.ConnectionStatus
import com.mooncc.teamspeak6.domain.model.Conversation
import com.mooncc.teamspeak6.domain.model.DeliveryState
import com.mooncc.teamspeak6.domain.model.LocalMediaState
import com.mooncc.teamspeak6.domain.model.Permission
import com.mooncc.teamspeak6.domain.model.ServerEvent
import com.mooncc.teamspeak6.domain.model.ServerGroup
import com.mooncc.teamspeak6.domain.repository.BookmarkRepository
import com.mooncc.teamspeak6.domain.repository.TeamSpeakRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Drives the WebQuery connection: authenticates, polls server state on an
 * interval, diffs snapshots into user visible events, and exposes the command
 * surface used by the UI.
 */
@Singleton
class TeamSpeakRepositoryImpl @Inject constructor(
    @QueryHttpClient private val httpClient: OkHttpClient,
    private val json: Json,
    private val chatMessageDao: ChatMessageDao,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsStore: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : TeamSpeakRepository {

    private val _connectionState = MutableStateFlow(ConnectionState())
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _channelTree = MutableStateFlow<List<Channel>>(emptyList())
    override val channelTree: StateFlow<List<Channel>> = _channelTree.asStateFlow()

    private val _clients = MutableStateFlow<List<Client>>(emptyList())
    override val clients: StateFlow<List<Client>> = _clients.asStateFlow()

    private val _serverGroups = MutableStateFlow<List<ServerGroup>>(emptyList())
    override val serverGroups: StateFlow<List<ServerGroup>> = _serverGroups.asStateFlow()

    private val _channelGroups = MutableStateFlow<List<ChannelGroup>>(emptyList())
    override val channelGroups: StateFlow<List<ChannelGroup>> = _channelGroups.asStateFlow()

    private val _myPermissions = MutableStateFlow<List<Permission>>(emptyList())
    override val myPermissions: StateFlow<List<Permission>> = _myPermissions.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _localMediaState = MutableStateFlow(LocalMediaState())
    override val localMediaState: StateFlow<LocalMediaState> = _localMediaState.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private var api: TeamSpeakQueryApi? = null
    private var pollJob: Job? = null
    private var activeBookmark: Bookmark? = null
    private var lastClientSnapshot: Map<Int, Client> = emptyMap()
    private val commandMutex = Mutex()
    private var consecutiveFailures = 0

    // ------------------------------------------------------------ connection

    override suspend fun connect(bookmark: Bookmark) {
        disconnect()
        activeBookmark = bookmark
        _connectionState.value = ConnectionState(
            status = ConnectionStatus.CONNECTING,
            bookmark = bookmark,
        )

        val queryApi = TeamSpeakQueryApi(
            client = WebQueryClient(
                httpClient = httpClient,
                json = json,
                baseUrl = bookmark.queryBaseUrl,
                apiKey = bookmark.apiKey.ifBlank { bookmark.queryPassword },
            ),
            virtualServerId = bookmark.virtualServerId,
        )

        try {
            val whoAmI = queryApi.whoAmI()
            val localClientId = whoAmI?.int("client_id") ?: 0
            val server = queryApi.serverInfo()
            api = queryApi

            if (bookmark.nickname.isNotBlank()) {
                runCatching { queryApi.updateSelf(mapOf("client_nickname" to bookmark.nickname)) }
            }

            _connectionState.value = ConnectionState(
                status = ConnectionStatus.CONNECTED,
                bookmark = bookmark,
                server = server,
                localClientId = localClientId,
                currentChannelId = whoAmI?.int("client_channel_id") ?: 0,
            )

            ensureConversation(ChatTarget.SERVER, 0, server.name)
            restoreHistory(bookmark.id)
            bookmarkRepository.markConnected(bookmark.id)
            refreshStaticData(queryApi)
            startPolling()
        } catch (t: Throwable) {
            api = null
            _connectionState.value = ConnectionState(
                status = ConnectionStatus.ERROR,
                bookmark = bookmark,
                errorMessage = describe(t),
            )
            emit(ServerEvent.Error(describe(t), now()))
        }
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        api = null
        activeBookmark = null
        lastClientSnapshot = emptyMap()
        consecutiveFailures = 0
        _channelTree.value = emptyList()
        _clients.value = emptyList()
        _serverGroups.value = emptyList()
        _channelGroups.value = emptyList()
        _myPermissions.value = emptyList()
        _conversations.value = emptyList()
        _localMediaState.value = LocalMediaState()
        _connectionState.value = ConnectionState(status = ConnectionStatus.DISCONNECTED)
    }

    override suspend fun refreshNow() {
        val queryApi = api ?: return
        runCatching { pollOnce(queryApi) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val interval = settingsStore.settings.first().pollIntervalMs.coerceIn(500, 15_000)
            while (isActive) {
                val queryApi = api ?: break
                try {
                    pollOnce(queryApi)
                    if (consecutiveFailures > 0) {
                        consecutiveFailures = 0
                        _connectionState.update { it.copy(status = ConnectionStatus.CONNECTED) }
                    }
                } catch (t: Throwable) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_FAILURES_BEFORE_ERROR) {
                        _connectionState.update {
                            it.copy(
                                status = ConnectionStatus.ERROR,
                                errorMessage = describe(t),
                            )
                        }
                        emit(ServerEvent.Error(describe(t), now()))
                        break
                    }
                    _connectionState.update { it.copy(status = ConnectionStatus.RECONNECTING) }
                }
                delay(interval.toLong())
            }
        }
    }

    private suspend fun pollOnce(queryApi: TeamSpeakQueryApi) {
        val localId = _connectionState.value.localClientId
        val channels = queryApi.channelList()
        val fetchedClients = queryApi.clientList(localId)
        val tree = ChannelTreeBuilder.build(channels, fetchedClients)

        _clients.value = fetchedClients
        _channelTree.value = tree

        val localClient = fetchedClients.firstOrNull { it.id == localId }
        if (localClient != null) {
            _connectionState.update { it.copy(currentChannelId = localClient.channelId) }
            ensureConversation(
                ChatTarget.CHANNEL,
                localClient.channelId,
                ChannelTreeBuilder.findChannel(tree, localClient.channelId)?.displayName
                    ?: "Channel",
            )
        }

        diffClients(fetchedClients, tree)
    }

    private suspend fun refreshStaticData(queryApi: TeamSpeakQueryApi) {
        runCatching { _serverGroups.value = queryApi.serverGroups() }
        runCatching { _channelGroups.value = queryApi.channelGroups() }
        runCatching { _myPermissions.value = queryApi.myPermissions() }
        runCatching { pollOnce(queryApi) }
    }

    /**
     * Compares the previous client snapshot with the current one to synthesise
     * join / leave / move events, since WebQuery has no push channel.
     */
    private suspend fun diffClients(current: List<Client>, tree: List<Channel>) {
        val currentById = current.associateBy { it.id }
        val previous = lastClientSnapshot
        if (previous.isNotEmpty()) {
            val settings = settingsStore.settings.first()
            if (settings.notifyOnJoinLeave) {
                currentById.values.filterNot { it.isQuery }.forEach { client ->
                    val before = previous[client.id]
                    when {
                        before == null -> emit(
                            ServerEvent.ClientJoined(
                                client = client,
                                channelName = channelName(tree, client.channelId),
                                timestampMs = now(),
                            ),
                        )
                        before.channelId != client.channelId -> emit(
                            ServerEvent.ClientMoved(
                                client = client,
                                fromChannelName = channelName(tree, before.channelId),
                                toChannelName = channelName(tree, client.channelId),
                                isLocalClient = client.id == _connectionState.value.localClientId,
                                timestampMs = now(),
                            ),
                        )
                    }
                }
                previous.values.filterNot { it.isQuery }.forEach { before ->
                    if (before.id !in currentById) {
                        emit(ServerEvent.ClientLeft(before, now()))
                    }
                }
            }
        }
        lastClientSnapshot = currentById
    }

    // -------------------------------------------------------------- channels

    override suspend fun joinChannel(channelId: Int, password: String) {
        val queryApi = api ?: return
        val localId = _connectionState.value.localClientId
        if (localId == 0) {
            emit(ServerEvent.Error("未知的本地客户端 ID，无法切换频道", now()))
            return
        }
        runQuery { queryApi.moveClient(localId, channelId, password) }
            .onSuccess {
                _connectionState.update { it.copy(currentChannelId = channelId) }
                refreshNow()
            }
            .onFailure { emit(ServerEvent.Error(describe(it), now())) }
    }

    override suspend fun createChannel(
        name: String,
        parentId: Int,
        password: String,
        topic: String,
        description: String,
        permanent: Boolean,
        semiPermanent: Boolean,
        maxClients: Int,
    ): Result<Int> {
        val queryApi = api ?: return Result.failure(IllegalStateException("未连接"))
        return runQuery {
            queryApi.createChannel(
                name = name,
                parentId = parentId,
                password = password,
                topic = topic,
                description = description,
                permanent = permanent,
                semiPermanent = semiPermanent,
                maxClients = maxClients,
            )
        }.onSuccess { refreshNow() }
    }

    override suspend fun editChannel(
        channelId: Int,
        properties: Map<String, String>,
    ): Result<Unit> = withApi { it.editChannel(channelId, properties) }.onSuccess { refreshNow() }

    override suspend fun deleteChannel(channelId: Int, force: Boolean): Result<Unit> =
        withApi { it.deleteChannel(channelId, force) }.onSuccess { refreshNow() }

    // ------------------------------------------------------------------ chat

    override suspend fun sendMessage(
        target: ChatTarget,
        conversationId: Int,
        text: String,
    ): Result<Unit> {
        val queryApi = api ?: return Result.failure(IllegalStateException("未连接"))
        if (text.isBlank()) return Result.success(Unit)

        val state = _connectionState.value
        val pending = ChatMessage(
            id = UUID.randomUUID().toString(),
            target = target,
            conversationId = conversationId,
            senderClientId = state.localClientId,
            senderNickname = state.bookmark?.nickname.orEmpty(),
            text = text,
            timestampMs = now(),
            isOutgoing = true,
            deliveryState = DeliveryState.SENDING,
        )
        appendMessage(pending)

        val result = runQuery {
            when (target) {
                ChatTarget.SERVER -> queryApi.sendServerMessage(text)
                ChatTarget.CHANNEL -> queryApi.sendChannelMessage(text)
                ChatTarget.CLIENT -> queryApi.sendPrivateMessage(conversationId, text)
            }
        }

        updateMessage(pending.id) {
            it.copy(
                deliveryState = if (result.isSuccess) DeliveryState.SENT else DeliveryState.FAILED,
            )
        }
        persist(pending.copy(deliveryState = DeliveryState.SENT))
        return result.onFailure { emit(ServerEvent.Error(describe(it), now())) }
    }

    override fun observeConversation(target: ChatTarget, conversationId: Int): Flow<List<ChatMessage>> =
        _conversations.map { list ->
            list.firstOrNull { it.target == target && it.conversationId == conversationId }
                ?.messages
                .orEmpty()
        }

    override fun markConversationRead(target: ChatTarget, conversationId: Int) {
        _conversations.update { list ->
            list.map {
                if (it.target == target && it.conversationId == conversationId) {
                    it.copy(unreadCount = 0)
                } else {
                    it
                }
            }
        }
    }

    override fun openConversation(target: ChatTarget, conversationId: Int, title: String) {
        ensureConversation(target, conversationId, title)
    }

    override fun closeConversation(target: ChatTarget, conversationId: Int) {
        _conversations.update { list ->
            list.filterNot { it.target == target && it.conversationId == conversationId }
        }
    }

    private fun ensureConversation(target: ChatTarget, conversationId: Int, title: String) {
        _conversations.update { list ->
            val existing = list.firstOrNull {
                it.target == target && it.conversationId == conversationId
            }
            if (existing != null) {
                list.map { if (it === existing) it.copy(title = title) else it }
            } else {
                list + Conversation(target, conversationId, title)
            }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        ensureConversation(
            message.target,
            message.conversationId,
            defaultTitle(message.target, message.conversationId),
        )
        _conversations.update { list ->
            list.map { conversation ->
                if (conversation.target == message.target &&
                    conversation.conversationId == message.conversationId
                ) {
                    conversation.copy(
                        messages = (conversation.messages + message).takeLast(MAX_MESSAGES),
                        unreadCount = if (message.isOutgoing) {
                            conversation.unreadCount
                        } else {
                            conversation.unreadCount + 1
                        },
                    )
                } else {
                    conversation
                }
            }
        }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _conversations.update { list ->
            list.map { conversation ->
                val index = conversation.messages.indexOfFirst { it.id == id }
                if (index < 0) {
                    conversation
                } else {
                    conversation.copy(
                        messages = conversation.messages.toMutableList().apply {
                            this[index] = transform(this[index])
                        },
                    )
                }
            }
        }
    }

    private suspend fun persist(message: ChatMessage) {
        val bookmarkId = activeBookmark?.id ?: return
        val key = "${message.target.name}:${message.conversationId}"
        runCatching { chatMessageDao.insert(message.toEntity(bookmarkId, key)) }
    }

    private suspend fun restoreHistory(bookmarkId: Long) {
        val stored = runCatching {
            chatMessageDao.observeForBookmark(bookmarkId).first()
        }.getOrNull().orEmpty()
        if (stored.isEmpty()) return
        val grouped = stored.map { it.toDomain() }.groupBy { it.target to it.conversationId }
        _conversations.update { list ->
            val merged = list.toMutableList()
            grouped.forEach { (key, messages) ->
                val (target, conversationId) = key
                val index = merged.indexOfFirst {
                    it.target == target && it.conversationId == conversationId
                }
                val conversation = if (index >= 0) {
                    merged[index]
                } else {
                    Conversation(target, conversationId, defaultTitle(target, conversationId))
                }
                val updated = conversation.copy(
                    messages = messages.takeLast(MAX_MESSAGES),
                )
                if (index >= 0) merged[index] = updated else merged += updated
            }
            merged
        }
    }

    private fun defaultTitle(target: ChatTarget, conversationId: Int): String = when (target) {
        ChatTarget.SERVER -> _connectionState.value.server?.name ?: "Server"
        ChatTarget.CHANNEL -> ChannelTreeBuilder
            .findChannel(_channelTree.value, conversationId)?.displayName ?: "Channel"
        ChatTarget.CLIENT -> _clients.value.firstOrNull { it.id == conversationId }?.nickname
            ?: "Private"
    }

    // --------------------------------------------------------------- clients

    override suspend fun moveClient(clientId: Int, channelId: Int, password: String): Result<Unit> =
        withApi { it.moveClient(clientId, channelId, password) }.onSuccess { refreshNow() }

    override suspend fun kickFromChannel(clientId: Int, reason: String): Result<Unit> =
        withApi { it.kickFromChannel(clientId, reason) }.onSuccess { refreshNow() }

    override suspend fun kickFromServer(clientId: Int, reason: String): Result<Unit> =
        withApi { it.kickFromServer(clientId, reason) }.onSuccess { refreshNow() }

    override suspend fun banClient(
        clientId: Int,
        durationSeconds: Long,
        reason: String,
    ): Result<Unit> = withApi { it.banClient(clientId, durationSeconds, reason) }
        .onSuccess { refreshNow() }

    override suspend fun poke(clientId: Int, message: String): Result<Unit> =
        withApi { it.poke(clientId, message) }

    override suspend fun addToServerGroup(groupId: Int, clientDatabaseId: Int): Result<Unit> =
        withApi { it.addClientToServerGroup(groupId, clientDatabaseId) }.onSuccess { refreshNow() }

    override suspend fun removeFromServerGroup(groupId: Int, clientDatabaseId: Int): Result<Unit> =
        withApi { it.removeClientFromServerGroup(groupId, clientDatabaseId) }
            .onSuccess { refreshNow() }

    override suspend fun setChannelGroup(
        groupId: Int,
        channelId: Int,
        clientDatabaseId: Int,
    ): Result<Unit> = withApi { it.setChannelGroup(groupId, channelId, clientDatabaseId) }
        .onSuccess { refreshNow() }

    // ----------------------------------------------------------- local state

    override suspend fun setNickname(nickname: String): Result<Unit> =
        withApi { it.updateSelf(mapOf("client_nickname" to nickname)) }
            .onSuccess {
                activeBookmark = activeBookmark?.copy(nickname = nickname)
                _connectionState.update { state ->
                    state.copy(bookmark = state.bookmark?.copy(nickname = nickname))
                }
                refreshNow()
            }

    override suspend fun setMicMuted(muted: Boolean) {
        _localMediaState.update { it.copy(micMuted = muted) }
        api?.let { queryApi ->
            runQuery { queryApi.updateSelf(mapOf("client_input_muted" to muted.asFlag())) }
        }
    }

    override suspend fun setSpeakerMuted(muted: Boolean) {
        _localMediaState.update { it.copy(speakerMuted = muted) }
        api?.let { queryApi ->
            runQuery { queryApi.updateSelf(mapOf("client_output_muted" to muted.asFlag())) }
        }
    }

    override suspend fun setPushToTalkEnabled(enabled: Boolean) {
        _localMediaState.update { it.copy(pushToTalkEnabled = enabled, pushToTalkActive = false) }
        settingsStore.update { it.copy(pushToTalkEnabled = enabled) }
    }

    override suspend fun setPushToTalkActive(active: Boolean) {
        _localMediaState.update { it.copy(pushToTalkActive = active) }
    }

    override suspend fun setAway(away: Boolean, message: String) {
        _localMediaState.update { it.copy(isAway = away, awayMessage = message) }
        api?.let { queryApi ->
            runQuery {
                queryApi.updateSelf(
                    mapOf(
                        "client_away" to away.asFlag(),
                        "client_away_message" to message,
                    ),
                )
            }
        }
    }

    override suspend fun setChannelCommander(enabled: Boolean): Result<Unit> {
        _localMediaState.update { it.copy(isChannelCommander = enabled) }
        return withApi {
            it.updateSelf(mapOf("client_is_channel_commander" to enabled.asFlag()))
        }.onSuccess { refreshNow() }
    }

    override fun updateLocalMedia(transform: (LocalMediaState) -> LocalMediaState) {
        _localMediaState.update(transform)
    }

    /** Called by the RTC layer when an incoming chat message arrives out of band. */
    suspend fun onIncomingMessage(message: ChatMessage) {
        appendMessage(message)
        persist(message)
        emit(ServerEvent.MessageReceived(message, message.timestampMs))
    }

    // ----------------------------------------------------------------- utils

    private suspend fun <T> withApi(block: suspend (TeamSpeakQueryApi) -> T): Result<T> {
        val queryApi = api ?: return Result.failure(IllegalStateException("未连接到服务器"))
        return runQuery { block(queryApi) }
    }

    private suspend fun <T> runQuery(block: suspend () -> T): Result<T> = commandMutex.withLock {
        runCatching { block() }
    }

    private fun emit(event: ServerEvent) {
        _events.tryEmit(event)
    }

    private fun channelName(tree: List<Channel>, channelId: Int): String =
        ChannelTreeBuilder.findChannel(tree, channelId)?.displayName ?: "?"

    private fun Boolean.asFlag(): String = if (this) "1" else "0"

    private fun now(): Long = System.currentTimeMillis()

    private fun describe(t: Throwable): String = when (t) {
        is TeamSpeakQueryException -> buildString {
            append(t.message)
            t.extraMessage?.let { append(" (").append(it).append(')') }
        }
        is TeamSpeakTransportException -> t.message
        else -> t.message ?: t::class.java.simpleName
    }

    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        while (true) {
            val current = value
            if (compareAndSet(current, transform(current))) return
        }
    }

    private companion object {
        const val MAX_MESSAGES = 500
        const val MAX_FAILURES_BEFORE_ERROR = 3
    }
}
