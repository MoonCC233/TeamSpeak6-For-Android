package com.mooncc.teamspeak9.domain.repository

import com.mooncc.teamspeak9.domain.model.Bookmark
import com.mooncc.teamspeak9.domain.model.Channel
import com.mooncc.teamspeak9.domain.model.ChannelGroup
import com.mooncc.teamspeak9.domain.model.ChatMessage
import com.mooncc.teamspeak9.domain.model.ChatTarget
import com.mooncc.teamspeak9.domain.model.Client
import com.mooncc.teamspeak9.domain.model.ConnectionState
import com.mooncc.teamspeak9.domain.model.Conversation
import com.mooncc.teamspeak9.domain.model.LocalMediaState
import com.mooncc.teamspeak9.domain.model.Permission
import com.mooncc.teamspeak9.domain.model.ServerEvent
import com.mooncc.teamspeak9.domain.model.ServerGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the live connection to a virtual server: state, channel tree,
 * conversations, and the command surface the UI drives.
 */
interface TeamSpeakRepository {

    val connectionState: StateFlow<ConnectionState>
    val channelTree: StateFlow<List<Channel>>
    val clients: StateFlow<List<Client>>
    val serverGroups: StateFlow<List<ServerGroup>>
    val channelGroups: StateFlow<List<ChannelGroup>>
    val myPermissions: StateFlow<List<Permission>>
    val conversations: StateFlow<List<Conversation>>
    val localMediaState: StateFlow<LocalMediaState>
    val events: SharedFlow<ServerEvent>

    suspend fun connect(bookmark: Bookmark)
    suspend fun disconnect()
    suspend fun refreshNow()

    suspend fun joinChannel(channelId: Int, password: String = "")
    suspend fun createChannel(
        name: String,
        parentId: Int,
        password: String = "",
        topic: String = "",
        description: String = "",
        permanent: Boolean = false,
        semiPermanent: Boolean = false,
        maxClients: Int = -1,
    ): Result<Int>

    suspend fun editChannel(channelId: Int, properties: Map<String, String>): Result<Unit>
    suspend fun deleteChannel(channelId: Int, force: Boolean = false): Result<Unit>

    suspend fun sendMessage(target: ChatTarget, conversationId: Int, text: String): Result<Unit>
    fun observeConversation(target: ChatTarget, conversationId: Int): Flow<List<ChatMessage>>
    fun markConversationRead(target: ChatTarget, conversationId: Int)
    fun openConversation(target: ChatTarget, conversationId: Int, title: String)
    fun closeConversation(target: ChatTarget, conversationId: Int)

    suspend fun moveClient(clientId: Int, channelId: Int, password: String = ""): Result<Unit>
    suspend fun kickFromChannel(clientId: Int, reason: String): Result<Unit>
    suspend fun kickFromServer(clientId: Int, reason: String): Result<Unit>
    suspend fun banClient(clientId: Int, durationSeconds: Long, reason: String): Result<Unit>
    suspend fun poke(clientId: Int, message: String): Result<Unit>
    suspend fun addToServerGroup(groupId: Int, clientDatabaseId: Int): Result<Unit>
    suspend fun removeFromServerGroup(groupId: Int, clientDatabaseId: Int): Result<Unit>
    suspend fun setChannelGroup(groupId: Int, channelId: Int, clientDatabaseId: Int): Result<Unit>

    suspend fun setNickname(nickname: String): Result<Unit>
    suspend fun setMicMuted(muted: Boolean)
    suspend fun setSpeakerMuted(muted: Boolean)
    suspend fun setPushToTalkEnabled(enabled: Boolean)
    suspend fun setPushToTalkActive(active: Boolean)
    suspend fun setAway(away: Boolean, message: String = "")
    suspend fun setChannelCommander(enabled: Boolean): Result<Unit>
    fun updateLocalMedia(transform: (LocalMediaState) -> LocalMediaState)
}
