package com.mooncc.teamspeak6.data.repository

import com.mooncc.teamspeak6.data.local.ChatMessageDao
import com.mooncc.teamspeak6.data.local.SettingsStore
import com.mooncc.teamspeak6.data.local.toDomain
import com.mooncc.teamspeak6.data.local.toEntity
import com.mooncc.teamspeak6.di.ApplicationScope
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
import com.mooncc.teamspeak6.voice.audio.VoiceCaptureEngine
import com.mooncc.teamspeak6.voice.audio.VoicePlaybackEngine
import com.mooncc.teamspeak6.voice.client.Ts3Event
import com.mooncc.teamspeak6.voice.client.Ts3Mappers
import com.mooncc.teamspeak6.voice.client.Ts3VoiceClient
import com.mooncc.teamspeak6.voice.identity.IdentityManager
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

/**
 * Drives a native TeamSpeak connection: the same UDP protocol the desktop
 * client speaks, so a bare server address is all the user has to provide.
 *
 * Unlike a query-based client there is no polling — the server pushes
 * notifications, which arrive here as [Ts3Event]s and are folded into the state
 * flows the UI observes. Full list reloads only happen on connect and when a
 * channel-structure notification says the tree changed.
 */
@Singleton
class NativeTeamSpeakRepositoryImpl @Inject constructor(
    private val chatMessageDao: ChatMessageDao,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsStore: SettingsStore,
    identityManager: IdentityManager,
    @ApplicationScope private val scope: CoroutineScope,
) : TeamSpeakRepository {

    private val capture = VoiceCaptureEngine()
    private val playback = VoicePlaybackEngine()
    private val client = Ts3VoiceClient(identityManager, capture, playback)

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

    private val commandMutex = Mutex()
    private val stateMutex = Mutex()

    private var activeBookmark: Bookmark? = null
    private var eventJob: Job? = null
    private var statsJob: Job? = null

    /** Channels and clients by id; the published tree is derived from these. */
    private var channelsById = linkedMapOf<Int, Channel>()
    private var clientsById = linkedMapOf<Int, Client>()
    private var talkingClientIds = mutableSetOf<Int>()

    init {
        playback.onTalkingChanged = { clientId, talking -> onTalkingChanged(clientId, talking) }
        capture.onTalkingChanged = { talking ->
            _localMediaState.update { it.copy(isTalking = talking) }
            val localId = _connectionState.value.localClientId
            if (localId != 0) onTalkingChanged(localId, talking)
        }
    }

    // ------------------------------------------------------------ connection

    override suspend fun connect(bookmark: Bookmark) {
        disconnect()
        activeBookmark = bookmark
        val settings = runCatching { settingsStore.settings.first() }.getOrNull()
        _localMediaState.value = LocalMediaState(
            pushToTalkEnabled = settings?.pushToTalkEnabled ?: false,
            voiceActivationThresholdDb = settings?.voiceActivationThresholdDb ?: -40,
            outputVolumePercent = settings?.outputVolumePercent ?: 100,
            inputGainPercent = settings?.inputGainPercent ?: 100,
            echoCancellation = settings?.echoCancellation ?: true,
            noiseSuppression = settings?.noiseSuppression ?: true,
            autoGainControl = settings?.autoGainControl ?: true,
        )
        _connectionState.value = ConnectionState(
            status = ConnectionStatus.CONNECTING,
            bookmark = bookmark,
        )

        val connected = client.connect(
            host = bookmark.host,
            port = bookmark.voicePort,
            nickname = bookmark.nickname,
            serverPassword = bookmark.serverPassword,
            defaultChannel = bookmark.defaultChannel,
            defaultChannelPassword = bookmark.defaultChannelPassword,
            subscribeAll = settings?.autoSubscribeChannels ?: true,
        )

        connected.onFailure { failure ->
            activeBookmark = null
            _connectionState.value = ConnectionState(
                status = ConnectionStatus.ERROR,
                bookmark = bookmark,
                errorMessage = describe(failure),
            )
            emit(ServerEvent.Error(describe(failure), now()))
            return
        }

        observeEvents()

        val whoAmI = client.whoAmI().getOrNull().orEmpty()
        val localClientId = whoAmI["clid"]?.toIntOrNull() ?: client.localClientId
        val server = client.serverInfo().getOrNull().orEmpty()

        _connectionState.value = ConnectionState(
            status = ConnectionStatus.CONNECTED,
            bookmark = bookmark,
            server = Ts3Mappers.toVirtualServer(server).let { info ->
                if (info.name.isBlank()) info.copy(name = bookmark.label) else info
            },
            localClientId = localClientId,
            currentChannelId = whoAmI["cid"]?.toIntOrNull() ?: 0,
        )

        ensureConversation(ChatTarget.SERVER, 0, _connectionState.value.server?.name ?: bookmark.label)
        restoreHistory(bookmark.id)
        bookmarkRepository.markConnected(bookmark.id)

        refreshGroups()
        refreshNow()
        startAudio()
        observeStatistics()
    }

    override suspend fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        statsJob?.cancel()
        statsJob = null
        stopAudio()
        client.disconnect()

        activeBookmark = null
        stateMutex.withLock {
            channelsById = linkedMapOf()
            clientsById = linkedMapOf()
            talkingClientIds = mutableSetOf()
        }
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
        if (!client.isConnected) return
        val channels = client.listChannels().getOrNull() ?: return
        val clientRows = client.listClients().getOrNull() ?: return
        val localId = _connectionState.value.localClientId

        stateMutex.withLock {
            channelsById = channels
                .map(Ts3Mappers::toChannel)
                .associateByTo(linkedMapOf()) { it.id }
            clientsById = clientRows
                .map { Ts3Mappers.toClient(it, localId) }
                .associateByTo(linkedMapOf()) { it.id }
            talkingClientIds.retainAll(clientsById.keys)
        }
        publish()

        val localChannelId = clientsById[localId]?.channelId
        if (localChannelId != null) {
            _connectionState.update { it.copy(currentChannelId = localChannelId) }
            ensureConversation(ChatTarget.CHANNEL, localChannelId, channelTitle(localChannelId))
        }
    }

    private suspend fun refreshChannels() {
        if (!client.isConnected) return
        val channels = client.listChannels().getOrNull() ?: return
        stateMutex.withLock {
            channelsById = channels
                .map(Ts3Mappers::toChannel)
                .associateByTo(linkedMapOf()) { it.id }
        }
        publish()
    }

    private suspend fun refreshGroups() {
        client.listServerGroups().onSuccess { rows ->
            _serverGroups.value = rows.map(Ts3Mappers::toServerGroup)
        }
        client.listChannelGroups().onSuccess { rows ->
            _channelGroups.value = rows.map(Ts3Mappers::toChannelGroup)
        }
        client.listMyPermissions().onSuccess { rows ->
            _myPermissions.value = rows.map(Ts3Mappers::toPermission)
        }
    }

    /** Rebuilds the published tree and client list from the id maps. */
    private fun publish() {
        val clientList = clientsById.values.map { it.copy(isTalking = it.id in talkingClientIds) }
        _clients.value = clientList
        _channelTree.value = ChannelTreeBuilder.build(channelsById.values.toList(), clientList)
    }

    // ---------------------------------------------------------------- events

    private fun observeEvents() {
        eventJob?.cancel()
        eventJob = scope.launch {
            client.events.collect { event -> handle(event) }
        }
    }

    private suspend fun handle(event: Ts3Event) {
        when (event) {
            is Ts3Event.Connected -> Unit

            is Ts3Event.Disconnected -> onDisconnected(event)

            is Ts3Event.ChannelListReceived,
            is Ts3Event.ChannelCreated,
            is Ts3Event.ChannelEdited,
            is Ts3Event.ChannelMoved,
            is Ts3Event.ChannelDeleted,
            -> refreshChannels()

            is Ts3Event.ClientJoined -> onClientJoined(event)
            is Ts3Event.ClientLeft -> onClientLeft(event)
            is Ts3Event.ClientMoved -> onClientMoved(event)
            is Ts3Event.ClientUpdated -> onClientUpdated(event.clientId, event.properties)

            is Ts3Event.ClientChannelGroupChanged -> onClientUpdated(
                event.clientId,
                mapOf("client_channel_group_id" to event.channelGroupId.toString()),
            )

            is Ts3Event.ClientServerGroupChanged -> onServerGroupChanged(event)

            is Ts3Event.TextMessage -> onTextMessage(event)

            is Ts3Event.Poked -> emit(
                ServerEvent.Poked(event.invokerName, event.message, now()),
            )

            is Ts3Event.ServerEdited -> client.serverInfo().onSuccess { row ->
                _connectionState.update { it.copy(server = Ts3Mappers.toVirtualServer(row)) }
            }

            is Ts3Event.ServerGroupListReceived,
            is Ts3Event.ChannelGroupListReceived,
            -> refreshGroups()

            is Ts3Event.Failure -> emit(ServerEvent.Error(event.message, now()))
        }
    }

    private suspend fun onDisconnected(event: Ts3Event.Disconnected) {
        stopAudio()
        val bookmark = activeBookmark
        val kicked = event.reasonId == REASON_KICK_SERVER || event.reasonId == REASON_BAN
        if (kicked) {
            emit(ServerEvent.Kicked(event.reasonMessage, fromServer = true, timestampMs = now()))
        }
        _connectionState.value = ConnectionState(
            status = if (event.reasonMessage.isBlank() && !kicked) {
                ConnectionStatus.DISCONNECTED
            } else {
                ConnectionStatus.ERROR
            },
            bookmark = bookmark,
            errorMessage = event.reasonMessage.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun onClientJoined(event: Ts3Event.ClientJoined) {
        val localId = _connectionState.value.localClientId
        val joined = Ts3Mappers
            .toClient(event.properties, localId)
            .copy(id = event.clientId, channelId = event.channelId)

        stateMutex.withLock { clientsById[event.clientId] = joined }
        publish()

        if (!joined.isQuery && settingsStore.settings.first().notifyOnJoinLeave) {
            emit(
                ServerEvent.ClientJoined(
                    client = joined,
                    channelName = channelTitle(joined.channelId),
                    timestampMs = now(),
                ),
            )
        }
    }

    private suspend fun onClientLeft(event: Ts3Event.ClientLeft) {
        val left = stateMutex.withLock {
            talkingClientIds.remove(event.clientId)
            clientsById.remove(event.clientId)
        } ?: return
        publish()

        if (left.isLocal) {
            if (event.reasonId == REASON_KICK_SERVER || event.reasonId == REASON_BAN) {
                emit(ServerEvent.Kicked(event.reasonMessage, fromServer = true, timestampMs = now()))
            }
            return
        }
        if (!left.isQuery && settingsStore.settings.first().notifyOnJoinLeave) {
            emit(ServerEvent.ClientLeft(left, now()))
        }
    }

    private suspend fun onClientMoved(event: Ts3Event.ClientMoved) {
        val previous = clientsById[event.clientId] ?: run {
            refreshNow()
            return
        }
        val moved = previous.copy(channelId = event.targetChannelId)
        stateMutex.withLock { clientsById[event.clientId] = moved }
        publish()

        if (moved.isLocal) {
            _connectionState.update { it.copy(currentChannelId = event.targetChannelId) }
            ensureConversation(
                ChatTarget.CHANNEL,
                event.targetChannelId,
                channelTitle(event.targetChannelId),
            )
            if (event.reasonId == REASON_KICK_CHANNEL) {
                emit(
                    ServerEvent.Kicked(
                        reason = event.reasonMessage,
                        fromServer = false,
                        timestampMs = now(),
                    ),
                )
            }
        }

        if (!moved.isQuery && settingsStore.settings.first().notifyOnJoinLeave) {
            emit(
                ServerEvent.ClientMoved(
                    client = moved,
                    fromChannelName = channelTitle(previous.channelId),
                    toChannelName = channelTitle(event.targetChannelId),
                    isLocalClient = moved.isLocal,
                    timestampMs = now(),
                ),
            )
        }
    }

    private suspend fun onClientUpdated(clientId: Int, properties: Map<String, String>) {
        val previous = clientsById[clientId] ?: return
        val localId = _connectionState.value.localClientId
        val updated = Ts3Mappers.mergeClient(previous, properties, localId)
        stateMutex.withLock { clientsById[clientId] = updated }
        publish()
    }

    private suspend fun onServerGroupChanged(event: Ts3Event.ClientServerGroupChanged) {
        val previous = clientsById[event.clientId] ?: return
        val groups = if (event.added) {
            (previous.serverGroups + event.serverGroupId).distinct()
        } else {
            previous.serverGroups - event.serverGroupId
        }
        stateMutex.withLock { clientsById[event.clientId] = previous.copy(serverGroups = groups) }
        publish()
    }

    private suspend fun onTextMessage(event: Ts3Event.TextMessage) {
        val state = _connectionState.value
        if (event.invokerId == state.localClientId) return

        val target = ChatTarget.fromTargetMode(event.targetMode)
        val conversationId = when (target) {
            ChatTarget.SERVER -> 0
            ChatTarget.CHANNEL -> state.currentChannelId
            ChatTarget.CLIENT -> event.invokerId
        }
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            target = target,
            conversationId = conversationId,
            senderClientId = event.invokerId,
            senderNickname = event.invokerName,
            senderUniqueIdentifier = event.invokerUniqueId,
            text = event.message,
            timestampMs = now(),
        )
        appendMessage(message)
        persist(message)
        emit(ServerEvent.MessageReceived(message, message.timestampMs))
    }

    private fun onTalkingChanged(clientId: Int, talking: Boolean) {
        scope.launch {
            val changed = stateMutex.withLock {
                if (talking) talkingClientIds.add(clientId) else talkingClientIds.remove(clientId)
            }
            if (changed) publish()
        }
    }

    private fun observeStatistics() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && client.isConnected) {
                _connectionState.update { it.copy(pingMs = client.pingMs) }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    // -------------------------------------------------------------- channels

    override suspend fun joinChannel(channelId: Int, password: String) {
        val localId = _connectionState.value.localClientId
        if (localId == 0) {
            emit(ServerEvent.Error("未知的本地客户端 ID，无法切换频道", now()))
            return
        }
        moveClient(localId, channelId, password)
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
    ): Result<Int> = runCommand {
        val params = buildMap {
            put("channel_name", name)
            if (parentId > 0) put("cpid", parentId.toString())
            if (topic.isNotBlank()) put("channel_topic", topic)
            if (description.isNotBlank()) put("channel_description", description)
            if (password.isNotBlank()) {
                put("channel_password", client.hashPassword(password))
            }
            put("channel_flag_permanent", permanent.flag())
            put("channel_flag_semi_permanent", semiPermanent.flag())
            if (maxClients >= 0) {
                put("channel_flag_maxclients_unlimited", "0")
                put("channel_maxclients", maxClients.toString())
            } else {
                put("channel_flag_maxclients_unlimited", "1")
            }
        }
        client.command("channelcreate", params).map { rows ->
            rows.firstNotNullOfOrNull { it["cid"]?.toIntOrNull() } ?: 0
        }
    }.onSuccess { refreshChannels() }

    override suspend fun editChannel(
        channelId: Int,
        properties: Map<String, String>,
    ): Result<Unit> = runCommand {
        client.execute("channeledit", mapOf("cid" to channelId.toString()) + properties)
    }.onSuccess { refreshChannels() }

    override suspend fun deleteChannel(channelId: Int, force: Boolean): Result<Unit> = runCommand {
        client.execute(
            "channeldelete",
            mapOf("cid" to channelId.toString(), "force" to force.flag()),
        )
    }.onSuccess { refreshChannels() }

    // ------------------------------------------------------------------ chat

    override suspend fun sendMessage(
        target: ChatTarget,
        conversationId: Int,
        text: String,
    ): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        if (!client.isConnected) return Result.failure(IllegalStateException("未连接"))

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

        val result = runCommand {
            when (target) {
                ChatTarget.SERVER -> client.sendServerMessage(text)
                ChatTarget.CHANNEL -> client.sendChannelMessage(conversationId, text)
                ChatTarget.CLIENT -> client.sendPrivateMessage(conversationId, text)
            }
        }

        updateMessage(pending.id) {
            it.copy(
                deliveryState = if (result.isSuccess) DeliveryState.SENT else DeliveryState.FAILED,
            )
        }
        if (result.isSuccess) persist(pending.copy(deliveryState = DeliveryState.SENT))
        return result.onFailure { emit(ServerEvent.Error(describe(it), now())) }
    }

    override fun observeConversation(
        target: ChatTarget,
        conversationId: Int,
    ): Flow<List<ChatMessage>> = _conversations.map { list ->
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
                val updated = conversation.copy(messages = messages.takeLast(MAX_MESSAGES))
                if (index >= 0) merged[index] = updated else merged += updated
            }
            merged
        }
    }

    private fun defaultTitle(target: ChatTarget, conversationId: Int): String = when (target) {
        ChatTarget.SERVER -> _connectionState.value.server?.name ?: "Server"
        ChatTarget.CHANNEL -> channelTitle(conversationId)
        ChatTarget.CLIENT -> clientsById[conversationId]?.nickname ?: "Private"
    }

    // --------------------------------------------------------------- clients

    override suspend fun moveClient(
        clientId: Int,
        channelId: Int,
        password: String,
    ): Result<Unit> = runCommand {
        client.execute(
            "clientmove",
            buildMap {
                put("clid", clientId.toString())
                put("cid", channelId.toString())
                if (password.isNotBlank()) put("cpw", client.hashPassword(password))
            },
        )
    }

    override suspend fun kickFromChannel(clientId: Int, reason: String): Result<Unit> = runCommand {
        client.execute(
            "clientkick",
            mapOf(
                "clid" to clientId.toString(),
                "reasonid" to REASON_KICK_CHANNEL.toString(),
                "reasonmsg" to reason.take(KICK_REASON_MAX_LENGTH),
            ),
        )
    }

    override suspend fun kickFromServer(clientId: Int, reason: String): Result<Unit> = runCommand {
        client.execute(
            "clientkick",
            mapOf(
                "clid" to clientId.toString(),
                "reasonid" to REASON_KICK_SERVER.toString(),
                "reasonmsg" to reason.take(KICK_REASON_MAX_LENGTH),
            ),
        )
    }

    override suspend fun banClient(
        clientId: Int,
        durationSeconds: Long,
        reason: String,
    ): Result<Unit> = runCommand {
        client.execute(
            "banclient",
            buildMap {
                put("clid", clientId.toString())
                if (durationSeconds > 0) put("time", durationSeconds.toString())
                if (reason.isNotBlank()) put("banreason", reason)
            },
        )
    }

    override suspend fun poke(clientId: Int, message: String): Result<Unit> = runCommand {
        client.execute("clientpoke", mapOf("clid" to clientId.toString(), "msg" to message))
    }

    override suspend fun addToServerGroup(
        groupId: Int,
        clientDatabaseId: Int,
    ): Result<Unit> = runCommand {
        client.execute(
            "servergroupaddclient",
            mapOf("sgid" to groupId.toString(), "cldbid" to clientDatabaseId.toString()),
        )
    }

    override suspend fun removeFromServerGroup(
        groupId: Int,
        clientDatabaseId: Int,
    ): Result<Unit> = runCommand {
        client.execute(
            "servergroupdelclient",
            mapOf("sgid" to groupId.toString(), "cldbid" to clientDatabaseId.toString()),
        )
    }

    override suspend fun setChannelGroup(
        groupId: Int,
        channelId: Int,
        clientDatabaseId: Int,
    ): Result<Unit> = runCommand {
        client.execute(
            "setclientchannelgroup",
            mapOf(
                "cgid" to groupId.toString(),
                "cid" to channelId.toString(),
                "cldbid" to clientDatabaseId.toString(),
            ),
        )
    }

    // ----------------------------------------------------------- local media

    override suspend fun setNickname(nickname: String): Result<Unit> =
        updateSelf("client_nickname" to nickname).onSuccess {
            activeBookmark = activeBookmark?.copy(nickname = nickname)
            _connectionState.update { state ->
                state.copy(bookmark = state.bookmark?.copy(nickname = nickname))
            }
        }

    override suspend fun setMicMuted(muted: Boolean) {
        _localMediaState.update { it.copy(micMuted = muted) }
        syncAudio()
        updateSelf("client_input_muted" to muted.flag())
    }

    override suspend fun setSpeakerMuted(muted: Boolean) {
        _localMediaState.update { it.copy(speakerMuted = muted) }
        syncAudio()
        updateSelf("client_output_muted" to muted.flag())
    }

    override suspend fun setPushToTalkEnabled(enabled: Boolean) {
        _localMediaState.update { it.copy(pushToTalkEnabled = enabled, pushToTalkActive = false) }
        syncAudio()
        settingsStore.update { it.copy(pushToTalkEnabled = enabled) }
    }

    override suspend fun setPushToTalkActive(active: Boolean) {
        _localMediaState.update { it.copy(pushToTalkActive = active) }
        syncAudio()
    }

    override suspend fun setAway(away: Boolean, message: String) {
        _localMediaState.update { it.copy(isAway = away, awayMessage = message) }
        updateSelf(
            "client_away" to away.flag(),
            "client_away_message" to message,
        )
    }

    override suspend fun setChannelCommander(enabled: Boolean): Result<Unit> {
        _localMediaState.update { it.copy(isChannelCommander = enabled) }
        return updateSelf("client_is_channel_commander" to enabled.flag())
    }

    override fun updateLocalMedia(transform: (LocalMediaState) -> LocalMediaState) {
        _localMediaState.update(transform)
        syncAudio()
    }

    private suspend fun updateSelf(vararg properties: Pair<String, String>): Result<Unit> =
        runCommand { client.execute("clientupdate", properties.toMap()) }

    // ----------------------------------------------------------------- audio

    private suspend fun startAudio() {
        playback.start()
        if (!capture.start()) {
            emit(ServerEvent.Error("无法访问麦克风，请检查录音权限", now()))
        }
        syncAudio()
    }

    private fun stopAudio() {
        capture.stop()
        playback.stop()
    }

    /** Pushes [LocalMediaState] onto the two audio engines. */
    private fun syncAudio() {
        val state = _localMediaState.value

        capture.muted = state.micMuted
        capture.transmitting = state.shouldTransmit
        capture.voiceActivationEnabled = !state.pushToTalkEnabled
        capture.activationThresholdDb = state.voiceActivationThresholdDb
        capture.inputGainPercent = state.inputGainPercent
        capture.echoCancellation = state.echoCancellation
        capture.noiseSuppression = state.noiseSuppression
        capture.autoGainControl = state.autoGainControl

        playback.muted = state.speakerMuted
        playback.masterVolumePercent = state.outputVolumePercent

        capture.setBitrate(currentChannelBitrate())
    }

    /**
     * TeamSpeak channels advertise a codec quality from 0 to 10 rather than a
     * bitrate; the encoder is scaled across its usable range accordingly.
     */
    private fun currentChannelBitrate(): Int {
        val quality = channelsById[_connectionState.value.currentChannelId]
            ?.codecQuality
            ?.coerceIn(0, MAX_CODEC_QUALITY)
            ?: DEFAULT_CODEC_QUALITY
        val span = MAX_VOICE_BITRATE - MIN_VOICE_BITRATE
        return MIN_VOICE_BITRATE + span * quality / MAX_CODEC_QUALITY
    }

    // ----------------------------------------------------------------- utils

    private suspend fun <T> runCommand(block: suspend () -> Result<T>): Result<T> =
        commandMutex.withLock {
            if (!client.isConnected) {
                Result.failure(IllegalStateException("未连接到服务器"))
            } else {
                runCatching { block() }.getOrElse { Result.failure(it) }
            }
        }

    private fun emit(event: ServerEvent) {
        _events.tryEmit(event)
    }

    private fun channelTitle(channelId: Int): String =
        channelsById[channelId]?.displayName ?: "?"

    private fun Boolean.flag(): String = if (this) "1" else "0"

    private fun now(): Long = System.currentTimeMillis()

    private fun describe(t: Throwable): String = t.message?.takeIf { it.isNotBlank() }
        ?: t::class.java.simpleName

    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        while (true) {
            val current = value
            if (compareAndSet(current, transform(current))) return
        }
    }

    private companion object {
        const val MAX_MESSAGES = 500
        const val PING_INTERVAL_MS = 2_000L
        const val KICK_REASON_MAX_LENGTH = 40
        const val MAX_CODEC_QUALITY = 10
        const val DEFAULT_CODEC_QUALITY = 6

        /** Encoder bitrate range mapped from the channel's codec quality. */
        const val MIN_VOICE_BITRATE = 16_000
        const val MAX_VOICE_BITRATE = 96_000

        /** `reasonid` values the server sends with leave / move notifications. */
        const val REASON_KICK_CHANNEL = 4
        const val REASON_KICK_SERVER = 5
        const val REASON_BAN = 6
    }
}
