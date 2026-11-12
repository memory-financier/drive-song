package com.drivesong.feature.trip.conversation

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.drivesong.core.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 대화 조각 녹음기 (설계 §7.5 1단계). 조각 길이는 [ConversationRecorder] 가 정한다. */
interface ChunkRecorder {
    /** [file] 에 녹음을 시작한다. 마이크를 쓸 수 없으면 예외. */
    suspend fun start(file: File)

    /** 녹음을 멈춘다. 쓸 수 있는 파일이 만들어졌으면 true. */
    fun stop(): Boolean
}

/**
 * MediaRecorder AAC 모노 16kHz 32kbps (.m4a, 5분 ≈ 1.2MB). 받아 적기·화자 식별(16kHz)에 충분하다.
 * [확인 필요] 설계 §13-3·§13-11: 차량 마이크를 주행 내내 쓸 수 있는지, Gleo·통화와 동시에 쓸 수 있는지.
 */
class MediaRecorderChunkRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChunkRecorder {
    private var recorder: MediaRecorder? = null

    override suspend fun start(file: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
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

/**
 * 데모 모드 (설계 §6.4, T19): 마이크 대신 앱에 넣은 데모 대화를 조각으로 쓴다. 에뮬레이터 마이크는 무음이라
 * 데모에서 가족 목소리 노래를 보여 주려면 필요하다. 가족이 직접 녹음한 대화(`assets/demo-family/`, git 밖
 * android/demo-private 에서 앱 모듈이 넣음)가 있으면 그것을, 없으면 공개 음성 대화(`assets/demo/`, 94초)를 쓴다.
 * 조각마다 같은 파일을 쓴다: 데모 주행은 노래 생성까지 약 35초라 조각이 하나뿐이다.
 */
class DemoChunkRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChunkRecorder {
    override suspend fun start(file: File) = withContext(Dispatchers.IO) {
        context.assets.open(asset(context)).use { input -> file.outputStream().use { input.copyTo(it) } }
        Unit
    }

    override fun stop() = true

    companion object {
        const val ASSET = "demo/conversation.m4a"
        const val FAMILY_ASSET = "demo-family/conversation.m4a"

        fun asset(context: Context): String =
            if (context.assets.list("demo-family")?.contains("conversation.m4a") == true) FAMILY_ASSET else ASSET
    }
}

/** 데모 모드면 [DemoChunkRecorder], 아니면 마이크. 조각을 시작할 때마다 정한다 (데모 모드 차량 데이터와 같은 방식). */
class ModeSwitchingChunkRecorder @Inject constructor(
    private val settings: SettingsRepository,
    private val mic: MediaRecorderChunkRecorder,
    private val demo: DemoChunkRecorder,
) : ChunkRecorder {
    private var active: ChunkRecorder? = null

    override suspend fun start(file: File) {
        val recorder = if (settings.demoMode.first()) demo else mic
        recorder.start(file)
        active = recorder
    }

    override fun stop(): Boolean {
        val recorder = active ?: return false
        active = null
        return recorder.stop()
    }
}
