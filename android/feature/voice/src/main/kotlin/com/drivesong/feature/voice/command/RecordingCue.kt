package com.drivesong.feature.voice.command

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.delay
import javax.inject.Inject

/** 음성 녹음 시작 직전 신호 (설계 §8.2: 화면을 보지 않아도 언제 말할지 알 수 있게). */
interface RecordingCue {
    suspend fun beforeRecording()
}

/** 짧은 삐 소리. 소리를 낼 수 없으면 그냥 넘어간다. */
class ToneRecordingCue @Inject constructor() : RecordingCue {
    override suspend fun beforeRecording() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (e: RuntimeException) {
            Log.w("RecordingCue", "no tone", e)
            return
        }
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            delay(BEEP_MS + 100L) // 신호음이 녹음에 들어가지 않게 끝난 뒤 시작
        } finally {
            tone.release()
        }
    }

    private companion object {
        const val VOLUME = 80
        const val BEEP_MS = 200
    }
}
