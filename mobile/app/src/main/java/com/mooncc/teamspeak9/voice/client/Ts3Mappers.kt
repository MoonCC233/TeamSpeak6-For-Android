package com.mooncc.teamspeak9.voice.client

import com.mooncc.teamspeak9.domain.model.Channel
import com.mooncc.teamspeak9.domain.model.ChannelCodec
import com.mooncc.teamspeak9.domain.model.ChannelGroup
import com.mooncc.teamspeak9.domain.model.ChannelSpacer
import com.mooncc.teamspeak9.domain.model.Client
import com.mooncc.teamspeak9.domain.model.ClientType
import com.mooncc.teamspeak9.domain.model.GroupType
import com.mooncc.teamspeak9.domain.model.Permission
import com.mooncc.teamspeak9.domain.model.ServerGroup
import com.mooncc.teamspeak9.domain.model.SpacerAlignment
import com.mooncc.teamspeak9.domain.model.VirtualServer

/**
 * Maps raw TeamSpeak protocol rows onto domain models.
 *
 * Every row coming out of ts3j — whether from a `channellist`-style reply or
 * from an event notification — is ultimately a `Map<String, String>` keyed by
 * the protocol's native field names. Working directly on the map (rather than
 * ts3j's typed wrappers) keeps a single code path for both, and avoids ts3j's
 * habit of returning `-1` or throwing for absent keys.
 */
internal object Ts3Mappers {

    fun toChannel(row: Map<String, String>): Channel {
        val name = row.str("channel_name")
        val spacer = ChannelSpacer.parse(name)
        val unlimitedClients = row.flag("channel_flag_maxclients_unlimited", default = true)
        val unlimitedFamily = row.flag("channel_flag_maxfamilyclients_unlimited", default = true)
        return Channel(
            id = row.int("cid"),
            parentId = row.int("pid"),
            order = row.int("channel_order"),
            name = name,
            topic = row.str("channel_topic"),
            description = row.str("channel_description"),
            hasPassword = row.flag("channel_flag_password"),
            codec = ChannelCodec.fromId(row.int("channel_codec", default = 4)),
            codecQuality = row.int("channel_codec_quality", default = 6),
            maxClients = if (unlimitedClients) -1 else row.int("channel_maxclients", default = -1),
            maxFamilyClients = if (unlimitedFamily) -1 else row.int("channel_maxfamilyclients", default = -1),
            isPermanent = row.flag("channel_flag_permanent", default = true),
            isSemiPermanent = row.flag("channel_flag_semi_permanent"),
            isDefault = row.flag("channel_flag_default"),
            isSpacer = spacer != null,
            spacerAlignment = spacer?.alignment ?: SpacerAlignment.NONE,
            spacerLabel = spacer?.label.orEmpty(),
            neededTalkPower = row.int("channel_needed_talk_power"),
            iconId = row.long("channel_icon_id"),
            totalClients = row.int("total_clients"),
            totalClientsFamily = row.int("total_clients_family"),
        )
    }

    fun toClient(row: Map<String, String>, localClientId: Int): Client {
        val id = row.int("clid")
        return Client(
            id = id,
            channelId = row.int("cid"),
            databaseId = row.int("client_database_id"),
            uniqueIdentifier = row.str("client_unique_identifier"),
            nickname = row.str("client_nickname").ifBlank { "client $id" },
            description = row.str("client_description"),
            type = ClientType.fromId(row.int("client_type")),
            inputMuted = row.flag("client_input_muted"),
            outputMuted = row.flag("client_output_muted"),
            outputOnlyMuted = row.flag("client_outputonly_muted"),
            inputHardware = row.flag("client_input_hardware", default = true),
            outputHardware = row.flag("client_output_hardware", default = true),
            isRecording = row.flag("client_is_recording"),
            isChannelCommander = row.flag("client_is_channel_commander"),
            isPrioritySpeaker = row.flag("client_is_priority_speaker"),
            isAway = row.flag("client_away"),
            awayMessage = row.str("client_away_message"),
            talkPower = row.int("client_talk_power"),
            isTalker = row.flag("client_is_talker"),
            isRequestingTalkPower = row.flag("client_talk_request"),
            talkRequestMessage = row.str("client_talk_request_msg"),
            serverGroups = row.intList("client_servergroups"),
            channelGroupId = row.int("client_channel_group_id"),
            platform = row.str("client_platform"),
            version = row.str("client_version"),
            country = row.str("client_country"),
            idleTimeMs = row.long("client_idle_time"),
            iconId = row.long("client_icon_id"),
            avatarFlag = row.str("client_flag_avatar"),
            isLocal = id != 0 && id == localClientId,
        )
    }

