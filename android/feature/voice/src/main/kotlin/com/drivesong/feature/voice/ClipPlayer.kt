package com.drivesong.feature.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import javax.inject.Inject

/** 클립 미리듣기 (설계 §8). 최대 5초 클립이라 오디오 포커스는 따로 요청하지 않는다. */
interface ClipPlayer {
    fun play(file: File)
    fun stop()
}

class MediaPlayerClipPlayer @Inject constructor() : ClipPlayer {
    private var player: MediaPlayer? = null

    override fun play(file: File) {
        stop()
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        p.setOnCompletionListener { if (player === it) stop() }
        p.setDataSource(file.absolutePath)
        p.prepare()
        p.start()
    }

    override fun stop() {
        player?.release()
        player = null
    }
}
