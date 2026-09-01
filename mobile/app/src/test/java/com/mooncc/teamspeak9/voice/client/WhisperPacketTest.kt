package com.mooncc.teamspeak9.voice.client

import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whisper wire format is not covered by ts3j's own tests and a mismatch
 * between the reported size and the written bytes silently corrupts the audio
 * payload, so both are pinned here.
 */
class WhisperPacketTest {

    @Test
    fun `target size matches written bytes`() {
        val target = MultiWhisperTarget(
            channelIds = intArrayOf(7, 9),
            clientIds = intArrayOf(11, 12, 13),
        )

        val buffer = ByteBuffer.allocate(target.size).order(ByteOrder.BIG_ENDIAN)
        target.write(buffer)

        assertEquals(2 + 2 * 8 + 3 * 2, target.size)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun `target serialises counts then ids`() {
        val target = MultiWhisperTarget(channelIds = intArrayOf(5), clientIds = intArrayOf(258))
        val buffer = ByteBuffer.allocate(target.size).order(ByteOrder.BIG_ENDIAN)

        target.write(buffer)
        buffer.flip()

        assertEquals(1, buffer.get().toInt())
        assertEquals(1, buffer.get().toInt())
        assertEquals(5L, buffer.long)
        assertEquals(258, buffer.short.toInt() and 0xFFFF)
    }

    @Test
    fun `target round trips`() {
        val original = MultiWhisperTarget(
            channelIds = intArrayOf(1, 2, 3),
            clientIds = intArrayOf(40, 50),
        )
        val buffer = ByteBuffer.allocate(original.size).order(ByteOrder.BIG_ENDIAN)
        original.write(buffer)
        buffer.flip()

        val restored = MultiWhisperTarget()
        restored.read(buffer)

        val roundTripped = ByteBuffer.allocate(restored.size).order(ByteOrder.BIG_ENDIAN)
        restored.write(roundTripped)

        assertEquals(original.size, restored.size)
        assertEquals(buffer.rewind(), roundTripped.rewind())
    }

    @Test
    fun `client whisper body carries target and payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val target = MultiWhisperTarget(channelIds = intArrayOf(42), clientIds = intArrayOf(7))
        val body = PacketBody1VoiceWhisper(ProtocolRole.CLIENT).apply {
            codecType = CodecType.OPUS_VOICE
            codecData = payload
            packetId = 0x1234
            setTarget(target)
        }

        val buffer = ByteBuffer.allocate(body.size).order(ByteOrder.BIG_ENDIAN)
        body.write(buffer)

        // u16 packetId + u8 codec + target + payload, with nothing left over.
        assertEquals(3 + target.size + payload.size, body.size)
        assertEquals(0, buffer.remaining())

        buffer.flip()
        assertEquals(0x1234, buffer.short.toInt() and 0xFFFF)
        assertEquals(CodecType.OPUS_VOICE.index, buffer.get().toInt() and 0xFF)
    }
}
