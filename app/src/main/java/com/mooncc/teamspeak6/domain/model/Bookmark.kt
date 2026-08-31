package com.mooncc.teamspeak6.domain.model

/**
 * A saved server bookmark.
 */
data class Bookmark(
    val id: Long = 0,
    val label: String,
    val host: String,
    val voicePort: Int = DEFAULT_VOICE_PORT,
    val queryPort: Int = DEFAULT_QUERY_PORT,
    val useTls: Boolean = false,
    val nickname: String,
    val serverPassword: String = "",
    val queryUsername: String = "",
    val queryPassword: String = "",
    val apiKey: String = "",
    val virtualServerId: Int = 1,
    val defaultChannel: String = "",
    val defaultChannelPassword: String = "",
    val bridgeUrl: String = "",
    val autoConnect: Boolean = false,
    val sortOrder: Int = 0,
    val lastConnectedMs: Long = 0,
) {
    val queryBaseUrl: String
        get() = buildString {
            append(if (useTls) "https://" else "http://")
            append(host)
            append(':')
            append(queryPort)
            append('/')
        }

    val displaySubtitle: String get() = "$host:$voicePort"

    companion object {
        const val DEFAULT_VOICE_PORT = 9987
        const val DEFAULT_QUERY_PORT = 10080
    }
}
