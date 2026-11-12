package com.drivesong.feature.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** 클립 녹음기 (설계 §8). 5초 자동 정지는 호출하는 쪽([MembersViewModel])이 맡는다. */
interface VoiceRecorder {
    /** [file] 에 녹음을 시작한다. 마이크를 쓸 수 없으면 예외. */
    fun start(file: File)

    /** 녹음을 멈춘다. 쓸 수 있는 파일이 만들어졌으면 true (너무 짧으면 false). */
    fun stop(): Boolean
}

/**
 * MediaRecorder AAC(.m4a) 녹음 (설계 §4.1).
 * [확인 필요] 설계 §13-3: Pleos 차량 마이크(AudioSource.MIC) 접근·음질. 안 되면 설계 §8 의
 * "폰 웹페이지(QR 접속) 녹음 업로드" 경로로 대체한다 (MVP 에서는 미구현).
 */
class MediaRecorderVoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(file: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop() // 녹음된 데이터가 없을 만큼 짧으면 RuntimeException
            true
        } catch (e: RuntimeException) {
            false
        } finally {
            r.release()
        }
    }
}
