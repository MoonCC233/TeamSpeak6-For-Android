package com.mooncc.teamspeak9.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mooncc.teamspeak9.domain.model.ChatMessage
import com.mooncc.teamspeak9.domain.model.ChatTarget
import com.mooncc.teamspeak9.domain.model.DeliveryState
import kotlinx.coroutines.flow.Flow

/**
 * Persisted chat history so conversations survive reconnects.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "bookmark_id") val bookmarkId: Long,
    val target: String,
    @ColumnInfo(name = "conversation_id") val conversationId: Int,
    @ColumnInfo(name = "conversation_key") val conversationKey: String,
    @ColumnInfo(name = "sender_client_id") val senderClientId: Int,
    @ColumnInfo(name = "sender_nickname") val senderNickname: String,
    @ColumnInfo(name = "sender_uid") val senderUniqueIdentifier: String,
    val text: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
)

fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    target = runCatching { ChatTarget.valueOf(target) }.getOrDefault(ChatTarget.CHANNEL),
    conversationId = conversationId,
    senderClientId = senderClientId,
    senderNickname = senderNickname,
    senderUniqueIdentifier = senderUniqueIdentifier,
    text = text,
    timestampMs = timestampMs,
    isOutgoing = isOutgoing,
    isSystem = isSystem,
    deliveryState = DeliveryState.SENT,
)

fun ChatMessage.toEntity(bookmarkId: Long, conversationKey: String): ChatMessageEntity =
    ChatMessageEntity(
        id = id,
        bookmarkId = bookmarkId,
        target = target.name,
        conversationId = conversationId,
        conversationKey = conversationKey,
        senderClientId = senderClientId,
        senderNickname = senderNickname,
        senderUniqueIdentifier = senderUniqueIdentifier,
        text = text,
        timestampMs = timestampMs,
        isOutgoing = isOutgoing,
        isSystem = isSystem,
    )

@Dao
interface ChatMessageDao {

    @Query(
        "SELECT * FROM chat_messages WHERE bookmark_id = :bookmarkId " +
            "ORDER BY timestamp_ms ASC LIMIT :limit",
    )
    fun observeForBookmark(bookmarkId: Long, limit: Int = 2000): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE bookmark_id = :bookmarkId")
    suspend fun clearForBookmark(bookmarkId: Long)

    @Query("DELETE FROM chat_messages WHERE conversation_key = :conversationKey")
    suspend fun clearConversation(conversationKey: String)
}
