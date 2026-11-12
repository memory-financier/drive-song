package com.drivesong.core.data.di

import android.content.Context
import androidx.room.Room
import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.db.MIGRATION_5_6
import com.drivesong.core.data.db.SongDao
import com.drivesong.core.data.db.TripDao
import com.drivesong.core.data.db.VoiceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DriveSongDatabase =
        Room.databaseBuilder(context, DriveSongDatabase::class.java, "drive-song.db")
            .addMigrations(MIGRATION_5_6)
            .fallbackToDestructiveMigration() // MVP(출시 전): 그 밖의 스키마 변경은 재생성
            .build()

    @Provides
    fun tripDao(db: DriveSongDatabase): TripDao = db.tripDao()

    @Provides
    fun voiceDao(db: DriveSongDatabase): VoiceDao = db.voiceDao()

    @Provides
    fun songDao(db: DriveSongDatabase): SongDao = db.songDao()
}
