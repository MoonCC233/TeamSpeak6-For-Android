package com.mooncc.teamspeak6.domain.model

/**
 * Channel codec as reported by the server.
 */
enum class ChannelCodec(val id: Int, val label: String) {
    SPEEX_NARROWBAND(0, "Speex Narrowband"),
    SPEEX_WIDEBAND(1, "Speex Wideband"),
    SPEEX_ULTRA_WIDEBAND(2, "Speex Ultra-Wideband"),
    CELT_MONO(3, "CELT Mono"),
    OPUS_VOICE(4, "Opus Voice"),
    OPUS_MUSIC(5, "Opus Music"),
    UNKNOWN(-1, "Unknown"),
    ;

    companion object {
        fun fromId(id: Int): ChannelCodec = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * A channel on a virtual server. [subChannels] is populated when the flat list
 * returned by the server is assembled into a tree.
 */
data class Channel(
    val id: Int,
    val parentId: Int,
    val order: Int,
    val name: String,
    val topic: String = "",
    val description: String = "",
    val hasPassword: Boolean = false,
    val codec: ChannelCodec = ChannelCodec.OPUS_VOICE,
    val codecQuality: Int = 6,
    val maxClients: Int = -1,
    val maxFamilyClients: Int = -1,
    val isPermanent: Boolean = true,
    val isSemiPermanent: Boolean = false,
    val isDefault: Boolean = false,
    val isSpacer: Boolean = false,
    val spacerAlignment: SpacerAlignment = SpacerAlignment.NONE,
    val spacerLabel: String = "",
    val neededTalkPower: Int = 0,
    val iconId: Long = 0,
    val totalClients: Int = 0,
    val totalClientsFamily: Int = 0,
    val isSubscribed: Boolean = false,
    val depth: Int = 0,
    val subChannels: List<Channel> = emptyList(),
    val clients: List<Client> = emptyList(),
) {
    val isTemporary: Boolean get() = !isPermanent && !isSemiPermanent
    val isFull: Boolean get() = maxClients in 0..totalClients
    val displayName: String get() = if (isSpacer) spacerLabel else name
}

enum class SpacerAlignment {
    NONE,
    LEFT,
    CENTER,
    RIGHT,
    REPEAT,
}
