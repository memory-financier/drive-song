package com.drivesong.feature.song

import android.util.Log
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.ClipPlanItem
import com.drivesong.core.network.dto.SongJobDto
import com.drivesong.core.network.dto.SongJobRequest
import com.drivesong.core.network.dto.SongJobStatus
import com.drivesong.feature.lyrics.Lyrics
import com.drivesong.feature.lyrics.LyricsInput
import com.drivesong.feature.lyrics.LyricsProvider
import com.drivesong.feature.trip.TripEvent
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripState
import com.drivesong.feature.trip.conversation.ConversationCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** 설계 §7.1 상태. */
enum class SongPhase {
    IDLE, RECORDING_TRIP, GENERATING_LYRICS, SUBMITTING_JOB, WAITING_FOR_SONG, READY, PLAYING, ARCHIVED, FAILED,
}

data class SongState(
    val phase: SongPhase = SongPhase.IDLE,
    val tripId: String? = null,
    /** 노래 생성을 시작한 뒤 도착 판정이 났는지 (P단 전환, 또는 P단 5분·기록 끄기로 주행 종료). */
    val arrived: Boolean = false,
    /** READY 이후의 노래 (Room 에 저장됨). */
    val song: SongEntity? = null,
    /** 기다리는 서버 잡의 마지막 상태 (대화 노래의 ANALYZING·CONVERTING 을 홈에 보여 준다). */
    val serverStatus: SongJobStatus? = null,
) {
    /** "노래가 곧 완성돼요": 도착했는데 아직 만드는 중 (설계 §7.1). */
    val almostReady: Boolean get() = arrived && phase in SongOrchestrator.MAKING
}

/**
 * 노래 생성 상태 머신 (설계 §3 SongOrchestrator, §7.1).
 *
 * - 트리거: 기록 중 ETA ≤ 10분([ETA_TRIGGER], EtaUpdated 를 받은 시각 기준), 또는 주행 종료 (ETA 가 없었거나
 *   10분 이내로 내려오지 않은 채 끝난 경우).
 * - 가사: POST /v1/trips/enrich → [LyricsProvider] (탑승자 호칭 + 설정의 스타일). clipPlan 은 주행마다 한 번 고른다.
 * - 잡: POST /v1/song-jobs → [POLL_INTERVAL_MS] 마다 GET, [SONG_TIMEOUT_MS] 안에 READY 가 아니면 실패.
 *   실패·타임아웃이면 한 번 더 (이미 받은 가사·완성된 잡은 다시 만들지 않는다), 그래도 실패면 FAILED + 알림.
 * - 완성: 오디오를 내려받아 Room 에 저장 → READY. 도착 전이면 도착 판정 때 (설정이 켜져 있으면) [autoPlay] 이벤트,
 *   이미 도착했으면 바로 알림 ("노래가 곧 완성돼요" → 완료 알림).
 * - 재생 상태: 재생 쪽(T11)이 [onPlaybackStarted] / [onPlaybackEnded] 로 PLAYING / ARCHIVED 로 바꾼다.
 * - 대화 담기 (설계 §7.5, §8.3): 생성을 시작할 때 [ConversationCapture.finish] 로 녹음을 끝내고 조각을 다 올린다.
 *   올린 대화가 있으면 가사를 앱에서 쓰지 않고 `conversation: true` 잡을 만든다 (서버가 대화로 가사·한마디·가족 목소리,
 *   clipPlan 은 이번 주행에 녹음한 클립만, 시간 제한 [CONVERSATION_SONG_TIMEOUT_MS]). 실패하면 대화를 기기·서버에서 지운다.
 * - 한 번에 한 곡: 만드는 중에 새 주행이 시작되면 그 주행은 노래를 만들지 않는다 (그 주행의 대화는 지운다).
 * - 데이터 삭제: [clearForDataDeletion] 이 만들던 노래를 재시도·실패 알림 없이 멈추고 삭제가 끝나면 상태를 되돌린다.
 *
 * 수명: 앱 프로세스 단위 싱글턴. [run] 은 Application 이 앱 시작 시 한 번 띄운다 (DriveSongApplication).
 * 주행 기록 Foreground Service 의 스코프에 묶지 않는 이유는 DriveSongApplication 주석 참고.
 * 시간 제한·폴링은 코루틴 시간(delay), 노래 날짜는 주입된 [Clock] (데모 중에는 시나리오 시각).
 */
