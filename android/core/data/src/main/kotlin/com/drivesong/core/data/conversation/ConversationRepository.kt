package com.drivesong.core.data.conversation

import android.content.Context
import com.drivesong.core.network.DriveSongApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * '대화 담기' (설계 §8.3, §7.5 1단계)의 상태·파일·서버 호출. 녹음 자체는 feature:trip 의 ConversationRecorder 가 한다.
 *
 * - [armed]: 이번 주행 대화 담기 스위치. 저장하지 않는다 (앱을 다시 켜면 꺼져 있다). 노래 생성이 시작되면 녹음기가 끈다.
 * - [recording]: 지금 마이크로 녹음 중 (모든 화면 상단 배너).
 * - 조각 파일은 `files/conversation/<tripId>/<seq>.m4a`. 올리면 바로 지운다.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val api: DriveSongApi,
    @ApplicationContext context: Context,
) {
    private val dir = File(context.filesDir, "conversation")

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun setArmed(on: Boolean) {
        _armed.value = on
    }

    fun setRecording(on: Boolean) {
        _recording.value = on
    }

    fun chunkFile(tripId: String, seq: Int): File = File(File(dir, tripId).apply { mkdirs() }, "$seq.m4a")

    /** POST /v1/trips/{tripId}/conversation. 성공하면 파일을 지운다. 실패는 예외 (파일은 남는다). */
    suspend fun upload(tripId: String, seq: Int, passengers: List<String>, file: File) {
        api.uploadConversation(
            tripId = tripId,
            seq = seq.toString().toRequestBody(TEXT),
            passengers = passengers.map { MultipartBody.Part.createFormData("passengers", it) },
            audio = MultipartBody.Part.createFormData("audio", file.name, file.asRequestBody(AUDIO_M4A)),
        )
        file.delete()
    }

    /** 서버의 이 주행 조각을 지운다 (없어도 성공). */
    suspend fun deleteOnServer(tripId: String) = api.deleteConversation(tripId)

    // 아래 두 개는 녹음기(백그라운드 디스패처)가 부른다. 조각 몇 개라 바로 끝난다.
    fun deleteLocal(tripId: String) {
        File(dir, tripId).deleteRecursively()
    }

    /** [keep] 밖의 주행 폴더를 지운다 (앞 프로세스가 남긴 조각). */
    fun deleteLocalExcept(keep: Set<String>) {
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    /** 설정 > 모든 데이터 삭제 (서버 조각은 DELETE /v1/songs 가 지운다). */
    suspend fun deleteAllLocal() {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    companion object {
        private val TEXT = "text/plain".toMediaType()
        private val AUDIO_M4A = "audio/mp4".toMediaType()
    }
}
