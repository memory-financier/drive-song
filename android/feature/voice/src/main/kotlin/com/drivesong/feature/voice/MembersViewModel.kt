package com.drivesong.feature.voice

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.db.ClipEntity
import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.vehicle.restriction.UxRestrictionsMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 녹음 진행 상태. 녹음 직후에는 [Draft] 로 미리듣기 → 저장/버리기 (설계 §8). */
sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val memberId: String, val slot: ClipSlot, val file: File) : RecordingState
    data class Draft(val memberId: String, val slot: ClipSlot, val file: File) : RecordingState
}

data class MemberClips(val member: MemberEntity, val clips: List<ClipEntity>) {
    fun clips(slot: ClipSlot) = clips.filter { it.slot == slot }
}

data class MembersUiState(
    val members: List<MemberClips> = emptyList(),
    val restricted: Boolean = true,
    val recording: RecordingState = RecordingState.Idle,
    val uploading: Set<Long> = emptySet(), // 업로드 중인 클립 localId
    val busy: Boolean = false, // 구성원 등록/삭제 요청 중
    val clipPlanMode: ClipPlanMode = ClipPlanMode.RANDOM,
    @StringRes val message: Int? = null,
) {
    /** 녹음 버튼: 정차 중, 다른 녹음이 없고, 슬롯이 다 차지 않았을 때만 (설계 §8). */
    fun canRecord(memberId: String, slot: ClipSlot): Boolean =
        !restricted &&
            recording == RecordingState.Idle &&
            members.any { it.member.id == memberId && it.clips(slot).size < VoiceRepository.MAX_CLIPS_PER_SLOT }
}

