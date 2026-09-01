package com.mooncc.teamspeak6.domain.model

/**
 * A connected client (user) on a virtual server.
 */
data class Client(
    val id: Int,
    val channelId: Int,
    val databaseId: Int = 0,
    val uniqueIdentifier: String = "",
    val nickname: String,
    val description: String = "",
    val type: ClientType = ClientType.VOICE,
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
    val outputOnlyMuted: Boolean = false,
    val inputHardware: Boolean = true,
    val outputHardware: Boolean = true,
    val isTalking: Boolean = false,
    val isRecording: Boolean = false,
    val isChannelCommander: Boolean = false,
    val isPrioritySpeaker: Boolean = false,
    val isAway: Boolean = false,
    val awayMessage: String = "",
    val talkPower: Int = 0,
    val isTalker: Boolean = false,
    val isRequestingTalkPower: Boolean = false,
    val talkRequestMessage: String = "",
    val serverGroups: List<Int> = emptyList(),
    val channelGroupId: Int = 0,
    val platform: String = "",
    val version: String = "",
    val country: String = "",
    val idleTimeMs: Long = 0,
    val connectedTimeMs: Long = 0,
    val iconId: Long = 0,
    val avatarFlag: String = "",
    val isSharingScreen: Boolean = false,
    val isSharingVideo: Boolean = false,
    val isLocal: Boolean = false,
) {
    val isQuery: Boolean get() = type == ClientType.QUERY

    /** Microphone unavailable or explicitly disabled. */
    val isMicMuted: Boolean get() = inputMuted || !inputHardware

    /** Speakers unavailable or explicitly disabled. */
    val isSpeakerMuted: Boolean get() = outputMuted || !outputHardware

    val statusIcon: ClientStatus
        get() = when {
            isSpeakerMuted -> ClientStatus.SPEAKER_MUTED
            isMicMuted -> ClientStatus.MIC_MUTED
            isAway -> ClientStatus.AWAY
            isTalking -> ClientStatus.TALKING
            else -> ClientStatus.IDLE
        }
}

enum class ClientType {
    VOICE,
    QUERY,
    ;

    companion object {
        fun fromId(id: Int): ClientType = if (id == 1) QUERY else VOICE
    }
}

enum class ClientStatus {
    TALKING,
    IDLE,
    MIC_MUTED,
    SPEAKER_MUTED,
    AWAY,
}
