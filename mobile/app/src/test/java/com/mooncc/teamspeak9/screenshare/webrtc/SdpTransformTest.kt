package com.mooncc.teamspeak9.screenshare.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTransformTest {

    private val videoSdp = listOf(
        "v=0",
        "o=- 1 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 98 100",
        "c=IN IP4 0.0.0.0",
        "a=rtpmap:96 VP8/90000",
        "a=rtpmap:98 VP9/90000",
        "a=rtpmap:100 H264/90000",
    ).joinToString("\r\n")

    @Test
    fun `applyVideoBitrate inserts bandwidth lines after the connection line`() {
        val result = SdpTransform.applyVideoBitrate(videoSdp, 2500).lines()
        val cIndex = result.indexOfFirst { it.startsWith("c=") }

        assertEquals("b=AS:2500", result[cIndex + 1])
        assertEquals("b=TIAS:2500000", result[cIndex + 2])
    }

    @Test
    fun `applyVideoBitrate replaces existing bandwidth lines instead of duplicating`() {
        val once = SdpTransform.applyVideoBitrate(videoSdp, 1000)
        val twice = SdpTransform.applyVideoBitrate(once, 4000)

        assertEquals(1, twice.lines().count { it.startsWith("b=AS:") })
        assertEquals(1, twice.lines().count { it.startsWith("b=TIAS:") })
        assertTrue(twice.lines().contains("b=AS:4000"))
    }

    @Test
    fun `applyVideoBitrate is a no-op for non-positive bitrates`() {
        assertEquals(videoSdp, SdpTransform.applyVideoBitrate(videoSdp, 0))
        assertEquals(videoSdp, SdpTransform.applyVideoBitrate(videoSdp, -1))
    }

    @Test
    fun `applyVideoBitrate leaves audio-only sdp untouched`() {
        val audioOnly = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0"
        assertEquals(audioOnly, SdpTransform.applyVideoBitrate(audioOnly, 2000))
    }

    @Test
    fun `applyVideoBitrate only touches the video section`() {
        val multi = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:111 opus/48000/2",
            "m=video 9 UDP/TLS/RTP/SAVPF 100",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:100 H264/90000",
        ).joinToString("\r\n")

        val result = SdpTransform.applyVideoBitrate(multi, 1500).lines()
        val videoIndex = result.indexOfFirst { it.startsWith("m=video") }
        val bitrateIndex = result.indexOfFirst { it.startsWith("b=AS:") }

        assertTrue(bitrateIndex > videoIndex)
    }

    @Test
    fun `preferVideoCodec moves the codec payloads to the front`() {
        val result = SdpTransform.preferVideoCodec(videoSdp, "H264")
        val mLine = result.lines().first { it.startsWith("m=video") }

        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 100 96 98", mLine)
    }

    @Test
    fun `preferVideoCodec is case insensitive`() {
        val result = SdpTransform.preferVideoCodec(videoSdp, "h264")
        assertTrue(result.lines().first { it.startsWith("m=video") }.endsWith("100 96 98"))
    }

    @Test
    fun `preferVideoCodec returns the sdp unchanged for an absent codec`() {
        assertEquals(videoSdp, SdpTransform.preferVideoCodec(videoSdp, "AV1"))
    }

    @Test
    fun `preferVideoCodec keeps every payload type`() {
        val original = videoSdp.lines().first { it.startsWith("m=video") }
            .split(' ').drop(3).toSet()
        val reordered = SdpTransform.preferVideoCodec(videoSdp, "H264")
            .lines().first { it.startsWith("m=video") }
            .split(' ').drop(3).toSet()

        assertEquals(original, reordered)
    }

    @Test
    fun `hasAudio detects the audio section`() {
        assertFalse(SdpTransform.hasAudio(videoSdp))
        assertTrue(SdpTransform.hasAudio("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"))
    }

    @Test
    fun `transforms accept LF-only line breaks`() {
        val lfSdp = videoSdp.replace("\r\n", "\n")
        val result = SdpTransform.applyVideoBitrate(lfSdp, 800)

        assertTrue(result.contains("b=AS:800"))
        assertTrue(result.contains("\r\n"))
    }
}
