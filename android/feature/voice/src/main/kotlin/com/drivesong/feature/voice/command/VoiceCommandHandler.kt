package com.drivesong.feature.voice.command

import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.VoiceCommand
import com.drivesong.core.model.VoiceCommandParser
import com.drivesong.feature.player.SongPlayer
import com.drivesong.feature.voice.VoiceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** 음성 명령 결과 (설계 §8.2). [success] 는 Gleo 응답의 status. */
enum class VoiceResult(val success: Boolean) {
    PLAYING(true),
    PAUSED(true),
    NO_SONG(false),
    SAVED(true),
    WHO(false),
    UNKNOWN_MEMBER(false),
    MIC_ERROR(false),
    TOO_SHORT(false),
    SAVE_FAILED(false),
    NOT_UNDERSTOOD(false),
    STT_UNAVAILABLE(false),
    BUSY(false),
}

/** 홈에 보이는 한 줄 상태 (정적 텍스트, 설계 §2). */
sealed interface VoiceStatus {
    data object Idle : VoiceStatus
    data object Listening : VoiceStatus
    data class Recording(val nickname: String, val slot: ClipSlot) : VoiceStatus
    data class Finished(val result: VoiceResult, val nickname: String? = null, val slot: ClipSlot? = null) : VoiceStatus
}

/**
 * 음성 명령 실행 (설계 §8.2). "말로 하기"(SpeechToText)와 Gleo Interface 가 같은 처리를 쓴다.
 * 한 번에 하나만 처리하고, 처리 중에 들어온 명령은 [VoiceResult.BUSY].
 * 녹음은 주행 중에도 한다 (화면 조작 없음): 신호음 → 5초 → 자동 저장, 업로드는 뒤에서.
 */
@Singleton
class VoiceCommandHandler @Inject constructor(
    private val voice: VoiceRepository,
    private val songs: SongRepository,
    private val player: SongPlayer,
    private val settings: SettingsRepository,
    private val recorder: VoiceRecorder,
    private val cue: RecordingCue,
    private val speech: SpeechInput,
) {
    /** 화면이 닫혀도 녹음·업로드가 끝까지 가도록 핸들러가 자기 스코프를 갖는다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val busy = Mutex()
    private var clearStatus: Job? = null

    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    /** "말로 하기" 버튼. */
    fun startListening() {
        scope.launch { listenAndRun() }
    }

    /** Gleo 호출 등 이미 해석된 명령. 결과가 나오면 [onResult] (메인 스레드). */
    fun submit(command: VoiceCommand, onResult: (VoiceResult) -> Unit) {
        scope.launch { onResult(run(command)) }
    }

    /** 한 번 듣고, 알아들은 명령을 실행한다. */
    suspend fun listenAndRun(): VoiceResult = exclusive {
        _status.value = VoiceStatus.Listening
        when (val heard = speech.listen()) {
            SpeechResult.Unavailable -> finish(VoiceResult.STT_UNAVAILABLE)
            SpeechResult.NoSpeech -> finish(VoiceResult.NOT_UNDERSTOOD)
            is SpeechResult.Text -> {
                val nicknames = voice.members.first().map { it.nickname }
                val command = VoiceCommandParser.parse(heard.text, nicknames)
                if (command == null) finish(VoiceResult.NOT_UNDERSTOOD) else execute(command)
            }
        }
    }

    suspend fun run(command: VoiceCommand): VoiceResult = exclusive { execute(command) }

    private suspend fun exclusive(block: suspend () -> VoiceResult): VoiceResult {
        if (!busy.tryLock()) return VoiceResult.BUSY
        clearStatus?.cancel()
        return try {
            block()
        } finally {
            busy.unlock()
            clearStatus = scope.launch {
                delay(STATUS_VISIBLE_MS)
                if (_status.value is VoiceStatus.Finished) _status.value = VoiceStatus.Idle
            }
        }
    }

    private suspend fun execute(command: VoiceCommand): VoiceResult = when (command) {
        VoiceCommand.PlaySong -> {
            val list = songs.songs.first()
            if (list.isEmpty()) {
                finish(VoiceResult.NO_SONG)
            } else {
                player.play(list, 0)
                finish(VoiceResult.PLAYING)
            }
        }
        VoiceCommand.PauseSong -> {
            if (player.nowPlaying.value.isPlaying) player.togglePlayPause()
            finish(VoiceResult.PAUSED)
        }
        is VoiceCommand.RecordClip -> record(command)
    }

    private suspend fun record(command: VoiceCommand.RecordClip): VoiceResult {
        val member = when (val found = findMember(command.nickname)) {
            is Found.Member -> found.member
            Found.Unknown -> return finish(VoiceResult.UNKNOWN_MEMBER, nickname = command.nickname)
            Found.Ambiguous -> return finish(VoiceResult.WHO)
        }
        val slot = command.slot ?: ClipSlot.CALL
        _status.value = VoiceStatus.Recording(member.nickname, slot)
        if (player.nowPlaying.value.isPlaying) player.togglePlayPause() // 노래가 녹음에 섞이지 않게
        cue.beforeRecording()
        val file = voice.newRecordingFile()
        try {
            recorder.start(file)
        } catch (e: Exception) {
            file.delete()
            return finish(VoiceResult.MIC_ERROR)
        }
        var recorded = false
        try {
            delay(MAX_CLIP_MS)
        } finally {
            recorded = recorder.stop()
            if (!recorded) file.delete()
        }
        if (!recorded) return finish(VoiceResult.TOO_SHORT)
        val clip = try {
            voice.addClipReplacingOldest(member.id, slot, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            return finish(VoiceResult.SAVE_FAILED)
        }
        // 업로드 실패는 가족·녹음 화면의 "다시 업로드"로 남는다 (설계 §8).
        scope.launch { runCatching { voice.upload(clip) } }
        return finish(VoiceResult.SAVED, member.nickname, slot)
    }

    private sealed interface Found {
        data class Member(val member: MemberEntity) : Found
        data object Unknown : Found
        data object Ambiguous : Found
    }

    /** 호칭이 있으면 그 사람, 없으면 이번 탑승자 1명 또는 등록된 구성원 1명. */
    private suspend fun findMember(nickname: String?): Found {
        val members = voice.members.first()
        if (nickname != null) {
            val wanted = nickname.compact()
            return members.firstOrNull { it.nickname.compact() == wanted }?.let(Found::Member) ?: Found.Unknown
        }
        val passengers = settings.selectedPassengerIds.first()
        val single = members.filter { it.id in passengers }.singleOrNull() ?: members.singleOrNull()
        return single?.let(Found::Member) ?: Found.Ambiguous
    }

    private fun finish(result: VoiceResult, nickname: String? = null, slot: ClipSlot? = null): VoiceResult {
        _status.value = VoiceStatus.Finished(result, nickname, slot)
        return result
    }

    private fun String.compact() = filterNot(Char::isWhitespace)

    companion object {
        /** 클립당 최대 5초 (설계 §8). */
        const val MAX_CLIP_MS = 5_000L

        /** 결과 한 줄을 보여주는 시간. */
        const val STATUS_VISIBLE_MS = 8_000L
    }
}
