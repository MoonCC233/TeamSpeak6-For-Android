package com.mooncc.teamspeak6.data.remote

import com.mooncc.teamspeak6.data.remote.WebQueryClient.Companion.bool
import com.mooncc.teamspeak6.data.remote.WebQueryClient.Companion.int
import com.mooncc.teamspeak6.data.remote.WebQueryClient.Companion.intList
import com.mooncc.teamspeak6.data.remote.WebQueryClient.Companion.long
import com.mooncc.teamspeak6.data.remote.WebQueryClient.Companion.str
import com.mooncc.teamspeak6.domain.model.Channel
import com.mooncc.teamspeak6.domain.model.ChannelCodec
import com.mooncc.teamspeak6.domain.model.ChannelGroup
import com.mooncc.teamspeak6.domain.model.ChannelSpacer
import com.mooncc.teamspeak6.domain.model.Client
import com.mooncc.teamspeak6.domain.model.ClientType
import com.mooncc.teamspeak6.domain.model.GroupType
import com.mooncc.teamspeak6.domain.model.OfflineMessage
import com.mooncc.teamspeak6.domain.model.Permission
import com.mooncc.teamspeak6.domain.model.ServerGroup
import com.mooncc.teamspeak6.domain.model.SpacerAlignment
import com.mooncc.teamspeak6.domain.model.VirtualServer
import kotlinx.serialization.json.JsonObject

/**
 * Maps raw WebQuery rows onto domain models.
 */
object QueryMappers {

    fun toVirtualServer(row: JsonObject): VirtualServer = VirtualServer(
        id = row.int("virtualserver_id", row.int("id", 1)),
        uniqueIdentifier = row.str("virtualserver_unique_identifier"),
        name = row.str("virtualserver_name", "TeamSpeak Server"),
        welcomeMessage = row.str("virtualserver_welcomemessage"),
        platform = row.str("virtualserver_platform"),
        version = row.str("virtualserver_version"),
        clientsOnline = row.int("virtualserver_clientsonline"),
        queryClientsOnline = row.int("virtualserver_queryclientsonline"),
        maxClients = row.int("virtualserver_maxclients"),
        channelsOnline = row.int("virtualserver_channelsonline"),
        uptimeSeconds = row.long("virtualserver_uptime"),
        hostMessage = row.str("virtualserver_hostmessage"),
        hostBannerUrl = row.str("virtualserver_hostbanner_url"),
        hostBannerGfxUrl = row.str("virtualserver_hostbanner_gfx_url"),
        iconId = row.long("virtualserver_icon_id"),
    )

    fun toChannel(row: JsonObject): Channel {
        val rawName = row.str("channel_name")
        val spacer = ChannelSpacer.parse(rawName)
        return Channel(
            id = row.int("cid", row.int("channel_id")),
            parentId = row.int("pid", row.int("cpid")),
            order = row.int("channel_order"),
            name = rawName,
            topic = row.str("channel_topic"),
            description = row.str("channel_description"),
            hasPassword = row.bool("channel_flag_password"),
            codec = ChannelCodec.fromId(row.int("channel_codec", 4)),
            codecQuality = row.int("channel_codec_quality", 6),
            maxClients = if (row.bool("channel_flag_maxclients_unlimited", true)) {
                -1
            } else {
                row.int("channel_maxclients", -1)
            },
            maxFamilyClients = if (row.bool("channel_flag_maxfamilyclients_unlimited", true)) {
                -1
            } else {
                row.int("channel_maxfamilyclients", -1)
            },
            isPermanent = row.bool("channel_flag_permanent", true),
            isSemiPermanent = row.bool("channel_flag_semi_permanent"),
            isDefault = row.bool("channel_flag_default"),
            isSpacer = spacer != null,
            spacerAlignment = spacer?.alignment ?: SpacerAlignment.NONE,
            spacerLabel = spacer?.label ?: "",
            neededTalkPower = row.int("channel_needed_talk_power"),
            iconId = row.long("channel_icon_id"),
            totalClients = row.int("total_clients", -1).coerceAtLeast(0),
            totalClientsFamily = row.int("total_clients_family", -1).coerceAtLeast(0),
        )
    }

    fun toClient(row: JsonObject, localClientId: Int = 0): Client {
        val id = row.int("clid", row.int("client_id"))
        return Client(
            id = id,
            channelId = row.int("cid", row.int("client_channel_id")),
            databaseId = row.int("client_database_id"),
            uniqueIdentifier = row.str("client_unique_identifier"),
            nickname = row.str("client_nickname", "unknown"),
            description = row.str("client_description"),
            type = ClientType.fromId(row.int("client_type")),
            inputMuted = row.bool("client_input_muted"),
            outputMuted = row.bool("client_output_muted"),
            outputOnlyMuted = row.bool("client_outputonly_muted"),
            inputHardware = row.bool("client_input_hardware", true),
            outputHardware = row.bool("client_output_hardware", true),
            isRecording = row.bool("client_is_recording"),
            isChannelCommander = row.bool("client_is_channel_commander"),
            isPrioritySpeaker = row.bool("client_is_priority_speaker"),
            isAway = row.bool("client_away"),
            awayMessage = row.str("client_away_message"),
            talkPower = row.int("client_talk_power"),
            isTalker = row.bool("client_is_talker"),
            isRequestingTalkPower = row.bool("client_talk_request"),
            talkRequestMessage = row.str("client_talk_request_msg"),
            serverGroups = row.intList("client_servergroups"),
            channelGroupId = row.int("client_channel_group_id"),
            platform = row.str("client_platform"),
            version = row.str("client_version"),
            country = row.str("client_country"),
            idleTimeMs = row.long("client_idle_time"),
            connectedTimeMs = row.long("connection_connected_time"),
            iconId = row.long("client_icon_id"),
            avatarFlag = row.str("client_flag_avatar"),
            isLocal = id != 0 && id == localClientId,
        )
    }

    fun toServerGroup(row: JsonObject): ServerGroup = ServerGroup(
        id = row.int("sgid", row.int("group_id")),
        name = row.str("name"),
        type = GroupType.fromId(row.int("type", 1)),
        iconId = row.long("iconid"),
        sortId = row.int("sortid"),
        savedb = row.bool("savedb", true),
        memberAddPower = row.int("n_member_addp"),
        memberRemovePower = row.int("n_member_removep"),
        nameMode = row.int("namemode"),
    )

    fun toChannelGroup(row: JsonObject): ChannelGroup = ChannelGroup(
        id = row.int("cgid", row.int("group_id")),
        name = row.str("name"),
        type = GroupType.fromId(row.int("type", 1)),
        iconId = row.long("iconid"),
        sortId = row.int("sortid"),
        savedb = row.bool("savedb", true),
    )

    fun toPermission(row: JsonObject): Permission = Permission(
        id = row.int("permid", row.int("p")),
        name = row.str("permsid", row.str("permname")),
        value = row.int("permvalue", row.int("v")),
        negated = row.bool("permnegated", row.bool("n")),
        skipped = row.bool("permskip", row.bool("s")),
        grantValue = row.int("permgrant"),
    )

    fun toOfflineMessage(row: JsonObject): OfflineMessage = OfflineMessage(
        id = row.int("msgid"),
        senderUniqueIdentifier = row.str("cluid"),
        subject = row.str("subject"),
        message = row.str("message"),
        timestampMs = row.long("timestamp") * 1000L,
        isRead = row.bool("flag_read"),
    )

}
