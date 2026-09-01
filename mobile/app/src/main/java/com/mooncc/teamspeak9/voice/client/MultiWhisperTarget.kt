package com.mooncc.teamspeak9.voice.client

import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import java.nio.ByteBuffer

/**
 * Whisper target list: `u8 channelCount, u8 clientCount, N×u64 channelId,
 * M×u16 clientId`.
 *
 * ts3j ships [PacketBody1VoiceWhisper.WhisperTargetMultiple] with the same wire
 * format, but its `getSize()` reports four bytes for the two count bytes it
 * writes. The socket allocates the send buffer from that size, so every whisper
 * packet would end up with two zero bytes appended after the Opus payload and
 * receivers would fail to decode the frame. Reimplementing the target keeps the
 * reported size and the written bytes in sync.
 */
internal class MultiWhisperTarget(
    private var channelIds: IntArray = IntArray(0),
    private var clientIds: IntArray = IntArray(0),
) : PacketBody1VoiceWhisper.WhisperTarget() {

    override fun write(buffer: ByteBuffer) {
        buffer.put((channelIds.size and 0xFF).toByte())
        buffer.put((clientIds.size and 0xFF).toByte())
        channelIds.forEach { buffer.putLong(it.toLong()) }
        clientIds.forEach { buffer.putShort((it and 0xFFFF).toShort()) }
    }

    override fun read(buffer: ByteBuffer) {
        val channelCount = buffer.get().toInt() and 0xFF
        val clientCount = buffer.get().toInt() and 0xFF
        channelIds = IntArray(channelCount) { buffer.long.toInt() }
        clientIds = IntArray(clientCount) { buffer.short.toInt() and 0xFFFF }
    }

    override fun getSize(): Int = COUNT_BYTES + channelIds.size * CHANNEL_ID_BYTES +
        clientIds.size * CLIENT_ID_BYTES

    private companion object {
        const val COUNT_BYTES = 2
        const val CHANNEL_ID_BYTES = 8
        const val CLIENT_ID_BYTES = 2
    }
}
