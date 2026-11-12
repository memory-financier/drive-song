package com.drivesong.core.data.voice

import android.content.Context
import com.drivesong.core.data.db.ClipEntity
import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.db.UploadState
import com.drivesong.core.data.db.VoiceDao
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.ClipCandidate
import com.drivesong.core.model.ClipPlanner
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.MemberConsentUpdate
import com.drivesong.core.network.dto.MemberDto
import com.drivesong.core.network.dto.MemberRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 가족 구성원·목소리 클립 (설계 §8, §8.1, §9). 서버 호출 실패는 예외로 던진다.
 * 녹음 파일은 앱 내부 저장소 `files/voice_clips/` 에 둔다.
 */
@Singleton
class VoiceRepository @Inject constructor(
    private val dao: VoiceDao,
    private val api: DriveSongApi,
    private val settings: SettingsRepository,
    @ApplicationContext context: Context,
    /** 녹음 시각 기록용. 앱에서는 주행 기록과 같은 Clock (데모 중에는 시나리오 시각, 설계 §8.2). */
    private val clock: Clock = Clock.systemUTC(),
) {
    private val clipDir = File(context.filesDir, "voice_clips")

    val members: Flow<List<MemberEntity>> = dao.members()

    /** 모든 클립, 녹음 순서대로. */
    val clips: Flow<List<ClipEntity>> = dao.clips()

    /**
     * 서버에 등록(동의 기록)한 뒤 로컬에 저장한다. 동의가 없으면 서버에 보내지 않는다 (§8.1).
     * 대화 녹음·목소리 변환은 선택 동의 (§8.3). 아이는 보호자 동의 아래에서만 받는다.
     */
    suspend fun addMember(
        nickname: String,
        isChild: Boolean,
        voiceConsent: Boolean,
        guardianConsent: Boolean,
        conversationConsent: Boolean = false,
        voiceConversionConsent: Boolean = false,
    ): MemberEntity {
        require(voiceConsent) { "voice consent is required" }
        require(!isChild || guardianConsent) { "guardian consent is required for a child" }
        val dto = api.createMember(
            MemberRequest(nickname, isChild, voiceConsent, guardianConsent, conversationConsent, voiceConversionConsent),
        )
        val member = dto.toEntity()
        dao.insertMember(member)
        return member
    }

    /** 선택 동의 변경 (§8.3, PATCH). 서버가 먼저 바뀐 뒤 로컬을 바꾼다. 변환 동의를 끄면 서버가 참고 목소리를 지운다. */
    suspend fun updateConsents(memberId: String, conversationConsent: Boolean, voiceConversionConsent: Boolean): MemberEntity {
        val member = api.updateMember(memberId, MemberConsentUpdate(conversationConsent, voiceConversionConsent)).toEntity()
        dao.updateMember(member)
        return member
    }

    /** 서버 구성원·클립 삭제 → 로컬 파일·행 삭제 → 탑승자 선택에서 제외 (§8.1). */
    suspend fun deleteMember(memberId: String) {
        ignoreNotFound { api.deleteMember(memberId) }
        dao.clipsOf(memberId).forEach { File(it.localPath).delete() }
        dao.deleteMember(memberId)
        val selected = settings.selectedPassengerIds.first()
        if (memberId in selected) settings.setSelectedPassengerIds(selected - memberId)
    }

    /** 새 녹음을 쓸 파일 (아직 클립으로 저장되지 않은 상태). */
    fun newRecordingFile(): File {
        clipDir.mkdirs()
        return File(clipDir, "${UUID.randomUUID()}.m4a")
    }

    /**
     * 녹음 파일을 클립으로 저장한다(PENDING, 업로드 전). 슬롯마다 최대 [MAX_CLIPS_PER_SLOT]개 (§8).
     * [recordedAtMs] 0 = 녹음 시각 모름: 이번 주행 녹음으로 치지 않는다 (데모 가족 클립, T19).
     */
    suspend fun addClip(memberId: String, slot: ClipSlot, file: File, recordedAtMs: Long = clock.millis()): ClipEntity {
        check(dao.countClips(memberId, slot) < MAX_CLIPS_PER_SLOT) { "max $MAX_CLIPS_PER_SLOT clips per slot" }
        val pending = ClipEntity(memberId = memberId, slot = slot, localPath = file.absolutePath, recordedAtMs = recordedAtMs)
        return pending.copy(localId = dao.insertClip(pending))
    }

    /**
     * 음성 녹음 저장 (설계 §8.2): 슬롯이 가득 차 있으면 가장 먼저 녹음한 클립을 지우고(서버 포함) 저장한다.
     * 오래된 클립의 서버 삭제가 실패하면 예외 (새 녹음은 저장하지 않음).
     */
    suspend fun addClipReplacingOldest(memberId: String, slot: ClipSlot, file: File): ClipEntity {
        if (dao.countClips(memberId, slot) >= MAX_CLIPS_PER_SLOT) {
            dao.oldestClip(memberId, slot)?.let { deleteClip(it) }
        }
        return addClip(memberId, slot, file)
    }

    /** POST /v1/clips (multipart: memberId, slot, audio). 실패하면 FAILED 로 바꾸고 예외를 다시 던진다. */
    suspend fun upload(clip: ClipEntity): ClipEntity {
        val file = File(clip.localPath)
        val audio = MultipartBody.Part.createFormData("audio", file.name, file.asRequestBody(AUDIO_M4A))
        val dto = try {
            api.uploadClip(clip.memberId.toRequestBody(TEXT), clip.slot.name.toRequestBody(TEXT), audio)
        } catch (e: Exception) {
            dao.updateClip(clip.copy(uploadState = UploadState.FAILED))
            throw e
        }
        return clip.copy(serverId = dto.clipId, uploadState = UploadState.UPLOADED).also { dao.updateClip(it) }
    }

    /** 서버 클립 삭제(업로드된 경우) → 로컬 파일·행 삭제. */
    suspend fun deleteClip(clip: ClipEntity) {
        clip.serverId?.let { ignoreNotFound { api.deleteClip(it) } }
        File(clip.localPath).delete()
        dao.deleteClip(clip.localId)
    }

    /**
     * 이번 주행의 clipPlan (§9): 탑승자의 업로드된 클립 중 슬롯마다 하나 (설정: 랜덤/순서대로).
     * 고른 클립은 사용 순번을 기록해, 순서대로 모드에서 다음 주행에 다른 클립이 나오게 한다.
     * @param recordedSinceMs 이번 주행 시작 시각: 그 뒤에 녹음한 클립이 있는 슬롯은 그 클립을 쓴다 (설계 §8.2).
     * @param onlyRecent true 면 그 뒤에 녹음한 클립만 쓴다. 대화 노래는 빈 슬롯을 대화 속 한마디로 채운다 (설계 §7.5).
     * @return 슬롯 → 서버 clipId (INTRO, CALL, OUTRO 순서, 클립이 없는 슬롯은 빠짐)
     */
    suspend fun planClips(
        passengerIds: Set<String>,
        recordedSinceMs: Long? = null,
        random: Random = Random.Default,
        onlyRecent: Boolean = false,
    ): Map<ClipSlot, String> {
        if (passengerIds.isEmpty()) return emptyMap()
        val candidates = dao.uploadedClipsOf(passengerIds).map {
            ClipCandidate(checkNotNull(it.serverId), it.memberId, it.slot, it.localId, it.lastUsedSeq, it.recordedAtMs)
        }
        val plan = ClipPlanner.plan(candidates, passengerIds, settings.clipPlanMode.first(), random, recordedSinceMs, onlyRecent)
        if (plan.isNotEmpty()) dao.markUsed(plan.values, dao.maxUsedSeq() + 1)
        return plan
    }

    /** 클립 행이 가리키지 않는 녹음 파일을 지운다 (저장하지 않은 초안, 녹음 중 프로세스 종료로 남은 파일). */
    suspend fun deleteOrphanFiles() {
        val kept = dao.clips().first().map { File(it.localPath).absolutePath }.toSet()
        clipDir.listFiles()?.filter { it.absolutePath !in kept }?.forEach { it.delete() }
    }

    private fun MemberDto.toEntity() =
        MemberEntity(memberId, nickname, isChild, voiceConsent, guardianConsent, conversationConsent, voiceConversionConsent)

    /** 이미 서버에서 지워진 경우(404)는 성공으로 본다. */
    private suspend fun ignoreNotFound(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
        }
    }

    companion object {
        const val MAX_CLIPS_PER_SLOT = 3
        private val TEXT = "text/plain".toMediaType()
        private val AUDIO_M4A = "audio/mp4".toMediaType()
    }
}
