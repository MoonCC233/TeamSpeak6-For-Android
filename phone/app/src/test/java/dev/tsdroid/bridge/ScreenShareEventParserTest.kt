package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ScreenShareEventParser] 单元测试。
 *
 * 载荷样例与 Rust 侧 `ScreenShareEvent` / `ScreenShareSignal` 的 Serde 序列化格式一致
 * （外部标签枚举 + snake_case 字段；`Option::None` 序列化为 JSON `null`）。
 */
class ScreenShareEventParserTest {

    @Test
    fun `stream started parses all fields`() {
        val event = ScreenShareEventParser.parse(
            """{"StreamStarted":{"stream_id":"7","client_id":42,"name":"desk","audio":true}}""",
        )
        assertEquals(
            ScreenShareEvent.StreamStarted(streamId = "7", clientId = 42, name = "desk", audio = true),
            event,
        )
    }

    @Test
    fun `stream info parses`() {
        val event = ScreenShareEventParser.parse(
            """{"StreamInfo":{"stream_id":"9","client_id":3,"name":"","audio":false}}""",
        )
        assertEquals(
            ScreenShareEvent.StreamInfo(streamId = "9", clientId = 3, name = "", audio = false),
            event,
        )
    }

    @Test
    fun `stream stopped parses`() {
        assertEquals(
            ScreenShareEvent.StreamStopped("7"),
            ScreenShareEventParser.parse("""{"StreamStopped":{"stream_id":"7"}}"""),
        )
    }

    @Test
    fun `join request parses`() {
        assertEquals(
            ScreenShareEvent.JoinRequest(streamId = "7", viewerId = 42),
            ScreenShareEventParser.parse("""{"JoinRequest":{"stream_id":"7","viewer_id":42}}"""),
        )
    }

    @Test
    fun `respond join request keeps raw offer sdp`() {
        val event = ScreenShareEventParser.parse(
            """{"RespondJoinRequest":{"stream_id":"7","offer":"v=0\r\no=- 1 2 IN IP4 127.0.0.1"}}""",
        ) as ScreenShareEvent.RespondJoinRequest
        assertEquals("7", event.streamId)
        assertEquals("v=0\r\no=- 1 2 IN IP4 127.0.0.1", event.offer)
    }

    @Test
    fun `viewer left parses`() {
        assertEquals(
            ScreenShareEvent.ViewerLeft(streamId = "7", viewerId = 3),
            ScreenShareEventParser.parse("""{"ViewerLeft":{"stream_id":"7","viewer_id":3}}"""),
        )
    }

    @Test
    fun `signal with answer keeps json and parsed signal`() {
        val event = ScreenShareEventParser.parse(
            """{"Signal":{"stream_id":"7","peer_id":42,"json":"{\"cmd\":\"answer\"}",""" +
                """"signal":{"Answer":{"sdp":"v=0 answer"}}}}""",
        ) as ScreenShareEvent.Signal
        assertEquals("7", event.streamId)
        assertEquals(42, event.peerId)
        assertEquals("""{"cmd":"answer"}""", event.json)
        assertEquals(ScreenShareSignal.Answer("v=0 answer"), event.signal)
    }

    @Test
    fun `signal with ice candidate parses mid and m line`() {
        val event = ScreenShareEventParser.parse(
            """{"Signal":{"stream_id":"7","peer_id":42,"json":"{}",""" +
                """"signal":{"IceCandidate":{"candidate":"candidate:1 1 udp 1 127.0.0.1 1 typ host",""" +
                """"mid":"0","m_line":0}}}}""",
        ) as ScreenShareEvent.Signal
        assertEquals(
            ScreenShareSignal.IceCandidate(
                candidate = "candidate:1 1 udp 1 127.0.0.1 1 typ host",
                mid = "0",
                mLine = 0,
            ),
            event.signal,
        )
    }

    @Test
    fun `signal with null ice fields parses as nulls`() {
        val event = ScreenShareEventParser.parse(
            """{"Signal":{"stream_id":"7","peer_id":5,"json":"{}",""" +
                """"signal":{"IceCandidate":{"candidate":"c","mid":null,"m_line":null}}}}""",
        ) as ScreenShareEvent.Signal
        assertEquals(ScreenShareSignal.IceCandidate(candidate = "c", mid = null, mLine = null), event.signal)
    }

    @Test
    fun `signal with null signal field parses`() {
        val event = ScreenShareEventParser.parse(
            """{"Signal":{"stream_id":"7","peer_id":5,"json":"{\"cmd\":\"weird\"}","signal":null}}""",
        ) as ScreenShareEvent.Signal
        assertNull(event.signal)
    }

    @Test
    fun `signal with join response decision false parses`() {
        val event = ScreenShareEventParser.parse(
            """{"Signal":{"stream_id":"7","peer_id":5,"json":"{}",""" +
                """"signal":{"JoinResponse":{"decision":false,"offer":null}}}}""",
        ) as ScreenShareEvent.Signal
        assertEquals(ScreenShareSignal.JoinResponse(decision = false, offer = null), event.signal)
    }

    @Test
    fun `unknown event returns null`() {
        assertNull(ScreenShareEventParser.parse("""{"SomethingNew":{"x":1}}"""))
    }

    @Test
    fun `empty object returns null`() {
        assertNull(ScreenShareEventParser.parse("{}"))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(ScreenShareEventParser.parse("not json at all"))
    }

    @Test
    fun `unknown signal variant returns null signal with json intact`() {
        val event = ScreenShareEventParser.parse(
            """{"Signal":{"stream_id":"7","peer_id":5,"json":"{\"cmd\":\"future\"}",""" +
                """"signal":{"FutureThing":{"a":1}}}}""",
        ) as ScreenShareEvent.Signal
        assertNotNull(event)
        assertNull(event.signal)
        assertEquals("""{"cmd":"future"}""", event.json)
    }
}
