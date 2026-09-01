package com.mooncc.teamspeak9.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private fun payload(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `withholds packets until the target depth is reached`() {
        val buffer = JitterBuffer(targetDepth = 3)
        buffer.offer(10, payload(1))
        assertNull(buffer.poll())
        buffer.offer(11, payload(2))
        assertNull(buffer.poll())
        buffer.offer(12, payload(3))

        assertEquals(JitterBuffer.Slot.Packet(10, payload(1)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(11, payload(2)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(12, payload(3)), buffer.poll())
    }

    @Test
    fun `reorders packets that arrive out of order`() {
        val buffer = JitterBuffer(targetDepth = 3)
        buffer.offer(5, payload(1))
        buffer.offer(7, payload(3))
        buffer.offer(6, payload(2))

        assertEquals(JitterBuffer.Slot.Packet(5, payload(1)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(6, payload(2)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(7, payload(3)), buffer.poll())
    }

    @Test
    fun `reports a missing packet as lost`() {
        val buffer = JitterBuffer(targetDepth = 2)
        buffer.offer(1, payload(1))
        buffer.offer(3, payload(3))

        assertEquals(JitterBuffer.Slot.Packet(1, payload(1)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Lost(2), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(3, payload(3)), buffer.poll())
    }

    @Test
    fun `drops packets that arrive after their slot played out`() {
        val buffer = JitterBuffer(targetDepth = 1)
        buffer.offer(100, payload(1))
        assertEquals(JitterBuffer.Slot.Packet(100, payload(1)), buffer.poll())

        buffer.offer(99, payload(9))
        assertEquals(0, buffer.size)
    }

    @Test
    fun `sequence numbers wrap at 16 bits`() {
        val buffer = JitterBuffer(targetDepth = 3)
        buffer.offer(0xFFFE, payload(1))
        buffer.offer(0x0000, payload(3))
        buffer.offer(0xFFFF, payload(2))

        assertEquals(JitterBuffer.Slot.Packet(0xFFFE, payload(1)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(0xFFFF, payload(2)), buffer.poll())
        assertEquals(JitterBuffer.Slot.Packet(0x0000, payload(3)), buffer.poll())
    }

    @Test
    fun `an old packet across the wrap point is dropped`() {
        val buffer = JitterBuffer(targetDepth = 1)
        buffer.offer(0x0002, payload(1))
        assertEquals(JitterBuffer.Slot.Packet(0x0002, payload(1)), buffer.poll())

        buffer.offer(0xFFF0, payload(9))
        assertEquals(0, buffer.size)
    }

    @Test
    fun `an empty payload ends the stream once the queue drains`() {
        val buffer = JitterBuffer(targetDepth = 1)
        buffer.offer(1, payload(1))
        buffer.offer(2, ByteArray(0))

        assertEquals(JitterBuffer.Slot.Packet(1, payload(1)), buffer.poll())
        assertEquals(JitterBuffer.Slot.EndOfStream, buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun `never grows past the maximum depth`() {
        val buffer = JitterBuffer(targetDepth = 2, maxDepth = 4)
        repeat(20) { index -> buffer.offer(index, payload(index)) }
        assertTrue(buffer.size <= 4)
    }

    @Test
    fun `reset clears all state`() {
        val buffer = JitterBuffer(targetDepth = 1)
        buffer.offer(1, payload(1))
        buffer.reset()

        assertEquals(0, buffer.size)
        assertNull(buffer.poll())
    }
}
