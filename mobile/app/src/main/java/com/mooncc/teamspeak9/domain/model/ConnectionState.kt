package com.mooncc.teamspeak9.domain.model

/**
 * Snapshot of the current connection to a virtual server.
 */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val bookmark: Bookmark? = null,
    val server: VirtualServer? = null,
    val localClientId: Int = 0,
    val currentChannelId: Int = 0,
    val errorMessage: String? = null,
    val pingMs: Long = 0,
    val packetLossPercent: Float = 0f,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.CONNECTED
    val isBusy: Boolean
        get() = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

/**
 * Local audio / media toggles that apply to the current session.
 */
data class LocalMediaState(
    val micMuted: Boolean = false,
    val speakerMuted: Boolean = false,
    val pushToTalkEnabled: Boolean = false,
    val pushToTalkActive: Boolean = false,
    val voiceActivationThresholdDb: Int = -40,
    val isTalking: Boolean = false,
    val isSharingScreen: Boolean = false,
    val isChannelCommander: Boolean = false,
    val isPrioritySpeaker: Boolean = false,
    val isRequestingTalkPower: Boolean = false,
    val talkRequestMessage: String = "",
    val whisperChannelIds: List<Int> = emptyList(),
    val whisperClientIds: List<Int> = emptyList(),
    val whisperActive: Boolean = false,
    val isAway: Boolean = false,
    val awayMessage: String = "",
    val outputVolumePercent: Int = 100,
    val inputGainPercent: Int = 100,
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true,
) {
    /** Whether any whisper target is configured. */
    val hasWhisperTargets: Boolean
        get() = whisperChannelIds.isNotEmpty() || whisperClientIds.isNotEmpty()

    /** Whether the microphone should currently transmit. */
    val shouldTransmit: Boolean
        get() = !micMuted && (whisperActive || !pushToTalkEnabled || pushToTalkActive)
}
