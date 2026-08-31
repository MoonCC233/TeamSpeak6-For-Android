package com.mooncc.teamspeak6.di

import android.content.Context
import androidx.room.Room
import com.mooncc.teamspeak6.data.local.BookmarkDao
import com.mooncc.teamspeak6.data.local.ChatMessageDao
import com.mooncc.teamspeak6.data.local.SettingsStore
import com.mooncc.teamspeak6.data.local.TeamSpeakDatabase
import com.mooncc.teamspeak6.data.repository.BookmarkRepositoryImpl
import com.mooncc.teamspeak6.data.repository.TeamSpeakRepositoryImpl
import com.mooncc.teamspeak6.domain.repository.BookmarkRepository
import com.mooncc.teamspeak6.domain.repository.TeamSpeakRepository
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    @QueryHttpClient
    fun provideQueryHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    @Binds
    @Singleton
    abstract fun bindTeamSpeakRepository(impl: TeamSpeakRepositoryImpl): TeamSpeakRepository
}
