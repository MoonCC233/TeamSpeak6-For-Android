package com.mooncc.teamspeak9.domain.model

/**
 * Where a chat message was sent.
 */
enum class ChatTarget(val targetMode: Int) {
    CLIENT(1),
    CHANNEL(2),
    SERVER(3),
    ;

    companion object {
        fun fromTargetMode(mode: Int): ChatTarget =
            entries.firstOrNull { it.targetMode == mode } ?: CHANNEL
    }
}

/**
 * A chat message shown in a conversation tab.
 */
data class ChatMessage(
    val id: String,
    val target: ChatTarget,
    /** Channel id for [ChatTarget.CHANNEL], client id for [ChatTarget.CLIENT], 0 for server. */
    val conversationId: Int,
    val senderClientId: Int,
    val senderNickname: String,
    val senderUniqueIdentifier: String = "",
    val text: String,
    val timestampMs: Long,
    val isOutgoing: Boolean = false,
    val isSystem: Boolean = false,
    val deliveryState: DeliveryState = DeliveryState.SENT,
)

enum class DeliveryState {
    SENDING,
    SENT,
    FAILED,
}

/**
 * A conversation tab: server chat, a channel chat, or a private chat.
 */
data class Conversation(
    val target: ChatTarget,
    val conversationId: Int,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val unreadCount: Int = 0,
) {
    val key: String get() = "${target.name}:$conversationId"
}

/**
 * An offline message stored on the server.
 */
data class OfflineMessage(
    val id: Int,
    val senderUniqueIdentifier: String,
    val subject: String,
    val message: String = "",
    val timestampMs: Long,
    val isRead: Boolean = false,
)
