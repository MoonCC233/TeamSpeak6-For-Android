package com.mooncc.teamspeak6.data.repository

import com.mooncc.teamspeak6.data.local.BookmarkDao
import com.mooncc.teamspeak6.data.local.toDomain
import com.mooncc.teamspeak6.data.local.toEntity
import com.mooncc.teamspeak6.domain.model.Bookmark
import com.mooncc.teamspeak6.domain.repository.BookmarkRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
) : BookmarkRepository {

    override fun observeBookmarks(): Flow<List<Bookmark>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getBookmark(id: Long): Bookmark? = dao.findById(id)?.toDomain()

    override suspend fun getAutoConnectBookmark(): Bookmark? =
        dao.findAutoConnect()?.toDomain()

    override suspend fun saveBookmark(bookmark: Bookmark): Long {
        val entity = bookmark.toEntity()
        return if (bookmark.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            bookmark.id
        }
    }

    override suspend fun deleteBookmark(id: Long) = dao.deleteById(id)

    override suspend fun markConnected(id: Long) = dao.touch(id, System.currentTimeMillis())
}
