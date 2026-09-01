package com.mooncc.teamspeak9.voice.client

import com.github.manevolent.ts3j.enums.GroupWhisperTarget
import com.github.manevolent.ts3j.enums.GroupWhisperType
import com.github.manevolent.ts3j.protocol.packet.PacketBody1VoiceWhisper
import com.mooncc.teamspeak9.domain.model.WhisperGroupKind
import com.mooncc.teamspeak9.domain.model.WhisperGroupScope
import com.mooncc.teamspeak9.domain.model.WhisperGroupTarget
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

/**
 * Group whisper target: `u8 type, u8 scope, u64 targetId`.
 *
 * Unlike the explicit list this addresses clients by group membership, so the
 * sender does not need to know who is where — the server resolves the group and
 * the channel scope on its side.
 *
 * This extends ts3j's [PacketBody1VoiceWhisper.WhisperTargetGroup] rather than
 * reimplementing it: `PacketBody1VoiceWhisper.setHeaderValues` decides whether
 * to raise the `NEW_PROTOCOL` header flag with an `instanceof` check against
 * that exact type, and the server only parses the body as a group target when
 * the flag is set. A sibling implementation would serialise identically but
 * lose the flag, and the payload would then be misread as an id list.
 *
 * The inherited `getSize()` (1 + 1 + 8) already agrees with what it writes, so
 * only the field wiring is added here.
 */
internal class GroupWhisperTargetBody(
    target: WhisperGroupTarget,
) : PacketBody1VoiceWhisper.WhisperTargetGroup() {

    init {
        whisperType = target.kind.toTs3j()
        whisperTarget = target.scope.toTs3j()
        targetId = target.groupId
    }
}

internal fun WhisperGroupKind.toTs3j(): GroupWhisperType = when (this) {
    WhisperGroupKind.SERVER_GROUP -> GroupWhisperType.SERVER_GROUP
    WhisperGroupKind.CHANNEL_GROUP -> GroupWhisperType.CHANNEL_GROUP
    WhisperGroupKind.CHANNEL_COMMANDER -> GroupWhisperType.CHANNEL_COMMANDER
    WhisperGroupKind.ALL_CLIENTS -> GroupWhisperType.ALL_CLIENTS
}

internal fun WhisperGroupScope.toTs3j(): GroupWhisperTarget = when (this) {
    WhisperGroupScope.ALL_CHANNELS -> GroupWhisperTarget.ALL_CHANNELS
    WhisperGroupScope.CURRENT_CHANNEL -> GroupWhisperTarget.CURRENT_CHANNEL
    WhisperGroupScope.PARENT_CHANNEL -> GroupWhisperTarget.PARENT_CHANNEL
    WhisperGroupScope.ALL_PARENT_CHANNEL -> GroupWhisperTarget.ALL_PARENT_CHANNEL
    WhisperGroupScope.CHANNEL_FAMILY -> GroupWhisperTarget.CHANNEL_FAMILY
    WhisperGroupScope.COMPLETE_CHANNEL_FAMILY -> GroupWhisperTarget.COMPLETE_CHANNEL_FAMILY
    WhisperGroupScope.SUBCHANNELS -> GroupWhisperTarget.SUBCHANNELS
}
