package com.mooncc.teamspeak6.data.remote

import com.mooncc.teamspeak6.domain.model.ChannelCodec
import com.mooncc.teamspeak6.domain.model.ClientType
import com.mooncc.teamspeak6.domain.model.SpacerAlignment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(raw: String): JsonObject = json.decodeFromString(JsonObject.serializer(), raw)

    @Test
    fun `maps channel fields and unlimited clients`() {
        val channel = QueryMappers.toChannel(
            row(
                """
                {
                  "cid": "7", "pid": "2", "channel_order": "5",
                  "channel_name": "Lobby", "channel_topic": "hi",
                  "channel_flag_password": "1", "channel_codec": "5",
                  "channel_codec_quality": "10",
                  "channel_flag_maxclients_unlimited": "1",
                  "channel_needed_talk_power": "25",
                  "total_clients": "3"
                }
                """,
            ),
        )

        assertEquals(7, channel.id)
        assertEquals(2, channel.parentId)
        assertEquals("Lobby", channel.name)
        assertEquals("hi", channel.topic)
        assertTrue(channel.hasPassword)
        assertEquals(ChannelCodec.OPUS_MUSIC, channel.codec)
        assertEquals(10, channel.codecQuality)
        assertEquals(-1, channel.maxClients)
        assertEquals(25, channel.neededTalkPower)
        assertEquals(3, channel.totalClients)
    }

    @Test
    fun `maps limited max clients`() {
        val channel = QueryMappers.toChannel(
            row(
                """
                {
                  "cid": "1", "channel_name": "Cap",
                  "channel_flag_maxclients_unlimited": "0",
                  "channel_maxclients": "12"
                }
                """,
            ),
        )

        assertEquals(12, channel.maxClients)
        assertTrue(channel.isFull.not())
    }

    @Test
    fun `detects centered spacer channels`() {
        val channel = QueryMappers.toChannel(
            row("""{"cid": "4", "channel_name": "[cspacer1]— Voice —"}"""),
        )

        assertTrue(channel.isSpacer)
        assertEquals(SpacerAlignment.CENTER, channel.spacerAlignment)
        assertEquals("— Voice —", channel.spacerLabel)
        assertEquals("— Voice —", channel.displayName)
    }

    @Test
    fun `detects repeat spacer channels`() {
        val channel = QueryMappers.toChannel(
            row("""{"cid": "4", "channel_name": "[*spacer]-"}"""),
        )

        assertTrue(channel.isSpacer)
        assertEquals(SpacerAlignment.REPEAT, channel.spacerAlignment)
    }

    @Test
    fun `regular channels are not spacers`() {
        val channel = QueryMappers.toChannel(
            row("""{"cid": "4", "channel_name": "Normal [not a spacer]"}"""),
        )

        assertFalse(channel.isSpacer)
        assertEquals("Normal [not a spacer]", channel.displayName)
    }

    @Test
    fun `maps client flags and groups`() {
        val client = QueryMappers.toClient(
            row(
                """
                {
                  "clid": "12", "cid": "3", "client_database_id": "44",
                  "client_nickname": "Moon", "client_type": "0",
                  "client_input_muted": "1", "client_output_muted": "0",
                  "client_input_hardware": "1", "client_output_hardware": "1",
                  "client_away": "1", "client_away_message": "brb",
                  "client_talk_power": "75",
                  "client_servergroups": "6,7,8",
                  "client_channel_group_id": "9",
                  "client_unique_identifier": "abc="
                }
                """,
            ),
            localClientId = 12,
        )

        assertEquals(12, client.id)
        assertEquals(3, client.channelId)
        assertEquals(44, client.databaseId)
        assertEquals("Moon", client.nickname)
        assertEquals(ClientType.VOICE, client.type)
        assertTrue(client.isMicMuted)
        assertFalse(client.isSpeakerMuted)
        assertTrue(client.isAway)
        assertEquals("brb", client.awayMessage)
        assertEquals(75, client.talkPower)
        assertEquals(listOf(6, 7, 8), client.serverGroups)
        assertEquals(9, client.channelGroupId)
        assertTrue(client.isLocal)
    }

    @Test
    fun `marks query clients`() {
        val client = QueryMappers.toClient(
            row("""{"clid": "1", "cid": "1", "client_nickname": "q", "client_type": "1"}"""),
        )

        assertTrue(client.isQuery)
    }

    @Test
    fun `maps virtual server info`() {
        val server = QueryMappers.toVirtualServer(
            row(
                """
                {
                  "virtualserver_name": "My Server",
                  "virtualserver_clientsonline": "10",
                  "virtualserver_queryclientsonline": "2",
                  "virtualserver_maxclients": "32",
                  "virtualserver_uptime": "8600"
                }
                """,
            ),
        )

        assertEquals("My Server", server.name)
        assertEquals(10, server.clientsOnline)
        assertEquals(8, server.voiceClientsOnline)
        assertEquals(32, server.maxClients)
        assertEquals(8600L, server.uptimeSeconds)
    }

    @Test
    fun `maps permissions from short and long field names`() {
        val long = QueryMappers.toPermission(
            row("""{"permid": "100", "permsid": "b_client_ban", "permvalue": "1", "permskip": "1"}"""),
        )
        val short = QueryMappers.toPermission(row("""{"p": "200", "v": "5", "n": "1"}"""))

        assertEquals(100, long.id)
        assertEquals("b_client_ban", long.name)
        assertTrue(long.skipped)
        assertEquals(200, short.id)
        assertEquals(5, short.value)
        assertTrue(short.negated)
    }

    @Test
    fun `maps offline messages converting seconds to millis`() {
        val message = QueryMappers.toOfflineMessage(
            row("""{"msgid": "3", "cluid": "uid=", "subject": "hi", "timestamp": "1700000000"}"""),
        )

        assertEquals(3, message.id)
        assertEquals("uid=", message.senderUniqueIdentifier)
        assertEquals(1_700_000_000_000L, message.timestampMs)
    }
}
