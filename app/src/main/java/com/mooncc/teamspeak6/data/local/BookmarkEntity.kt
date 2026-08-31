package com.mooncc.teamspeak6.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mooncc.teamspeak6.domain.model.Bookmark

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    @ColumnInfo(name = "voice_port") val voicePort: Int,
    @ColumnInfo(name = "query_port") val queryPort: Int,
    @ColumnInfo(name = "use_tls") val useTls: Boolean,
    val nickname: String,
    @ColumnInfo(name = "server_password") val serverPassword: String,
    @ColumnInfo(name = "query_username") val queryUsername: String,
    @ColumnInfo(name = "query_password") val queryPassword: String,
    @ColumnInfo(name = "api_key") val apiKey: String,
    @ColumnInfo(name = "virtual_server_id") val virtualServerId: Int,
    @ColumnInfo(name = "default_channel") val defaultChannel: String,
    @ColumnInfo(name = "default_channel_password") val defaultChannelPassword: String,
    @ColumnInfo(name = "bridge_url") val bridgeUrl: String,
    @ColumnInfo(name = "auto_connect") val autoConnect: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "last_connected_ms") val lastConnectedMs: Long,
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    label = label,
    host = host,
    voicePort = voicePort,
    queryPort = queryPort,
    useTls = useTls,
    nickname = nickname,
    serverPassword = serverPassword,
    queryUsername = queryUsername,
    queryPassword = queryPassword,
    apiKey = apiKey,
    virtualServerId = virtualServerId,
    defaultChannel = defaultChannel,
    defaultChannelPassword = defaultChannelPassword,
    bridgeUrl = bridgeUrl,
    autoConnect = autoConnect,
    sortOrder = sortOrder,
    lastConnectedMs = lastConnectedMs,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    label = label,
    host = host,
    voicePort = voicePort,
    queryPort = queryPort,
    useTls = useTls,
    nickname = nickname,
    serverPassword = serverPassword,
    queryUsername = queryUsername,
    queryPassword = queryPassword,
    apiKey = apiKey,
    virtualServerId = virtualServerId,
    defaultChannel = defaultChannel,
    defaultChannelPassword = defaultChannelPassword,
    bridgeUrl = bridgeUrl,
    autoConnect = autoConnect,
    sortOrder = sortOrder,
    lastConnectedMs = lastConnectedMs,
)
