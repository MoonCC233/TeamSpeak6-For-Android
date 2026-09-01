package com.mooncc.teamspeak9.voice.client

import com.mooncc.teamspeak9.domain.model.ChannelCodec
import com.mooncc.teamspeak9.domain.model.ClientType
import com.mooncc.teamspeak9.domain.model.GroupType
import com.mooncc.teamspeak9.domain.model.SpacerAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ts3MappersTest {

    @Test
    fun `maps channel fields`() {
        val channel = Ts3Mappers.toChannel(
            mapOf(
                "cid" to "7",
                "pid" to "2",
                "channel_order" to "5",
                "channel_name" to "Lobby",
                "channel_topic" to "welcome",
                "channel_flag_password" to "1",
                "channel_codec" to "5",
                "channel_codec_quality" to "10",
                "channel_flag_maxclients_unlimited" to "0",
                "channel_maxclients" to "16",
                "channel_needed_talk_power" to "25",
                "total_clients" to "3",
            ),
        )

        assertEquals(7, channel.id)
        assertEquals(2, channel.parentId)
        assertEquals(5, channel.order)
        assertEquals("Lobby", channel.name)
        assertEquals("welcome", channel.topic)
        assertTrue(channel.hasPassword)
        assertEquals(ChannelCodec.OPUS_MUSIC, channel.codec)
        assertEquals(10, channel.codecQuality)
        assertEquals(16, channel.maxClients)
        assertEquals(25, channel.neededTalkPower)
        assertEquals(3, channel.totalClients)
        assertFalse(channel.isSpacer)
    }

    @Test
    fun `an unlimited channel reports no client cap`() {
        val channel = Ts3Mappers.toChannel(
            mapOf(
                "cid" to "1",
                "channel_flag_maxclients_unlimited" to "1",
                "channel_maxclients" to "16",
            ),
        )
        assertEquals(-1, channel.maxClients)
        assertFalse(channel.isFull)
    }

    @Test
    fun `a channel without flags defaults to permanent and unlimited`() {
        val channel = Ts3Mappers.toChannel(mapOf("cid" to "1", "channel_name" to "Default"))
        assertTrue(channel.isPermanent)
        assertEquals(-1, channel.maxClients)
        assertEquals(-1, channel.maxFamilyClients)
        assertEquals(ChannelCodec.OPUS_VOICE, channel.codec)
    }

    @Test
    fun `recognises a centered spacer`() {
        val channel = Ts3Mappers.toChannel(
            mapOf("cid" to "9", "channel_name" to "[cspacer3]--- Staff ---"),
        )
        assertTrue(channel.isSpacer)
        assertEquals(SpacerAlignment.CENTER, channel.spacerAlignment)
        assertEquals("--- Staff ---", channel.spacerLabel)
        assertEquals("--- Staff ---", channel.displayName)
    }

    @Test
    fun `recognises a repeating spacer`() {
        val channel = Ts3Mappers.toChannel(
            mapOf("cid" to "9", "channel_name" to "[*spacer]-"),
        )
        assertEquals(SpacerAlignment.REPEAT, channel.spacerAlignment)
        assertEquals("-", channel.spacerLabel)
    }

    @Test
    fun `maps client fields and flags the local client`() {
        val client = Ts3Mappers.toClient(
            mapOf(
                "clid" to "12",
                "cid" to "4",
                "client_database_id" to "88",
                "client_unique_identifier" to "abc=",
                "client_nickname" to "Moon",
                "client_type" to "0",
                "client_input_muted" to "1",
                "client_output_muted" to "0",
                "client_away" to "1",
                "client_away_message" to "brb",
                "client_talk_power" to "75",
                "client_servergroups" to "6,7,8",
                "client_channel_group_id" to "9",
                "client_idle_time" to "4200",
            ),
            localClientId = 12,
        )

        assertEquals(12, client.id)
        assertEquals(4, client.channelId)
        assertEquals(88, client.databaseId)
        assertEquals("Moon", client.nickname)
        assertEquals(ClientType.VOICE, client.type)
        assertTrue(client.inputMuted)
        assertTrue(client.isAway)
        assertEquals("brb", client.awayMessage)
        assertEquals(75, client.talkPower)
        assertEquals(listOf(6, 7, 8), client.serverGroups)
        assertEquals(9, client.channelGroupId)
        assertEquals(4200L, client.idleTimeMs)
        assertTrue(client.isLocal)
    }

    @Test
    fun `a client without hardware flags is assumed to have hardware`() {
        val client = Ts3Mappers.toClient(mapOf("clid" to "3"), localClientId = 1)
        assertTrue(client.inputHardware)
        assertTrue(client.outputHardware)
        assertFalse(client.isMicMuted)
        assertFalse(client.isLocal)
    }

    @Test
    fun `a client with no nickname gets a placeholder`() {
        val client = Ts3Mappers.toClient(mapOf("clid" to "42"), localClientId = 0)
        assertEquals("client 42", client.nickname)
    }

    @Test
    fun `a query client is detected`() {
        val client = Ts3Mappers.toClient(
            mapOf("clid" to "5", "client_type" to "1"),
            localClientId = 0,
        )
        assertEquals(ClientType.QUERY, client.type)
        assertTrue(client.isQuery)
    }

    @Test
    fun `client id zero is never considered local`() {
        val client = Ts3Mappers.toClient(mapOf("clid" to "0"), localClientId = 0)
        assertFalse(client.isLocal)
    }

    @Test
    fun `merging keeps unchanged properties from the previous snapshot`() {
        val previous = Ts3Mappers.toClient(
            mapOf(
                "clid" to "12",
                "cid" to "4",
                "client_nickname" to "Moon",
                "client_talk_power" to "75",
                "client_servergroups" to "6,7",
            ),
            localClientId = 12,
        )

        val updated = Ts3Mappers.mergeClient(
            previous = previous,
            row = mapOf("client_away" to "1", "client_away_message" to "brb"),
            localClientId = 12,
        )

        assertEquals("Moon", updated.nickname)
        assertEquals(4, updated.channelId)
        assertEquals(75, updated.talkPower)
        assertEquals(listOf(6, 7), updated.serverGroups)
        assertTrue(updated.isAway)
        assertEquals("brb", updated.awayMessage)
        assertEquals(12, updated.id)
    }

    @Test
    fun `merging lets the event win over the previous snapshot`() {
        val previous = Ts3Mappers.toClient(
            mapOf("clid" to "12", "client_nickname" to "Moon", "client_talk_power" to "10"),
            localClientId = 0,
        )
        val updated = Ts3Mappers.mergeClient(
            previous = previous,
            row = mapOf("client_nickname" to "Sun", "client_talk_power" to "90"),
            localClientId = 0,
        )
        assertEquals("Sun", updated.nickname)
        assertEquals(90, updated.talkPower)
    }

    @Test
    fun `maps a server group`() {
        val group = Ts3Mappers.toServerGroup(
            mapOf(
                "sgid" to "6",
                "name" to "Admin",
                "type" to "1",
                "iconid" to "300",
                "sortid" to "10",
                "n_member_addp" to "75",
                "n_member_removep" to "75",
                "savedb" to "1",
            ),
        )
        assertEquals(6, group.id)
        assertEquals("Admin", group.name)
        assertEquals(GroupType.REGULAR, group.type)
        assertEquals(300L, group.iconId)
        assertEquals(75, group.memberAddPower)
        assertTrue(group.savedb)
    }

    @Test
    fun `maps a channel group`() {
        val group = Ts3Mappers.toChannelGroup(
            mapOf("cgid" to "9", "name" to "Operator", "type" to "0", "savedb" to "0"),
        )
        assertEquals(9, group.id)
        assertEquals("Operator", group.name)
        assertEquals(GroupType.TEMPLATE, group.type)
        assertFalse(group.savedb)
    }

    @Test
    fun `maps a permission`() {
        val permission = Ts3Mappers.toPermission(
            mapOf(
                "permid" to "100",
                "permname" to "b_channel_join_permanent",
                "permvalue" to "1",
                "permnegated" to "0",
                "permskip" to "1",
            ),
        )
        assertEquals(100, permission.id)
        assertEquals("b_channel_join_permanent", permission.name)
        assertEquals(1, permission.value)
        assertFalse(permission.negated)
        assertTrue(permission.skipped)
    }

    @Test
    fun `maps virtual server info`() {
        val server = Ts3Mappers.toVirtualServer(
            mapOf(
                "virtualserver_id" to "1",
                "virtualserver_name" to "Moon Server",
                "virtualserver_clientsonline" to "12",
                "virtualserver_queryclientsonline" to "2",
                "virtualserver_maxclients" to "32",
                "virtualserver_channelsonline" to "8",
                "virtualserver_uptime" to "86400",
                "virtualserver_welcomemessage" to "hi",
            ),
        )
        assertEquals(1, server.id)
        assertEquals("Moon Server", server.name)
        assertEquals(12, server.clientsOnline)
        assertEquals(10, server.voiceClientsOnline)
        assertEquals(32, server.maxClients)
        assertEquals(8, server.channelsOnline)
        assertEquals(86_400L, server.uptimeSeconds)
        assertEquals("hi", server.welcomeMessage)
    }

    @Test
    fun `absent virtual server id falls back to one`() {
        val server = Ts3Mappers.toVirtualServer(mapOf("virtualserver_name" to "x"))
        assertEquals(1, server.id)
    }
}
