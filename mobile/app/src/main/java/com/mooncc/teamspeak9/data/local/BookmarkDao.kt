package com.mooncc.teamspeak9.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY sort_order ASC, label ASC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun findById(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE auto_connect = 1 ORDER BY sort_order ASC LIMIT 1")
    suspend fun findAutoConnect(): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE bookmarks SET last_connected_ms = :timestampMs WHERE id = :id")
    suspend fun touch(id: Long, timestampMs: Long)
}
