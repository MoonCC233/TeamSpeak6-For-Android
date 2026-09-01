package com.mooncc.teamspeak6.domain.model

/**
 * Something that happened on the server and should be surfaced to the user.
 */
sealed interface ServerEvent {
    val timestampMs: Long

    data class ClientJoined(
        val client: Client,
        val channelName: String,
        override val timestampMs: Long,
    ) : ServerEvent

    data class ClientLeft(
        val client: Client,
        override val timestampMs: Long,
    ) : ServerEvent

    data class ClientMoved(
        val client: Client,
        val fromChannelName: String,
        val toChannelName: String,
        val isLocalClient: Boolean,
        override val timestampMs: Long,
    ) : ServerEvent

    data class Poked(
        val fromNickname: String,
        val message: String,
        override val timestampMs: Long,
    ) : ServerEvent

    data class MessageReceived(
        val message: ChatMessage,
        override val timestampMs: Long,
    ) : ServerEvent

    data class ScreenShareStarted(
        val clientId: Int,
        val nickname: String,
        override val timestampMs: Long,
    ) : ServerEvent

    data class ScreenShareStopped(
        val clientId: Int,
        val nickname: String,
        override val timestampMs: Long,
    ) : ServerEvent

    data class Kicked(
        val reason: String,
        val fromServer: Boolean,
        override val timestampMs: Long,
    ) : ServerEvent

    data class Error(
        val message: String,
        override val timestampMs: Long,
    ) : ServerEvent
}
