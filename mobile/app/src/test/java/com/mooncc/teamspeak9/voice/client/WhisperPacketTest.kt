package com.mooncc.teamspeak9.voice.client

import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.enums.GroupWhisperTarget
import com.github.manevolent.ts3j.enums.GroupWhisperType
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.header.ClientPacketHeader
import com.github.manevolent.ts3j.protocol.header.HeaderFlag
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.mooncc.teamspeak9.domain.model.WhisperGroupKind
import com.mooncc.teamspeak9.domain.model.WhisperGroupScope
import com.mooncc.teamspeak9.domain.model.WhisperGroupTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `group target serialises kind then scope then id`() {
        val target = GroupWhisperTargetBody(
            WhisperGroupTarget(
                kind = WhisperGroupKind.CHANNEL_GROUP,
                scope = WhisperGroupScope.SUBCHANNELS,
                groupId = 77,
            ),
        )

        val buffer = ByteBuffer.allocate(target.size).order(ByteOrder.BIG_ENDIAN)
        target.write(buffer)

        assertEquals(1 + 1 + 8, target.size)
        assertEquals(0, buffer.remaining())

        buffer.flip()
        assertEquals(GroupWhisperType.CHANNEL_GROUP.index, buffer.get().toInt() and 0xFF)
        assertEquals(GroupWhisperTarget.SUBCHANNELS.index, buffer.get().toInt() and 0xFF)
        assertEquals(77L, buffer.long)
    }

    @Test
    fun `group target uses the enum indices from the domain model`() {
        WhisperGroupKind.entries.forEach { kind ->
            assertEquals(kind.id, kind.toTs3j().index)
        }
        WhisperGroupScope.entries.forEach { scope ->
            assertEquals(scope.id, scope.toTs3j().index)
        }
    }

    /**
     * ts3j decides whether to raise NEW_PROTOCOL by testing the target against
     * [PacketBody1VoiceWhisper.WhisperTargetGroup]. Without the flag the server
     * parses a group target as an explicit id list, so the inheritance is load
     * bearing rather than incidental.
     */
    @Test
    fun `group whisper raises the new protocol header flag`() {
        val body = PacketBody1VoiceWhisper(ProtocolRole.CLIENT).apply {
            codecType = CodecType.OPUS_VOICE
            codecData = ByteArray(0)
            setTarget(
                GroupWhisperTargetBody(
                    WhisperGroupTarget(kind = WhisperGroupKind.CHANNEL_COMMANDER),
                ),
            )
        }

        val header = ClientPacketHeader()
        body.setHeaderValues(header)

        assertTrue(header.getPacketFlag(HeaderFlag.NEW_PROTOCOL))
    }

    @Test
    fun `list whisper clears the new protocol header flag`() {
        val header = ClientPacketHeader()
        header.setPacketFlag(HeaderFlag.NEW_PROTOCOL, true)

        PacketBody1VoiceWhisper(ProtocolRole.CLIENT).apply {
            codecType = CodecType.OPUS_VOICE
            codecData = ByteArray(0)
            setTarget(MultiWhisperTarget(channelIds = intArrayOf(1)))
        }.setHeaderValues(header)

        assertFalse(header.getPacketFlag(HeaderFlag.NEW_PROTOCOL))
    }

    @Test
    fun `group whisper body accounts for the target in its size`() {
        val payload = byteArrayOf(9, 9, 9)
        val target = GroupWhisperTargetBody(
            WhisperGroupTarget(kind = WhisperGroupKind.ALL_CLIENTS),
        )
        val body = PacketBody1VoiceWhisper(ProtocolRole.CLIENT).apply {
            codecType = CodecType.OPUS_VOICE
            codecData = payload
            packetId = 1
            setTarget(target)
        }

        val buffer = ByteBuffer.allocate(body.size).order(ByteOrder.BIG_ENDIAN)
        body.write(buffer)

        assertEquals(3 + 10 + payload.size, body.size)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun `group targets needing an id are rejected until one is set`() {
        assertFalse(WhisperGroupTarget(kind = WhisperGroupKind.SERVER_GROUP).isValid)
        assertFalse(WhisperGroupTarget(kind = WhisperGroupKind.CHANNEL_GROUP).isValid)
        assertTrue(
            WhisperGroupTarget(kind = WhisperGroupKind.SERVER_GROUP, groupId = 6).isValid,
        )
        // Commander and all-clients address no group, so no id is needed.
        assertTrue(WhisperGroupTarget(kind = WhisperGroupKind.CHANNEL_COMMANDER).isValid)
        assertTrue(WhisperGroupTarget(kind = WhisperGroupKind.ALL_CLIENTS).isValid)
    }
}
