package com.mooncc.teamspeak9.domain.model

/**
 * Who a group whisper addresses.
 *
 * The ids are the protocol indices, matching the convention used by
 * [GroupType]: the wire value lives on the enum so the voice layer can map
 * straight through without a translation table.
 */
enum class WhisperGroupKind(val id: Int) {
    /** Everyone holding a given server group. */
    SERVER_GROUP(0),

    /** Everyone holding a given channel group. */
    CHANNEL_GROUP(1),

    /** Everyone flagged as channel commander. */
    CHANNEL_COMMANDER(2),

    /** Everyone in scope, regardless of group membership. */
    ALL_CLIENTS(3),
    ;

    /** Whether [WhisperGroupTarget.groupId] has to be filled in. */
    val needsGroupId: Boolean
        get() = this == SERVER_GROUP || this == CHANNEL_GROUP

    companion object {
        fun fromId(id: Int): WhisperGroupKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which channels a group whisper reaches, relative to the sender's own channel.
 */
enum class WhisperGroupScope(val id: Int) {
    ALL_CHANNELS(0),
    CURRENT_CHANNEL(1),
    PARENT_CHANNEL(2),
    ALL_PARENT_CHANNEL(3),
    CHANNEL_FAMILY(4),
    COMPLETE_CHANNEL_FAMILY(5),
    SUBCHANNELS(6),
    ;

    companion object {
        fun fromId(id: Int): WhisperGroupScope? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A group whisper target: address a set of clients by group membership and
 * channel scope instead of listing channel and client ids individually.
 *
 * This is the addressing mode the desktop client uses for "whisper to all
 * channel commanders" and similar shortcuts. It travels in a different wire
 * format than the explicit list, so a whisper is either a group whisper or a
 * list whisper — never both.
 */
data class WhisperGroupTarget(
    val kind: WhisperGroupKind,
    val scope: WhisperGroupScope = WhisperGroupScope.ALL_CHANNELS,
    /** Server- or channel-group id; unused for commander / all-clients. */
    val groupId: Long = 0,
) {
    /** Whether this target is complete enough to send. */
    val isValid: Boolean
        get() = !kind.needsGroupId || groupId > 0
}
