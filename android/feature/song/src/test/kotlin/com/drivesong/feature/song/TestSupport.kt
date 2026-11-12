package com.drivesong.feature.song

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.TimeOfDay
import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.ClipDto
import com.drivesong.core.network.dto.ConversationChunkDto
import com.drivesong.core.network.dto.EnrichedTripDto
import com.drivesong.core.network.dto.LyricsDto
import com.drivesong.core.network.dto.LyricsRequest
import com.drivesong.core.network.dto.MemberConsentUpdate
import com.drivesong.core.network.dto.MemberDto
import com.drivesong.core.network.dto.MemberRequest
import com.drivesong.core.network.dto.SongJobDto
import com.drivesong.core.network.dto.SongJobRequest
import com.drivesong.core.network.dto.SongJobStatus
import com.drivesong.feature.trip.TripEvent
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripState
import com.drivesong.feature.trip.conversation.ConversationCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Room 인메모리 DB. 쿼리를 호출한 스레드에서 바로 실행해 코루틴 가상 시간과 함께 쓸 수 있게 한다. */
fun inMemoryDatabase(): DriveSongDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DriveSongDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

/** 코루틴 가상 시간을 따르는 시계: [start] + 경과 가상 시간. */
@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class VirtualClock(private val scheduler: TestCoroutineScheduler, private val start: Instant) : Clock() {
    override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
    override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
    override fun withZone(zone: ZoneId): Clock = this
}

/** 주행 기록 대신 테스트가 직접 이벤트를 보낸다. */
class FakeTripEvents : TripEvents {
    override val state = MutableStateFlow<TripState>(TripState.Idle)
    override val events = MutableSharedFlow<TripEvent>(extraBufferCapacity = 64)
    var summary: TripSummary? = null
    var summaryRequests = 0

    override suspend fun currentSummary(): TripSummary? {
        summaryRequests++
        return summary
    }
}

/**
 * enrich / lyrics / song-jobs / audio 만 흉내 낸다.
 * [jobStatus] = (jobId, 그 잡의 폴링 횟수 1..) → 상태. [lyricsFailures] 만큼 가사 요청을 먼저 실패시킨다.
 * DELETE /v1/songs 뒤에는 그때까지의 잡을 물으면 서버처럼 실패한다 (404).
 */
class FakeSongApi : DriveSongApi {
    var jobStatus: (jobId: String, poll: Int) -> SongJobStatus = { _, _ -> SongJobStatus.READY }
    var lyricsFailures = 0

    /** 대화 잡이 READY 일 때 돌려줄 부른 구성원 id. */
    var singers = listOf("mem_mom", "mem_kid")

    /** enrich·lyrics 응답 지연 (서버 처리 시간 흉내, 코루틴 가상 시간). */
    var callDelayMs = 0L
    val enrichRequests = mutableListOf<TripSummary>()
    val lyricsRequests = mutableListOf<LyricsRequest>()
    val jobRequests = mutableListOf<SongJobRequest>()
    val polls = mutableMapOf<String, Int>()
    val downloads = mutableListOf<String>()
    var deleteAllRequests = 0
    private var deletedJobs = 0

    override suspend fun enrichTrip(trip: TripSummary): EnrichedTripDto {
        enrichRequests += trip
        delay(callDelayMs)
        return EnrichedTripDto(
            tripId = trip.tripId, startedAt = trip.startedAt, endedAt = trip.endedAt, etaAt = trip.etaAt,
            origin = trip.origin, destination = trip.destination, destinationName = trip.destinationName,
            waypoints = trip.waypoints, distanceKm = trip.distanceKm, durationMin = trip.durationMin,
            avgSpeedKmh = trip.avgSpeedKmh, maxSpeedKmh = trip.maxSpeedKmh, timeOfDay = trip.timeOfDay,
            passengers = trip.passengers, originName = "서울 중구", placeNames = listOf("서울 중구", "평창군", "강릉시"),
        )
    }

    override suspend fun generateLyrics(body: LyricsRequest): LyricsDto {
        lyricsRequests += body
        delay(callDelayMs)
        if (lyricsFailures > 0) {
            lyricsFailures--
            throw IOException("llm down")
        }
        return LyricsDto(LYRICS, body.enrichedTrip.destinationName?.let { "$it 가는 길" })
    }

    override suspend fun createSongJob(body: SongJobRequest): SongJobDto {
        jobRequests += body
        return SongJobDto("job_${jobRequests.size}", SongJobStatus.QUEUED, title = body.title ?: "오늘의 드라이브 송")
    }