/** 가족 구성원·녹음 화면 (설계 §8, §8.1, §10). 정차 전용: 주행 제한이 시작되면 진행 중인 녹음을 멈춘다. */
@HiltViewModel
class MembersViewModel @Inject constructor(
    private val repo: VoiceRepository,
    private val settings: SettingsRepository,
    restrictions: UxRestrictionsMonitor,
    private val recorder: VoiceRecorder,
    private val player: ClipPlayer,
) : ViewModel() {

    /** 첫 값이 오기 전에는 안전하게 제한 상태로 본다. */
    private val restricted = restrictions.isRestricted
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    private val local = MutableStateFlow(MembersUiState())
    private var autoStop: Job? = null

    val uiState: StateFlow<MembersUiState> =
        combine(repo.members, repo.clips, restricted, settings.clipPlanMode, local) { members, clips, restricted, mode, local ->
            local.copy(
                members = members.map { m -> MemberClips(m, clips.filter { it.memberId == m.id }) },
                restricted = restricted,
                clipPlanMode = mode,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MembersUiState())

    init {
        viewModelScope.launch {
            restricted.collect { if (it) stopRecording() }
        }
        // 구성원이 지워지면 (이 화면·데이터 삭제) 그 구성원의 녹음·초안은 화면에서 닿을 수 없으므로 버린다.
        // 남겨 두면 녹음 상태가 Idle 로 돌아오지 않아 모든 녹음 버튼이 막힌다.
        viewModelScope.launch {
            repo.members.collect { members ->
                val owner = when (val r = local.value.recording) {
                    is RecordingState.Recording -> r.memberId
                    is RecordingState.Draft -> r.memberId
                    RecordingState.Idle -> null
                }
                if (owner != null && members.none { it.id == owner }) discardRecording()
            }
        }
    }

    fun addMember(
        nickname: String,
        isChild: Boolean,
        voiceConsent: Boolean,
        guardianConsent: Boolean,
        conversationConsent: Boolean = false,
        voiceConversionConsent: Boolean = false,
        onAdded: () -> Unit,
    ) {
        serverCall {
            repo.addMember(nickname, isChild, voiceConsent, guardianConsent, conversationConsent, voiceConversionConsent)
            onAdded()
        }
    }

    /** 선택 동의 변경 (설계 §8.3). 목소리 변환을 끄면 서버의 참고 목소리가 바로 지워진다. */
    fun setConsents(memberId: String, conversationConsent: Boolean, voiceConversionConsent: Boolean) {
        serverCall { repo.updateConsents(memberId, conversationConsent, voiceConversionConsent) }
    }

    fun deleteMember(memberId: String) {
        serverCall { repo.deleteMember(memberId) }
    }

    fun startRecording(memberId: String, slot: ClipSlot) {
        // uiState 는 한 박자 늦을 수 있어 제한·녹음 상태는 원본 값으로도 확인한다.
        if (restricted.value || local.value.recording != RecordingState.Idle || !uiState.value.canRecord(memberId, slot)) return
        player.stop()
        val file = repo.newRecordingFile()
        try {
            recorder.start(file)
        } catch (e: Exception) {
            file.delete()
            showMessage(R.string.voice_error_microphone)
            return
        }
        local.update { it.copy(recording = RecordingState.Recording(memberId, slot, file)) }
        autoStop = viewModelScope.launch {
            delay(MAX_CLIP_MS)
            stopRecording()
        }
    }

    /** 직접 멈춤, 5초 자동 정지, 주행 제한 시작 모두 여기로 온다. */
    fun stopRecording() {
        val recording = local.value.recording as? RecordingState.Recording ?: return
        autoStop?.cancel()
        if (recorder.stop()) {
            local.update { it.copy(recording = RecordingState.Draft(recording.memberId, recording.slot, recording.file)) }
        } else {
            recording.file.delete()
            local.update { it.copy(recording = RecordingState.Idle, message = R.string.voice_error_too_short) }
        }
    }

    fun previewDraft() {
        val draft = local.value.recording as? RecordingState.Draft ?: return
        play(draft.file)
    }

    fun discardDraft() {
        val draft = local.value.recording as? RecordingState.Draft ?: return
        player.stop()
        draft.file.delete()
        local.update { it.copy(recording = RecordingState.Idle) }
    }

    /** 저장 → 업로드 (설계 §8). 업로드가 실패해도 클립은 남고 다시 시도할 수 있다. */
    fun saveDraft() {
        val draft = local.value.recording as? RecordingState.Draft ?: return
        player.stop()
        local.update { it.copy(recording = RecordingState.Idle) }
        viewModelScope.launch {
            val clip = try {
                repo.addClip(draft.memberId, draft.slot, draft.file)
            } catch (e: IllegalStateException) { // 슬롯이 이미 가득 참
                draft.file.delete()
                showMessage(R.string.voice_error_slot_full)
                return@launch
            }
            upload(clip)
        }
    }

    fun retryUpload(clip: ClipEntity) {
        viewModelScope.launch { upload(clip) }
    }

    fun deleteClip(clip: ClipEntity) {
        if (clip.localId in local.value.uploading) return
        player.stop()
        serverCall { repo.deleteClip(clip) }
    }

    fun playClip(clip: ClipEntity) = play(File(clip.localPath))

    fun setClipPlanMode(mode: ClipPlanMode) {
        viewModelScope.launch { settings.setClipPlanMode(mode) }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    override fun onCleared() {
        discardRecording() // 저장하지 않은 초안 파일도 남기지 않는다 (§8.1)
        player.stop()
    }

    /** 진행 중인 녹음이나 저장하지 않은 초안을 파일과 함께 버린다. */
    private fun discardRecording() {
        when (val r = local.value.recording) {
            is RecordingState.Recording -> {
                autoStop?.cancel()
                recorder.stop()
                r.file.delete()
            }
            is RecordingState.Draft -> {
                player.stop()
                r.file.delete()
            }
            RecordingState.Idle -> return
        }
        local.update { it.copy(recording = RecordingState.Idle) }
    }

    private suspend fun upload(clip: ClipEntity) {
        if (clip.localId in local.value.uploading) return
        local.update { it.copy(uploading = it.uploading + clip.localId) }
        try {
            repo.upload(clip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showMessage(R.string.voice_error_upload)
        } finally {
            local.update { it.copy(uploading = it.uploading - clip.localId) }
        }
    }

    private fun play(file: File) {
        try {
            player.play(file)
        } catch (e: Exception) {
            showMessage(R.string.voice_error_playback)
        }
    }

    private fun serverCall(block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(R.string.voice_error_server)
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun showMessage(@StringRes message: Int) = local.update { it.copy(message = message) }

    companion object {
        /** 클립당 최대 5초 (설계 §8). */
        const val MAX_CLIP_MS = 5_000L
    }
}
