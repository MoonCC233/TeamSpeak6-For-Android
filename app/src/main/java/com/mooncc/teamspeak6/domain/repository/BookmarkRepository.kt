package com.mooncc.teamspeak6.domain.repository

import com.mooncc.teamspeak6.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeBookmarks(): Flow<List<Bookmark>>
    suspend fun getBookmark(id: Long): Bookmark?
    suspend fun getAutoConnectBookmark(): Bookmark?
    suspend fun saveBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(id: Long)
    suspend fun markConnected(id: Long)
}
