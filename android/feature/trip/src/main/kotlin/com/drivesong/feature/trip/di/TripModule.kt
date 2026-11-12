package com.drivesong.feature.trip.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripRecorder
import com.drivesong.feature.trip.conversation.ChunkRecorder
import com.drivesong.feature.trip.conversation.ConversationCapture
import com.drivesong.feature.trip.conversation.ConversationRecorder
import com.drivesong.feature.trip.conversation.ModeSwitchingChunkRecorder
import com.drivesong.feature.trip.conversation.MicPermission
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TripModule {
    @Binds
    abstract fun tripEvents(impl: TripRecorder): TripEvents

    @Binds
    abstract fun conversationCapture(impl: ConversationRecorder): ConversationCapture

    @Binds
    abstract fun chunkRecorder(impl: ModeSwitchingChunkRecorder): ChunkRecorder

    companion object {
        @Provides
        fun micPermission(@ApplicationContext context: Context) = MicPermission {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }
}
