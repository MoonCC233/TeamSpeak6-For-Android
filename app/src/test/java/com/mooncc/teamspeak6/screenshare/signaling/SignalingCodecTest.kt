package com.mooncc.teamspeak6.screenshare.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingCodecTest {

    @Test
    fun `hello round trips through encode and the wire discriminator`() {
        val hello = ClientMessage.Hello(
            roomId = "room-1",
            clientUid = "uid",
            tsClientId = 7,
            nickname = "MoonCC",
        )
        val json = SignalingCodec.encode(hello)

        assertTrue(json.contains("\"type\":\"hello\""))
        assertTrue(json.contains("\"v\":1"))
        assertEquals(
            hello,
            SignalingCodec.json.decodeFromString(ClientMessage.serializer(), json),
        )
    }

    @Test
    fun `announce serialises enums by their wire names`() {
        val json = SignalingCodec.encode(
            ClientMessage.Announce(
                mode = ShareMode.SFU,
                privacy = SharePrivacy.PRIVATE,
                video = VideoParams(1280, 720, 30, 2500),
            ),
        )

        assertTrue(json.contains("\"mode\":\"sfu\""))
        assertTrue(json.contains("\"privacy\":\"private\""))
    }

    @Test
    fun `welcome decodes with its nested collections`() {
        val message = SignalingCodec.decode(
            """
            {
              "type": "welcome",
              "peerId": "p1",
              "roomId": "r1",
              "sfuAvailable": true,
              "iceServers": [{"urls": ["stun:example.com:3478"]}],
              "shares": [
                {"publisherId": "p2", "nickname": "PC", "mode": "p2p",
                 "hasAudio": true, "video": {"width": 1920, "height": 1080,
                 "fps": 30, "bitrateKbps": 4000}}
              ]
            }
            """.trimIndent(),
        )

        val welcome = message as ServerMessage.Welcome
        assertEquals("p1", welcome.peerId)
        assertTrue(welcome.sfuAvailable)
        assertEquals(1, welcome.iceServers.size)
        assertEquals(1920, welcome.shares.single().video?.width)
    }

    @Test
    fun `unknown types decode to Unknown instead of throwing`() {
        val message = SignalingCodec.decode("""{"type":"future-thing","x":1}""")

        val unknown = message as ServerMessage.Unknown
        assertEquals("future-thing", unknown.type)
    }

    @Test
    fun `known type with a malformed body degrades to Unknown`() {
        val message = SignalingCodec.decode("""{"type":"peer-left"}""")

        assertTrue(message is ServerMessage.Unknown)
    }

    @Test
    fun `unknown fields on a known type are ignored`() {
        val message = SignalingCodec.decode(
            """{"type":"share-stopped","publisherId":"p2","futureField":42}""",
        )

        assertEquals("p2", (message as ServerMessage.ShareStopped).publisherId)
    }

    @Test
    fun `non-json and type-less frames decode to null`() {
        assertNull(SignalingCodec.decode("not json"))
        assertNull(SignalingCodec.decode("""{"no":"type"}"""))
        assertNull(SignalingCodec.decode("[]"))
    }

    @Test
    fun `candidate frames survive a round trip`() {
        val json = SignalingCodec.encode(
            ClientMessage.Candidate(
                to = "peer-2",
                candidate = "candidate:1 1 udp 2130706431 10.0.0.1 54321 typ host",
                sdpMid = "0",
                sdpMLineIndex = 0,
            ),
        )
        val decoded = SignalingCodec.decode(json.replace("\"to\"", "\"from\""))

        assertEquals("peer-2", (decoded as ServerMessage.Candidate).from)
    }
}

class RoomIdTest {

    @Test
    fun `the same location always yields the same id`() {
        assertEquals(RoomId.forChannel("server-uid", 42), RoomId.forChannel("server-uid", 42))
    }

    @Test
    fun `different channels yield different ids`() {
        assertNotEquals(RoomId.forChannel("server-uid", 1), RoomId.forChannel("server-uid", 2))
    }

    @Test
    fun `different servers yield different ids`() {
        assertNotEquals(RoomId.forChannel("server-a", 1), RoomId.forChannel("server-b", 1))
    }

    @Test
    fun `ids are 32 lowercase hex characters`() {
        val id = RoomId.forChannel("server-uid", 42)

        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `an empty server uid still produces a valid id`() {
        assertEquals(32, RoomId.forChannel("", 0).length)
    }
}