@Singleton
class SongOrchestrator @Inject constructor(
    private val tripEvents: TripEvents,
    private val lyricsProvider: LyricsProvider,
    private val api: DriveSongApi,
    private val voice: VoiceRepository,
    private val songs: SongRepository,
    private val settings: SettingsRepository,
    private val notifier: SongNotifier,
    private val clock: Clock,
    private val conversation: ConversationCapture,
) {
    private val _state = MutableStateFlow(SongState())
    val state: StateFlow<SongState> = _state.asStateFlow()

    private val _autoPlay = MutableSharedFlow<SongEntity>(extraBufferCapacity = 1)

    /** 도착 시 자동 재생할 노래 (replay 없음: 재생 쪽은 앱 시작 시 구독해 둘 것). */
    val autoPlay: SharedFlow<SongEntity> = _autoPlay.asSharedFlow()

    /** 지금 만들고 있는 노래 (데이터 삭제 때 취소한다). */
    @Volatile
    private var production: Job? = null

    /**
     * 취소될 때까지 주행 이벤트를 처리한다. 노래 생성은 이 코루틴의 자식으로 돈다.
     * 블로킹 파일 쓰기(다운로드)가 있으므로 IO 디스패처에서 실행할 것.
     */
    suspend fun run() {
        coroutineScope {
            tripEvents.events
                // 구독 전에 이미 시작된 주행도 놓치지 않는다
                .onSubscription { (tripEvents.state.value as? TripState.Recording)?.let { onTripStarted(it.tripId) } }
                .collect { handle(it) }
        }
    }

    /**
     * 설정 > 모든 데이터 삭제 (설계 §8.1). 만들던 노래를 취소해(재시도·실패 알림 없음) 서버에서 지워진 잡을
     * 다시 만들지 않게 한 뒤 [delete] 를 실행한다. 삭제 중에는 IDLE 이라 주행 이벤트로 새 노래를 시작하지 않는다.
     * 끝나면 IDLE 로 돌아가되, 기록 중인 주행(삭제에서 남긴다)이 아직 노래를 시작하지 않았으면 RECORDING_TRIP 으로
     * 이어 간다. 이미 노래를 시작한(지운) 주행은 다시 만들지 않는다 (주행당 한 번).
     */
    suspend fun clearForDataDeletion(delete: suspend () -> Unit) {
        production?.cancelAndJoin()
        val before = _state.getAndUpdate { SongState() }
        try {
            delete()
        } finally {
            val recording = (tripEvents.state.value as? TripState.Recording)?.tripId
            val songStarted = before.tripId == recording && before.phase != SongPhase.RECORDING_TRIP
            _state.value = if (recording != null && !songStarted) SongState(SongPhase.RECORDING_TRIP, recording) else SongState()
        }
    }

    fun onPlaybackStarted(songId: String) =
        _state.update { if (it.phase == SongPhase.READY && it.song?.id == songId) it.copy(phase = SongPhase.PLAYING) else it }

    fun onPlaybackEnded(songId: String) =
        _state.update { if (it.phase == SongPhase.PLAYING && it.song?.id == songId) it.copy(phase = SongPhase.ARCHIVED) else it }

    private suspend fun CoroutineScope.handle(event: TripEvent) {
        val current = _state.value
        val recording = current.phase == SongPhase.RECORDING_TRIP && current.tripId == event.tripId
        when (event) {
            is TripEvent.Started -> onTripStarted(event.tripId)
            is TripEvent.EtaUpdated -> {
                val eta = event.route.etaAt ?: return
                if (recording && Duration.between(event.at, eta) <= ETA_TRIGGER) {
                    tripEvents.currentSummary()?.let { start(it, arrived = false) } // 위치가 아직 없으면 다음 갱신 때
                }
            }
            is TripEvent.Ended -> {
                // P단 5분(PARKED)·기록 끄기(STOPPED)는 도착으로 본다. 안내 종료는 뒤따르는 P단(Arrived)을 기다린다.
                val arrived = event.reason != TripEndReason.NAVIGATION_ENDED
                val summary = event.summary
                if (recording) {
                    if (summary != null) {
                        start(summary, arrived)
                    } else {
                        launch { discardConversation(event.tripId) }
                        fail("trip ${event.tripId} has no location")
                    }
                } else if (arrived && current.tripId == event.tripId) {
                    onArrived()
                } else if (current.tripId != event.tripId) {
                    launch { discardConversation(event.tripId) } // 노래를 만들지 않은 주행 (다른 노래를 만드는 중이었음)
                }
            }
            is TripEvent.Arrived -> if (current.tripId == event.tripId && current.phase in MAKING + SongPhase.READY) onArrived()
        }
    }

    private fun onTripStarted(tripId: String) =
        _state.update { if (it.phase in MAKING) it else SongState(SongPhase.RECORDING_TRIP, tripId) }

    /** 도착은 주행마다 처음 한 번만 처리한다. 노래가 먼저 준비됐으면 자동 재생, 아니면 완성 때 알림. */
    private suspend fun onArrived() {
        val before = _state.getAndUpdate { it.copy(arrived = true) }
        if (!before.arrived && before.phase == SongPhase.READY && settings.autoPlay.first()) {
            _autoPlay.emit(checkNotNull(before.song))
        }
    }

    private fun CoroutineScope.start(summary: TripSummary, arrived: Boolean) {
        _state.update { it.copy(phase = SongPhase.GENERATING_LYRICS, arrived = arrived, serverStatus = null) }
        production = launch { produce(summary) }
    }

    private suspend fun produce(summary: TripSummary) {
        val song = try {
            val passengers = summary.passengers.toSet()
            // 대화 담기: 녹음을 끝내고 남은 조각을 다 올린 뒤에 잡을 만든다 (그 뒤 조각은 서버가 받지 않는다)
            val withConversation = conversation.finish(summary.tripId)
            // 이번 주행 중 음성으로 녹음한 목소리가 있으면 그 슬롯에 우선 쓴다 (설계 §8.2).
            // 대화 노래는 그런 클립만 쓰고 빈 슬롯은 서버가 대화 속 한마디로 채운다 (설계 §7.5)
            val clipPlan = voice.planClips(
                passengers,
                recordedSinceMs = summary.startedAt.toEpochMilli(),
                onlyRecent = withConversation,
            ).map { (slot, clipId) -> ClipPlanItem(slot, clipId) }
            val members = voice.members.first()
            val nicknames = members.filter { it.id in passengers }.map { it.nickname }
            val style = settings.songStyle.first()
            var lyrics: Lyrics? = null
            var finished: SongJobDto? = null
            withOneRetry {
                if (withConversation) {
                    val job = finished ?: makeConversationSong(summary, style, clipPlan).also { finished = it }
                    download(summary, job, title = job.title ?: DEFAULT_TITLE, lyrics = job.lyrics.orEmpty(),
                        singers = job.singers.mapNotNull { id -> members.find { it.id == id }?.nickname })
                } else {
                    val text = lyrics ?: writeLyrics(summary, nicknames, style).also { lyrics = it }
                    val job = finished ?: makeSong(summary, text, style, clipPlan).also { finished = it }
                    download(summary, job, title = job.title ?: text.title ?: DEFAULT_TITLE, lyrics = text.text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            discardConversation(summary.tripId) // 잡을 만들기 전에 실패했으면 서버에 대화가 남아 있다
            fail("song for ${summary.tripId} failed", e)
            return
        }
        val now = _state.updateAndGet { it.copy(phase = SongPhase.READY, song = song) }
        if (now.arrived) notifier.songReady(song)
    }

    private suspend fun writeLyrics(summary: TripSummary, nicknames: List<String>, style: SongStyle): Lyrics {
        _state.update { it.copy(phase = SongPhase.GENERATING_LYRICS) }
        val trip = api.enrichTrip(summary)
        return lyricsProvider.generate(LyricsInput(trip, nicknames, style)).getOrThrow()
    }

    private suspend fun download(summary: TripSummary, job: SongJobDto, title: String, lyrics: String, singers: List<String> = emptyList()) =
        songs.download(
            songId = checkNotNull(job.songId) { "READY job ${job.jobId} without songId" },
            tripId = summary.tripId,
            title = title,
            lyrics = lyrics,
            createdAt = clock.instant(),
            waypoints = summary.waypoints,
            singers = singers,
        )

    /** 잡을 만들고 READY 가 될 때까지 기다린다. */
    private suspend fun makeSong(summary: TripSummary, lyrics: Lyrics, style: SongStyle, clipPlan: List<ClipPlanItem>): SongJobDto {
        _state.update { it.copy(phase = SongPhase.SUBMITTING_JOB) }
        val request = SongJobRequest(summary.tripId, lyrics.text, style.wire, DURATION_SEC, clipPlan, lyrics.title)
        return submitAndWait(request, SONG_TIMEOUT_MS)
    }

    /** 대화 노래 (설계 §7.5, §9.1): 가사는 서버가 대화로 쓴다. 지명·날씨만 붙여 보낸다. */
    private suspend fun makeConversationSong(summary: TripSummary, style: SongStyle, clipPlan: List<ClipPlanItem>): SongJobDto {
        _state.update { it.copy(phase = SongPhase.GENERATING_LYRICS) }
        val trip = api.enrichTrip(summary)
        _state.update { it.copy(phase = SongPhase.SUBMITTING_JOB) }
        val request = SongJobRequest(
            tripId = summary.tripId, style = style.wire, durationSec = DURATION_SEC, clipPlan = clipPlan,
            conversation = true, enrichedTrip = trip,
        )
        return submitAndWait(request, CONVERSATION_SONG_TIMEOUT_MS)
    }

    private suspend fun submitAndWait(request: SongJobRequest, timeoutMs: Long): SongJobDto {
        val job = api.createSongJob(request)
        _state.update { it.copy(phase = SongPhase.WAITING_FOR_SONG, serverStatus = job.status) }
        return withTimeoutOrNull(timeoutMs) { poll(job.jobId) }
            ?: throw SongJobException("job ${job.jobId} not ready after ${timeoutMs / 1000} s")
    }

    private suspend fun discardConversation(tripId: String) {
        try {
            conversation.discard(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not discard the conversation of $tripId", e)
        }
    }

    /** 폴링 중 네트워크 오류는 건너뛰고 계속 묻는다 (전체 시간 제한은 호출하는 쪽). */
    private suspend fun poll(jobId: String): SongJobDto {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val job = try {
                api.getSongJob(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "poll $jobId failed", e)
                continue
            }
            _state.update { if (it.phase == SongPhase.WAITING_FOR_SONG) it.copy(serverStatus = job.status) else it }
            when (job.status) {
                SongJobStatus.READY -> return job
                SongJobStatus.FAILED -> throw SongJobException("job $jobId failed: ${job.error}")
                else -> Unit
            }
        }
    }

    private suspend fun <T> withOneRetry(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "song attempt failed, retrying once", e)
        block()
    }

    private fun fail(message: String, cause: Exception? = null) {
        Log.w(TAG, message, cause)
        _state.update { it.copy(phase = SongPhase.FAILED) }
        notifier.songFailed()
    }

    private class SongJobException(message: String) : Exception(message)

    companion object {
        private const val TAG = "SongOrchestrator"
        val ETA_TRIGGER: Duration = Duration.ofMinutes(10)
        const val POLL_INTERVAL_MS = 5_000L
        const val SONG_TIMEOUT_MS = 5 * 60_000L

        /**
         * 대화 노래: 분석 + 가족 N명 변환 (설계 §7.5 실측: 3명 168초, 약 30초 + 45초 x N). 도착 10분 전에 시작하므로
         * 그 안에 끝나도록 [ETA_TRIGGER] 와 같게 둔다 `[확인 필요]` 긴 대화·4명 이상.
         */
        const val CONVERSATION_SONG_TIMEOUT_MS = 10 * 60_000L

        /** 설계 §7.3 곡 길이 60~90초 (§9 예시 값). */
        const val DURATION_SEC = 75
        const val DEFAULT_TITLE = "오늘의 드라이브 송"

        /** 노래를 만드는 중인 단계. */
        val MAKING = setOf(SongPhase.GENERATING_LYRICS, SongPhase.SUBMITTING_JOB, SongPhase.WAITING_FOR_SONG)
    }
}
