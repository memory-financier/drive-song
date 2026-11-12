package com.drivesong.feature.voice.di

import com.drivesong.feature.voice.ClipPlayer
import com.drivesong.feature.voice.MediaPlayerClipPlayer
import com.drivesong.feature.voice.MediaRecorderVoiceRecorder
import com.drivesong.feature.voice.VoiceRecorder
import com.drivesong.feature.voice.command.PleosSpeechInput
import com.drivesong.feature.voice.command.RecordingCue
import com.drivesong.feature.voice.command.SpeechInput
import com.drivesong.feature.voice.command.ToneRecordingCue
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 녹음기·플레이어는 스코프 없이 주입할 때마다 새로 만든다 (화면 ViewModel 마다, 음성 명령 핸들러에 하나).
 * 음성 명령 핸들러(@Singleton)도 녹음기를 써서 SingletonComponent 에 둔다 (설계 §8.2).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    abstract fun voiceRecorder(impl: MediaRecorderVoiceRecorder): VoiceRecorder

    @Binds
    abstract fun clipPlayer(impl: MediaPlayerClipPlayer): ClipPlayer

    @Binds
    abstract fun speechInput(impl: PleosSpeechInput): SpeechInput

    @Binds
    abstract fun recordingCue(impl: ToneRecordingCue): RecordingCue
}
