package com.mooncc.teamspeak9.di

import android.content.Context
import androidx.room.Room
import com.mooncc.teamspeak9.data.local.BookmarkDao
import com.mooncc.teamspeak9.data.local.ChatMessageDao
import com.mooncc.teamspeak9.data.local.SettingsStore
import com.mooncc.teamspeak9.data.local.TeamSpeakDatabase
import com.mooncc.teamspeak9.data.repository.BookmarkRepositoryImpl
import com.mooncc.teamspeak9.data.repository.NativeTeamSpeakRepositoryImpl
import com.mooncc.teamspeak9.domain.repository.BookmarkRepository
import com.mooncc.teamspeak9.domain.repository.TeamSpeakRepository
import com.mooncc.teamspeak9.voice.identity.IdentityStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @SignalingHttpClient
    fun provideSignalingHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob()) + Dispatchers.IO

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TeamSpeakDatabase =
        Room.databaseBuilder(context, TeamSpeakDatabase::class.java, TeamSpeakDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBookmarkDao(database: TeamSpeakDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun provideChatMessageDao(database: TeamSpeakDatabase): ChatMessageDao =
        database.chatMessageDao()

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    fun provideIdentityStore(@ApplicationContext context: Context): IdentityStore =
        IdentityStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    @Binds
    @Singleton
    abstract fun bindTeamSpeakRepository(impl: NativeTeamSpeakRepositoryImpl): TeamSpeakRepository
}
