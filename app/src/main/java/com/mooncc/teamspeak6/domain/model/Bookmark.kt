package com.mooncc.teamspeak6.domain.model

/**
 * A saved server bookmark.
 *
 * Only what the native protocol actually needs: an address, a nickname, and the
 * optional passwords — the same fields the desktop client asks for.
 */
data class Bookmark(
    val id: Long = 0,
    val label: String,
    val host: String,
    val voicePort: Int = DEFAULT_VOICE_PORT,
    val nickname: String,
    val serverPassword: String = "",
    val defaultChannel: String = "",
    val defaultChannelPassword: String = "",
    val autoConnect: Boolean = false,
    val sortOrder: Int = 0,
    val lastConnectedMs: Long = 0,
) {
    val displaySubtitle: String get() = "$host:$voicePort"

    companion object {
        const val DEFAULT_VOICE_PORT = 9987
    }
}