    fun toServerGroup(row: Map<String, String>): ServerGroup = ServerGroup(
        id = row.int("sgid"),
        name = row.str("name"),
        type = GroupType.fromId(row.int("type", default = 1)),
        iconId = row.long("iconid"),
        sortId = row.int("sortid"),
        memberAddPower = row.int("n_member_addp"),
        memberRemovePower = row.int("n_member_removep"),
        nameMode = row.int("namemode"),
        savedb = row.int("savedb", default = 1) == 1,
    )

    fun toChannelGroup(row: Map<String, String>): ChannelGroup = ChannelGroup(
        id = row.int("cgid"),
        name = row.str("name"),
        type = GroupType.fromId(row.int("type", default = 1)),
        iconId = row.long("iconid"),
        sortId = row.int("sortid"),
        savedb = row.int("savedb", default = 1) == 1,
    )

    fun toPermission(row: Map<String, String>): Permission = Permission(
        id = row.int("permid"),
        name = row.str("permname"),
        value = row.int("permvalue"),
        negated = row.flag("permnegated"),
        skipped = row.flag("permskip"),
    )

    /**
     * `serverinfo` and the `initserver` notification share field names, so both
     * can be funnelled through here.
     */
    fun toVirtualServer(row: Map<String, String>): VirtualServer = VirtualServer(
        id = row.int("virtualserver_id", default = 1),
        uniqueIdentifier = row.str("virtualserver_unique_identifier"),
        name = row.str("virtualserver_name"),
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

    /**
     * Events carry only the properties that changed, so [previous] supplies the
     * rest. Keys present in [row] always win.
     */
    fun mergeClient(previous: Client, row: Map<String, String>, localClientId: Int): Client {
        val merged = previous.toRow() + row.filterValues { it.isNotEmpty() }
        return toClient(merged, localClientId).copy(id = previous.id)
    }

    private fun Client.toRow(): Map<String, String> = mapOf(
        "clid" to id.toString(),
        "cid" to channelId.toString(),
        "client_database_id" to databaseId.toString(),
        "client_unique_identifier" to uniqueIdentifier,
        "client_nickname" to nickname,
        "client_description" to description,
        "client_type" to if (type == ClientType.QUERY) "1" else "0",
        "client_input_muted" to inputMuted.bit(),
        "client_output_muted" to outputMuted.bit(),
        "client_outputonly_muted" to outputOnlyMuted.bit(),
        "client_input_hardware" to inputHardware.bit(),
        "client_output_hardware" to outputHardware.bit(),
        "client_is_recording" to isRecording.bit(),
        "client_is_channel_commander" to isChannelCommander.bit(),
        "client_is_priority_speaker" to isPrioritySpeaker.bit(),
        "client_away" to isAway.bit(),
        "client_away_message" to awayMessage,
        "client_talk_power" to talkPower.toString(),
        "client_is_talker" to isTalker.bit(),
        "client_talk_request" to isRequestingTalkPower.bit(),
        "client_talk_request_msg" to talkRequestMessage,
        "client_servergroups" to serverGroups.joinToString(","),
        "client_channel_group_id" to channelGroupId.toString(),
        "client_platform" to platform,
        "client_version" to version,
        "client_country" to country,
        "client_idle_time" to idleTimeMs.toString(),
        "client_icon_id" to iconId.toString(),
        "client_flag_avatar" to avatarFlag,
    )

    private fun Boolean.bit(): String = if (this) "1" else "0"

    private fun Map<String, String>.str(key: String): String = this[key].orEmpty()

    private fun Map<String, String>.int(key: String, default: Int = 0): Int =
        this[key]?.trim()?.toIntOrNull() ?: default

    private fun Map<String, String>.long(key: String, default: Long = 0L): Long =
        this[key]?.trim()?.toLongOrNull() ?: default

    private fun Map<String, String>.flag(key: String, default: Boolean = false): Boolean =
        when (this[key]?.trim()) {
            null, "" -> default
            "0" -> false
            else -> true
        }

    private fun Map<String, String>.intList(key: String): List<Int> =
        this[key]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
}
