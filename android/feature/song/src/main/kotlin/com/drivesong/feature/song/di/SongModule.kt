package com.drivesong.feature.song.di

import com.drivesong.feature.song.NotificationSongNotifier
import com.drivesong.feature.song.SongNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SongModule {
    @Binds
    abstract fun songNotifier(impl: NotificationSongNotifier): SongNotifier
}
