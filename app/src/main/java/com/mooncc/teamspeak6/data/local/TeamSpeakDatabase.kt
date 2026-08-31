package com.mooncc.teamspeak6.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookmarkEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TeamSpeakDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        const val NAME = "teamspeak.db"
    }
}
