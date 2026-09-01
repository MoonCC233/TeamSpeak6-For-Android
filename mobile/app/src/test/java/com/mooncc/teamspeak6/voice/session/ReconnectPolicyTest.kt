package com.mooncc.teamspeak6.voice.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `delay doubles from 2s and caps at 30s`() {
        assertEquals(2_000L, ReconnectPolicy.delayMsFor(1))
        assertEquals(4_000L, ReconnectPolicy.delayMsFor(2))
        assertEquals(8_000L, ReconnectPolicy.delayMsFor(3))
        assertEquals(16_000L, ReconnectPolicy.delayMsFor(4))
        assertEquals(30_000L, ReconnectPolicy.delayMsFor(5))
        assertEquals(30_000L, ReconnectPolicy.delayMsFor(8))
    }

    @Test
    fun `policy retries until the max attempts unless user initiated or kicked`() {
        assertTrue(ReconnectPolicy.shouldRetry(1, reasonId = 0, userInitiated = false))
        assertTrue(ReconnectPolicy.shouldRetry(4, reasonId = 0, userInitiated = false))
        assertTrue(ReconnectPolicy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS, reasonId = 0, userInitiated = false))
        assertFalse(ReconnectPolicy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS + 1, reasonId = 0, userInitiated = false))
        assertFalse(ReconnectPolicy.shouldRetry(2, reasonId = 5, userInitiated = false))
        assertFalse(ReconnectPolicy.shouldRetry(2, reasonId = 0, userInitiated = true))
    }
}
