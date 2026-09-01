package com.mooncc.teamspeak9.screenshare

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenShareManagerUrlNormalizationTest {

    @Test
    fun `http and https urls are normalized to websocket endpoints`() {
        assertEquals(
            "ws://127.0.0.1:8765",
            ScreenShareManager.normalizeSignalUrlStatic("http://127.0.0.1:8765"),
        )
        assertEquals(
            "wss://signal.example.com",
            ScreenShareManager.normalizeSignalUrlStatic("https://signal.example.com"),
        )
        assertEquals(
            "ws://already-websocket.example.com",
            ScreenShareManager.normalizeSignalUrlStatic("ws://already-websocket.example.com"),
        )
        assertEquals("", ScreenShareManager.normalizeSignalUrlStatic("   "))
    }
}
