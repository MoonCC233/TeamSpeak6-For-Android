package com.mooncc.teamspeak9.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mooncc.teamspeak9.domain.model.Bookmark

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    @ColumnInfo(name = "voice_port") val voicePort: Int,
    val nickname: String,
    @ColumnInfo(name = "server_password") val serverPassword: String,
    @ColumnInfo(name = "default_channel") val defaultChannel: String,
    @ColumnInfo(name = "default_channel_password") val defaultChannelPassword: String,
    @ColumnInfo(name = "auto_connect") val autoConnect: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "last_connected_ms") val lastConnectedMs: Long,
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    label = label,
    host = host,
    voicePort = voicePort,
    nickname = nickname,
    serverPassword = serverPassword,
    defaultChannel = defaultChannel,
    defaultChannelPassword = defaultChannelPassword,
    autoConnect = autoConnect,
    sortOrder = sortOrder,
    lastConnectedMs = lastConnectedMs,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    label = label,
    host = host,
    voicePort = voicePort,
    nickname = nickname,
    serverPassword = serverPassword,
    defaultChannel = defaultChannel,
    defaultChannelPassword = defaultChannelPassword,
    autoConnect = autoConnect,
    sortOrder = sortOrder,
    lastConnectedMs = lastConnectedMs,
)