    override suspend fun getSongJob(jobId: String): SongJobDto {
        val n = (polls[jobId] ?: 0) + 1
        polls[jobId] = n
        if (jobId.removePrefix("job_").toInt() <= deletedJobs) throw IOException("HTTP 404 job $jobId")
        val status = jobStatus(jobId, n)
        val ready = status == SongJobStatus.READY
        return SongJobDto(
            jobId = jobId,
            status = status,
            songId = if (ready) "song_$jobId" else null,
            audioUrl = if (ready) "/v1/songs/song_$jobId/audio" else null,
            title = jobRequests.last().title ?: "오늘의 드라이브 송",
            error = if (status == SongJobStatus.FAILED) "provider error" else null,
            lyrics = if (ready && jobRequests.last().conversation) CONVERSATION_LYRICS else null,
            singers = if (ready && jobRequests.last().conversation) singers else emptyList(),
        )
    }

    override suspend fun downloadSongAudio(songId: String): ResponseBody {
        downloads += songId
        return AUDIO.toResponseBody("audio/mp4".toMediaType())
    }

    override suspend fun deleteAllSongs() {
        deleteAllRequests++
        deletedJobs = jobRequests.size
    }
    override suspend fun registerDevice() = throw UnsupportedOperationException()
    override suspend fun createMember(body: MemberRequest) = throw UnsupportedOperationException()
    override suspend fun deleteMember(memberId: String) = throw UnsupportedOperationException()
    override suspend fun uploadClip(memberId: RequestBody, slot: RequestBody, audio: MultipartBody.Part): ClipDto =
        throw UnsupportedOperationException()
    override suspend fun deleteClip(clipId: String) = throw UnsupportedOperationException()
    override suspend fun updateMember(memberId: String, body: MemberConsentUpdate): MemberDto = throw UnsupportedOperationException()
    override suspend fun uploadConversation(
        tripId: String,
        seq: RequestBody,
        passengers: List<MultipartBody.Part>,
        audio: MultipartBody.Part,
    ): ConversationChunkDto = throw UnsupportedOperationException()
    override suspend fun deleteConversation(tripId: String) = throw UnsupportedOperationException()

    companion object {
        const val LYRICS = "[verse]\n강릉 가는 길\n[chorus]\n엄마랑 첫째랑"
        const val CONVERSATION_LYRICS = "[verse]\n바다 보러 가자\n[chorus]\n호두과자 먹자"
        val AUDIO = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112) // "....ftyp"
    }
}

class FakeNotifier : SongNotifier {
    val ready = mutableListOf<SongEntity>()
    var failed = 0
    override fun songReady(song: SongEntity) {
        ready += song
    }
    override fun songFailed() {
        failed++
    }
}

class FakeSettings : SettingsRepository {
    override val simulateDrivingRestriction = MutableStateFlow(false)
    override suspend fun setSimulateDrivingRestriction(enabled: Boolean) = Unit
    override val demoMode = MutableStateFlow(true)
    override suspend fun setDemoMode(enabled: Boolean) = Unit
    override val destinationPresetId = MutableStateFlow<String?>(null)
    override suspend fun setDestinationPresetId(id: String?) = Unit
    override val selectedPassengerIds = MutableStateFlow(emptySet<String>())
    override suspend fun setSelectedPassengerIds(ids: Set<String>) {
        selectedPassengerIds.value = ids
    }
    override val clipPlanMode = MutableStateFlow(ClipPlanMode.IN_ORDER)
    override suspend fun setClipPlanMode(mode: ClipPlanMode) = Unit
    override val songStyle = MutableStateFlow(SongStyle.KIDS)
    override suspend fun setSongStyle(style: SongStyle) = Unit
    override val autoPlay = MutableStateFlow(true)
    override suspend fun setAutoPlay(enabled: Boolean) = Unit
    override val ambientLightShow = MutableStateFlow(true)
    override suspend fun setAmbientLightShow(enabled: Boolean) = Unit
}

/** 서울 → 강릉 데모 주행의 요약 (ETA 트리거 시점: 진행 중이라 endedAt = null). */
fun demoSummary(tripId: String, passengers: List<String>, endedAt: Instant? = null) = TripSummary(
    tripId = tripId,
    startedAt = Instant.parse("2026-11-01T00:30:00Z"),
    endedAt = endedAt,
    etaAt = Instant.parse("2026-11-01T03:30:00Z"),
    origin = GeoPoint(37.5663, 126.9779),
    destination = GeoPoint(37.804, 128.906),
    destinationName = "강릉 경포해변",
    waypoints = listOf(GeoPoint(37.5663, 126.9779), GeoPoint(37.678, 128.713), GeoPoint(37.795, 128.9)),
    distanceKm = 230.5,
    durationMin = 170,
    avgSpeedKmh = 81.4,
    maxSpeedKmh = 105.0,
    timeOfDay = TimeOfDay.MORNING,
    passengers = passengers,
)

/** 대화 담기 대신: [uploaded] 가 true 면 finish 가 "올린 대화가 있다" 고 답한다. */
class FakeConversation(var uploaded: Boolean = false) : ConversationCapture {
    val finished = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    override suspend fun finish(tripId: String): Boolean {
        finished += tripId
        return uploaded
    }
    override suspend fun discard(tripId: String) {
        discarded += tripId
    }
}
