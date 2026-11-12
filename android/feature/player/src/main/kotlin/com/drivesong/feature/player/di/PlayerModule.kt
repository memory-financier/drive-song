package com.drivesong.feature.player.di

import com.drivesong.feature.player.MediaControllerSongPlayer
import com.drivesong.feature.player.SongPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {
    @Binds
    abstract fun songPlayer(impl: MediaControllerSongPlayer): SongPlayer
}
