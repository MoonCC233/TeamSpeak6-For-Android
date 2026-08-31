package com.mooncc.teamspeak6.data.remote

import com.mooncc.teamspeak6.domain.model.Channel
import com.mooncc.teamspeak6.domain.model.ChannelGroup
import com.mooncc.teamspeak6.domain.model.Client
import com.mooncc.teamspeak6.domain.model.OfflineMessage
import com.mooncc.teamspeak6.domain.model.Permission
import com.mooncc.teamspeak6.domain.model.ServerGroup
import com.mooncc.teamspeak6.domain.model.VirtualServer
import kotlinx.serialization.json.JsonObject

/**
 * High level wrapper around [WebQueryClient] exposing the TeamSpeak commands the
 * app needs, already mapped onto domain models.
 */
class TeamSpeakQueryApi(
    private val client: WebQueryClient,
    private val virtualServerId: Int,
) {

    // ---------------------------------------------------------------- server

    suspend fun serverInfo(): VirtualServer =
        QueryMappers.toVirtualServer(
            client.executeSingle("serverinfo", virtualServerId) ?: JsonObject(emptyMap()),
        ).copy(id = virtualServerId)

    suspend fun whoAmI(): JsonObject? = client.whoAmI()

    suspend fun serverList(): List<VirtualServer> =
        client.execute("serverlist").map(QueryMappers::toVirtualServer)

    suspend fun sendServerMessage(text: String) {
        client.executeVoid(
            "sendtextmessage",
            virtualServerId,
            mapOf("targetmode" to "3", "target" to "0", "msg" to text),
        )
    }

    // --------------------------------------------------------------- channels

    suspend fun channelList(): List<Channel> = client.execute(
        command = "channellist",
        virtualServerId = virtualServerId,
        flags = listOf("-topic", "-flags", "-voice", "-limits", "-icon"),
    ).map(QueryMappers::toChannel)

    suspend fun channelInfo(channelId: Int): Channel? =
        client.executeSingle("channelinfo", virtualServerId, mapOf("cid" to channelId.toString()))
            ?.let { QueryMappers.toChannel(it).copy(id = channelId) }

    suspend fun createChannel(
        name: String,
        parentId: Int = 0,
        password: String = "",
        topic: String = "",
        description: String = "",
        permanent: Boolean = false,
        semiPermanent: Boolean = false,
        maxClients: Int = -1,
    ): Int {
        val params = buildMap {
            put("channel_name", name)
            if (parentId != 0) put("cpid", parentId.toString())
            if (password.isNotEmpty()) put("channel_password", password)
            if (topic.isNotEmpty()) put("channel_topic", topic)
            if (description.isNotEmpty()) put("channel_description", description)
            put("channel_flag_permanent", if (permanent) "1" else "0")
            put("channel_flag_semi_permanent", if (semiPermanent) "1" else "0")
            if (maxClients >= 0) {
                put("channel_flag_maxclients_unlimited", "0")
                put("channel_maxclients", maxClients.toString())
            }
        }
        val row = client.executeSingle("channelcreate", virtualServerId, params)
        return with(WebQueryClient.Companion) { row?.int("cid") ?: 0 }
    }

    suspend fun editChannel(channelId: Int, properties: Map<String, String>) {
        client.executeVoid(
            "channeledit",
            virtualServerId,
            properties + ("cid" to channelId.toString()),
        )
    }

    suspend fun deleteChannel(channelId: Int, force: Boolean = false) {
        client.executeVoid(
            "channeldelete",
            virtualServerId,
            mapOf("cid" to channelId.toString(), "force" to if (force) "1" else "0"),
        )
    }

    suspend fun sendChannelMessage(text: String) {
        client.executeVoid(
            "sendtextmessage",
            virtualServerId,
            mapOf("targetmode" to "2", "target" to "0", "msg" to text),
        )
    }

    // ---------------------------------------------------------------- clients

    suspend fun clientList(localClientId: Int = 0): List<Client> = client.execute(
        command = "clientlist",
        virtualServerId = virtualServerId,
        flags = listOf(
            "-uid", "-away", "-voice", "-times", "-groups",
            "-info", "-country", "-icon",
        ),
    ).map { QueryMappers.toClient(it, localClientId) }

    suspend fun clientInfo(clientId: Int, localClientId: Int = 0): Client? =
        client.executeSingle("clientinfo", virtualServerId, mapOf("clid" to clientId.toString()))
            ?.let { QueryMappers.toClient(it, localClientId).copy(id = clientId) }

    suspend fun moveClient(clientId: Int, channelId: Int, channelPassword: String = "") {
        val params = buildMap {
            put("clid", clientId.toString())
            put("cid", channelId.toString())
            if (channelPassword.isNotEmpty()) put("cpw", channelPassword)
        }
        client.executeVoid("clientmove", virtualServerId, params)
    }

    suspend fun kickFromChannel(clientId: Int, reason: String = "") {
        kick(clientId, reasonId = 4, reason = reason)
    }

    suspend fun kickFromServer(clientId: Int, reason: String = "") {
        kick(clientId, reasonId = 5, reason = reason)
    }

    private suspend fun kick(clientId: Int, reasonId: Int, reason: String) {
        val params = buildMap {
            put("clid", clientId.toString())
            put("reasonid", reasonId.toString())
            if (reason.isNotEmpty()) put("reasonmsg", reason)
        }
        client.executeVoid("clientkick", virtualServerId, params)
    }

    suspend fun banClient(clientId: Int, durationSeconds: Long = 0, reason: String = "") {
        val params = buildMap {
            put("clid", clientId.toString())
            if (durationSeconds > 0) put("time", durationSeconds.toString())
            if (reason.isNotEmpty()) put("banreason", reason)
        }
        client.executeVoid("banclient", virtualServerId, params)
    }

    suspend fun poke(clientId: Int, message: String) {
        client.executeVoid(
            "clientpoke",
            virtualServerId,
            mapOf("clid" to clientId.toString(), "msg" to message),
        )
    }

    suspend fun sendPrivateMessage(clientId: Int, text: String) {
        client.executeVoid(
            "sendtextmessage",
            virtualServerId,
            mapOf("targetmode" to "1", "target" to clientId.toString(), "msg" to text),
        )
    }

    suspend fun updateSelf(properties: Map<String, String>) {
        client.executeVoid("clientupdate", virtualServerId, properties)
    }

    suspend fun editClient(clientId: Int, properties: Map<String, String>) {
        client.executeVoid(
            "clientedit",
            virtualServerId,
            properties + ("clid" to clientId.toString()),
        )
    }

    // ----------------------------------------------------------------- groups

    suspend fun serverGroups(): List<ServerGroup> =
        client.execute("servergrouplist", virtualServerId).map(QueryMappers::toServerGroup)

    suspend fun channelGroups(): List<ChannelGroup> =
        client.execute("channelgrouplist", virtualServerId).map(QueryMappers::toChannelGroup)

    suspend fun addClientToServerGroup(groupId: Int, clientDatabaseId: Int) {
        client.executeVoid(
            "servergroupaddclient",
            virtualServerId,
            mapOf("sgid" to groupId.toString(), "cldbid" to clientDatabaseId.toString()),
        )
    }

    suspend fun removeClientFromServerGroup(groupId: Int, clientDatabaseId: Int) {
        client.executeVoid(
            "servergroupdelclient",
            virtualServerId,
            mapOf("sgid" to groupId.toString(), "cldbid" to clientDatabaseId.toString()),
        )
    }

    suspend fun setChannelGroup(groupId: Int, channelId: Int, clientDatabaseId: Int) {
        client.executeVoid(
            "setclientchannelgroup",
            virtualServerId,
            mapOf(
                "cgid" to groupId.toString(),
                "cid" to channelId.toString(),
                "cldbid" to clientDatabaseId.toString(),
            ),
        )
    }

    // ------------------------------------------------------------ permissions

    suspend fun myPermissions(): List<Permission> =
        client.execute("permoverview", virtualServerId, mapOf("cid" to "0", "cldbid" to "0"))
            .map(QueryMappers::toPermission)

    suspend fun channelPermissions(channelId: Int): List<Permission> =
        client.execute("channelpermlist", virtualServerId, mapOf("cid" to channelId.toString()))
            .map(QueryMappers::toPermission)

    suspend fun serverGroupPermissions(groupId: Int): List<Permission> =
        client.execute("servergrouppermlist", virtualServerId, mapOf("sgid" to groupId.toString()))
            .map(QueryMappers::toPermission)

    // --------------------------------------------------------------- messages

    suspend fun offlineMessages(): List<OfflineMessage> =
        client.execute("messagelist", virtualServerId).map(QueryMappers::toOfflineMessage)

    suspend fun sendOfflineMessage(targetUniqueId: String, subject: String, message: String) {
        client.executeVoid(
            "messageadd",
            virtualServerId,
            mapOf("cluid" to targetUniqueId, "subject" to subject, "message" to message),
        )
    }

    suspend fun deleteOfflineMessage(messageId: Int) {
        client.executeVoid("messagedel", virtualServerId, mapOf("msgid" to messageId.toString()))
    }
}
