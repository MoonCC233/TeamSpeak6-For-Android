package com.mooncc.teamspeak6.voice.client

import android.util.Log
import com.github.manevolent.ts3j.command.Command
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandOption
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.event.ChannelCreateEvent
import com.github.manevolent.ts3j.event.ChannelDeletedEvent
import com.github.manevolent.ts3j.event.ChannelDescriptionEditedEvent
import com.github.manevolent.ts3j.event.ChannelEditedEvent
import com.github.manevolent.ts3j.event.ChannelGroupListEvent
import com.github.manevolent.ts3j.event.ChannelListEvent
import com.github.manevolent.ts3j.event.ChannelMovedEvent
import com.github.manevolent.ts3j.event.ClientChannelGroupChangedEvent
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.ClientLeaveEvent
import com.github.manevolent.ts3j.event.ClientMovedEvent
import com.github.manevolent.ts3j.event.ClientPokeEvent
import com.github.manevolent.ts3j.event.ClientUpdatedEvent
import com.github.manevolent.ts3j.event.ConnectedEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.ServerEditedEvent
import com.github.manevolent.ts3j.event.ServerGroupClientAddedEvent
import com.github.manevolent.ts3j.event.ServerGroupClientDeletedEvent
import com.github.manevolent.ts3j.event.ServerGroupListEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.event.TextMessageEvent
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.TS3DNS
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import com.mooncc.teamspeak6.voice.audio.VoiceCaptureEngine
import com.mooncc.teamspeak6.voice.audio.VoicePlaybackEngine
import com.mooncc.teamspeak6.voice.identity.IdentityManager
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Speaks the native TeamSpeak 3/6 UDP protocol, so the app connects to a plain
 * server address exactly like the desktop client does — no server-side bridge or
 * query credentials involved.
 *
 * ts3j exposes a blocking, callback-driven API. This wrapper moves every
 * blocking call onto [Dispatchers.IO], turns notifications into a [Ts3Event]
 * flow, and adds a generic [command] escape hatch for the many commands ts3j
 * has no helper for (channel editing, group listing, `whoami`, …).
 */
internal class Ts3VoiceClient(
    private val identityManager: IdentityManager,
    val capture: VoiceCaptureEngine,
    val playback: VoicePlaybackEngine,
) {

    private val connectMutex = Mutex()

    private val _events = MutableSharedFlow<Ts3Event>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Ts3Event> = _events.asSharedFlow()

    @Volatile
    private var socket: LocalTeamspeakClientSocket? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    val localClientId: Int get() = socket?.clientId ?: 0

    /** Own unique identifier (the identity UID), available before connecting. */
    suspend fun uniqueIdentifier(): String = identityManager.identity().uid.toBase64()

    // --- lifecycle -----------------------------------------------------------

    /**
     * Connects and blocks until the server finished sending the initial channel
     * and client lists.
     */
    suspend fun connect(
        host: String,
        port: Int,
        nickname: String,
        serverPassword: String,
        defaultChannel: String,
        defaultChannelPassword: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): Result<Unit> = connectMutex.withLock {
        if (socket != null) disconnectInternal()
        val identity = identityManager.identity()

        withContext(Dispatchers.IO) {
            runCatching {
                val client = LocalTeamspeakClientSocket()
                client.setIdentity(identity)
                client.setClientVersion(CLIENT_PLATFORM, CLIENT_VERSION, CLIENT_VERSION_SIGN)
                client.setNickname(nickname)
                client.setOption("client.hwid", HWID)
                if (defaultChannel.isNotBlank()) {
                    client.setOption("client.default_channel", defaultChannel)
                }
                if (defaultChannelPassword.isNotBlank()) {
                    client.setOption("client.default_channel_password", defaultChannelPassword)
                }
                client.setEventMultiThreading(false)
                client.setExceptionHandler { throwable ->
                    Log.w(TAG, "ts3j internal error", throwable)
                    _events.tryEmit(Ts3Event.Failure(throwable.describe()))
                }
                client.addListener(EventBridge())
                client.setVoiceHandler { packet ->
                    playback.submit(packet.clientId, packet.packetId, packet.codecData ?: EMPTY)
                }
                client.microphone = capture

                socket = client
                try {
                    client.connect(resolve(host, port), serverPassword.ifBlank { null }, timeoutMs)
                    client.subscribeAll()
                    Unit
                } catch (t: Throwable) {
                    socket = null
                    runCatching { client.close() }
                    throw t
                }
            }
        }
    }

    suspend fun disconnect(reason: String = "") = withContext(Dispatchers.IO) {
        connectMutex.withLock { disconnectInternal(reason) }
    }

    private fun disconnectInternal(reason: String = "") {
        val client = socket ?: return
        socket = null
        runCatching { client.disconnect(reason) }
            .onFailure { Log.w(TAG, "graceful disconnect failed", it) }
        runCatching { client.close() }
    }

    /**
     * TS3 servers are usually published as a `_ts3._udp` SRV record, but dnsjava
     * can fail outright on Android (no `/etc/resolv.conf`), and an explicit port
     * always wins anyway — so SRV is only consulted for default-port hosts and
     * any failure falls back to a direct address.
     */
    private fun resolve(host: String, port: Int): InetSocketAddress {
        if (port != DEFAULT_VOICE_PORT) return InetSocketAddress(host, port)
        val srv = runCatching { TS3DNS.lookup(host).firstOrNull() }.getOrNull()
        return srv ?: InetSocketAddress(host, port)
    }

    // --- generic command gateway --------------------------------------------

    /**
     * Sends an arbitrary command and returns one map per reply row.
     *
     * ts3j only wraps a fraction of the client command set, but its transport is
     * generic: rows come back as `SingleCommand`s whose maps are keyed by the
     * same protocol field names the server query uses.
     *
     * Values are passed through verbatim — ts3j escapes them on serialisation,
     * so pre-escaping would double-encode.
     */
    suspend fun command(
        name: String,
        params: Map<String, String> = emptyMap(),
        options: List<String> = emptyList(),
    ): Result<List<Map<String, String>>> = withContext(Dispatchers.IO) {
        val client = socket
        if (client == null || !client.isConnected) {
            return@withContext Result.failure(IOException("not connected"))
        }
        runCatching {
            val command: Command = SingleCommand(name, ProtocolRole.CLIENT)
            params.forEach { (key, value) ->
                command.add(CommandSingleParameter(key, value))
            }
            options.forEach { option ->
                command.add(CommandOption(option).apply { set(true) })
            }
            client.executeCommand(command).get(COMMAND_TIMEOUT_MS).map { it.toMap() }
        }
    }

    /** Runs a command and discards the reply rows. */
    suspend fun execute(
        name: String,
        params: Map<String, String> = emptyMap(),
        options: List<String> = emptyList(),
    ): Result<Unit> = command(name, params, options).map { }

    // --- convenience wrappers ------------------------------------------------

    /**
     * `channellist` with every optional block requested; the bare command only
     * returns ids and names.
     */
    suspend fun listChannels(): Result<List<Map<String, String>>> =
        command("channellist", options = CHANNEL_LIST_OPTIONS)

    suspend fun listClients(): Result<List<Map<String, String>>> =
        command("clientlist", options = CLIENT_LIST_OPTIONS)

    suspend fun serverInfo(): Result<Map<String, String>> =
        command("serverinfo").map { it.firstOrNull().orEmpty() }

    suspend fun whoAmI(): Result<Map<String, String>> =
        command("whoami").map { it.firstOrNull().orEmpty() }

    suspend fun clientInfo(clientId: Int): Result<Map<String, String>> =
        command("clientinfo", mapOf("clid" to clientId.toString()))
            .map { it.firstOrNull().orEmpty() }

    suspend fun channelInfo(channelId: Int): Result<Map<String, String>> =
        command("channelinfo", mapOf("cid" to channelId.toString()))
            .map { it.firstOrNull().orEmpty() }

    suspend fun listServerGroups(): Result<List<Map<String, String>>> = command("servergrouplist")

    suspend fun listChannelGroups(): Result<List<Map<String, String>>> = command("channelgrouplist")

    /** Ping to the server in milliseconds, or `0` while disconnected. */
    val pingMs: Long
        get() = socket?.let { runCatching { it.ping.key.toLong() }.getOrDefault(0L) } ?: 0L

    // --- events --------------------------------------------------------------

    private inner class EventBridge : TS3Listener {

        override fun onConnected(e: ConnectedEvent) {
            emit(Ts3Event.Connected(e.map.orEmpty()))
        }

        override fun onDisconnected(e: DisconnectedEvent) {
            emit(Ts3Event.Disconnected(e.reasonId, e.reasonMessage.orEmpty()))
        }

        override fun onChannelList(e: ChannelListEvent) {
            emit(Ts3Event.ChannelListReceived(e.map.orEmpty()))
        }

        override fun onChannelCreate(e: ChannelCreateEvent) {
            emit(Ts3Event.ChannelCreated(e.channelId, e.map.orEmpty()))
        }

        override fun onChannelEdit(e: ChannelEditedEvent) {
            emit(Ts3Event.ChannelEdited(e.channelId, e.map.orEmpty()))
        }

        override fun onChannelDescriptionChanged(e: ChannelDescriptionEditedEvent) {
            emit(Ts3Event.ChannelEdited(e.channelId, e.map.orEmpty()))
        }

        override fun onChannelMoved(e: ChannelMovedEvent) {
            emit(Ts3Event.ChannelMoved(e.channelId, e.channelParentId, e.channelOrder))
        }

        override fun onChannelDeleted(e: ChannelDeletedEvent) {
            emit(Ts3Event.ChannelDeleted(e.channelId))
        }

        override fun onClientJoin(e: ClientJoinEvent) {
            val properties = e.map.orEmpty()
            emit(Ts3Event.ClientJoined(e.clientId, e.clientTargetId, properties))
        }

        override fun onClientLeave(e: ClientLeaveEvent) {
            playback.removeStream(e.clientId)
            emit(
                Ts3Event.ClientLeft(
                    clientId = e.clientId,
                    reasonId = e.reasonId,
                    reasonMessage = e.reasonMessage.orEmpty(),
                    invokerName = e.invokerName.orEmpty(),
                ),
            )
        }

        override fun onClientMoved(e: ClientMovedEvent) {
            emit(
                Ts3Event.ClientMoved(
                    clientId = e.clientId,
                    targetChannelId = e.targetChannelId,
                    invokerName = e.invokerName.orEmpty(),
                    reasonId = e.reasonId,
                    reasonMessage = e.reasonMessage.orEmpty(),
                ),
            )
        }

        override fun onClientChanged(e: ClientUpdatedEvent) {
            emit(Ts3Event.ClientUpdated(e.clientId, e.map.orEmpty()))
        }

        override fun onClientChannelGroupChanged(e: ClientChannelGroupChangedEvent) {
            emit(
                Ts3Event.ClientChannelGroupChanged(
                    clientId = e.clientId,
                    channelGroupId = e.channelGroupId,
                    channelId = e.channelId,
                ),
            )
        }

        override fun onServerGroupClientAdded(e: ServerGroupClientAddedEvent) {
            emit(Ts3Event.ClientServerGroupChanged(e.clientId, e.serverGroupId, added = true))
        }

        override fun onServerGroupClientDeleted(e: ServerGroupClientDeletedEvent) {
            emit(Ts3Event.ClientServerGroupChanged(e.clientId, e.serverGroupId, added = false))
        }

        override fun onTextMessage(e: TextMessageEvent) {
            emit(
                Ts3Event.TextMessage(
                    targetMode = e.targetMode?.index ?: Ts3Event.TARGET_CHANNEL,
                    message = e.message.orEmpty(),
                    invokerId = e.invokerId,
                    invokerName = e.invokerName.orEmpty(),
                    invokerUniqueId = e.invokerUniqueId.orEmpty(),
                    targetClientId = e.targetClientId,
                ),
            )
        }

        override fun onClientPoke(e: ClientPokeEvent) {
            emit(Ts3Event.Poked(e.invokerId, e.invokerName.orEmpty(), e.message.orEmpty()))
        }

        override fun onServerEdit(e: ServerEditedEvent) {
            emit(Ts3Event.ServerEdited(e.map.orEmpty()))
        }

        override fun onServerGroupList(e: ServerGroupListEvent) {
            emit(Ts3Event.ServerGroupListReceived(e.map.orEmpty()))
        }

        override fun onChannelGroupList(e: ChannelGroupListEvent) {
            emit(Ts3Event.ChannelGroupListReceived(e.map.orEmpty()))
        }

        private fun emit(event: Ts3Event) {
            _events.tryEmit(event)
        }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    companion object {
        private const val TAG = "Ts3VoiceClient"

        const val DEFAULT_VOICE_PORT = 9987
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val COMMAND_TIMEOUT_MS = 10_000L

        /**
         * Servers verify `client_version_sign` against TeamSpeak's signing key,
         * so a client can only present a version triple that TeamSpeak itself
         * has already signed.
         */
        private const val CLIENT_PLATFORM = "Windows"
        private const val CLIENT_VERSION = "3.0.19.3 [Build: 1466672534]"
        private const val CLIENT_VERSION_SIGN =
            "a1OYzvM18mrmfUQBUgxYBxYz2DUU6y5k3/mEL6FurzU0y97Bd1FL7+PRpcHyPkg4R+kKAFZ1nhyzbgkGphDWDg=="
        private const val HWID = "+LyYqbDqOvEEpN5pdAbF8/v5kZ0="

        private val EMPTY = ByteArray(0)

        private val CHANNEL_LIST_OPTIONS =
            listOf("topic", "flags", "voice", "limits", "icon", "secondsempty")
        private val CLIENT_LIST_OPTIONS =
            listOf("uid", "away", "voice", "groups", "info", "times", "icon", "country")
    }
}
