package com.drivesong.feature.song

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.ClipEntity
import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.db.UploadState
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.network.dto.ClipPlanItem
import com.drivesong.core.network.dto.SongJobRequest
import com.drivesong.core.network.dto.SongJobStatus
import com.drivesong.feature.lyrics.RemoteLyricsProvider
import com.drivesong.feature.trip.TripEvent
import com.drivesong.feature.trip.TripState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SongOrchestratorTest {

    private val dispatcher = StandardTestDispatcher()
    private val start = Instant.parse("2026-11-01T00:30:00Z")
    private val clock = VirtualClock(dispatcher.scheduler, start)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDatabase()
    private val api = FakeSongApi()
    private val settings = FakeSettings()
    private val trip = FakeTripEvents()
    private val notifier = FakeNotifier()
    private val conversation = FakeConversation()
    private val songs = SongRepository(db.songDao(), api, context)
    private val orchestrator = SongOrchestrator(
        tripEvents = trip,
        lyricsProvider = RemoteLyricsProvider(api),
        api = api,
        voice = VoiceRepository(db.voiceDao(), api, settings, context),
        songs = songs,
        settings = settings,
        notifier = notifier,
        clock = clock,
        conversation = conversation,
    )
    private val autoPlayed = mutableListOf<SongEntity>()
    private val passengers = listOf("mem_kid", "mem_mom")

    @After
    fun tearDown() = db.close()

    private val state get() = orchestrator.state.value

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        // 구성원 3명, 슬롯마다 업로드된 클립 1개. 아빠는 이번 주행에 타지 않았다.
        val voice = db.voiceDao()
        voice.insertMember(MemberEntity("mem_mom", "엄마", isChild = false, voiceConsent = true, guardianConsent = false))
        voice.insertMember(MemberEntity("mem_kid", "첫째", isChild = true, voiceConsent = true, guardianConsent = true))
        voice.insertMember(MemberEntity("mem_dad", "아빠", isChild = false, voiceConsent = true, guardianConsent = false))
        voice.insertClip(ClipEntity(memberId = "mem_mom", slot = ClipSlot.INTRO, localPath = "/x", serverId = "clip_a1", uploadState = UploadState.UPLOADED))
        voice.insertClip(ClipEntity(memberId = "mem_kid", slot = ClipSlot.CALL, localPath = "/y", serverId = "clip_b2", uploadState = UploadState.UPLOADED))
        voice.insertClip(ClipEntity(memberId = "mem_dad", slot = ClipSlot.OUTRO, localPath = "/z", serverId = "clip_c3", uploadState = UploadState.UPLOADED))

        backgroundScope.launch { orchestrator.run() }
        backgroundScope.launch { orchestrator.autoPlay.collect { autoPlayed += it } }
        runCurrent()
        block()
    }

    private suspend fun TestScope.send(event: TripEvent) {
        trip.events.emit(event)
        runCurrent()
    }

    private fun now() = clock.instant()

    private fun eta(seconds: Long, etaAt: Instant? = now().plusSeconds(seconds)) = TripEvent.EtaUpdated(
        "trip_1",
        RouteInfo(GeoPoint(37.804, 128.906), "강릉 경포해변", etaAt, remainingDistanceKm = seconds / 60.0),
        now(),
    )

    /**
     * 주행 시작 → ETA 30분, 10분 1초(트리거 안 됨) → ETA 정확히 10분(트리거).
     * 요약은 처음부터 준비돼 있다 (트리거 판정만으로 막히는지 확인). 잡 제출 직후(WAITING_FOR_SONG)에 돌아온다.
     */
    private suspend fun TestScope.driveUntilEtaTrigger() {
        trip.summary = demoSummary("trip_1", passengers)
        send(TripEvent.Started("trip_1", now()))
        assertEquals(SongPhase.RECORDING_TRIP, state.phase)
        send(eta(30 * 60))
        send(eta(10 * 60 + 1))
        assertEquals(SongPhase.RECORDING_TRIP, state.phase)
        assertEquals(0, trip.summaryRequests)

        send(eta(10 * 60))
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
    }

    @Test
    fun `demo trip - song is READY before arrival and arrival emits the auto-play event`() = test {
        api.jobStatus = { _, poll -> if (poll < 2) SongJobStatus.GENERATING else SongJobStatus.READY }

        driveUntilEtaTrigger()

        // 가사 요청: 서버가 붙인 지명 + 탑승자 호칭(구성원 등록 순) + 설정 스타일
        assertEquals(listOf(demoSummary("trip_1", passengers)), api.enrichRequests)
        val lyricsRequest = api.lyricsRequests.single()
        assertEquals(listOf("엄마", "첫째"), lyricsRequest.familyNicknames)
        assertEquals("kids", lyricsRequest.style)
        assertEquals(listOf("서울 중구", "평창군", "강릉시"), lyricsRequest.enrichedTrip.placeNames)
        // 잡 요청: 탑승자 클립만 (아빠 OUTRO 제외), 75초, 가사 응답의 제목
        assertEquals(
            SongJobRequest(
                tripId = "trip_1",
                lyrics = FakeSongApi.LYRICS,
                style = "kids",
                durationSec = 75,
                clipPlan = listOf(ClipPlanItem(ClipSlot.INTRO, "clip_a1"), ClipPlanItem(ClipSlot.CALL, "clip_b2")),
                title = "강릉 경포해변 가는 길",
            ),
            api.jobRequests.single(),
        )

        // 5초마다 폴링: 4.999초엔 아직, 5초에 GENERATING, 10초에 READY
        advanceTimeBy(4_999)
        assertTrue(api.polls.isEmpty())
        advanceTimeBy(2)
        assertEquals(1, api.polls["job_1"])
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
        advanceTimeBy(5_000)
        assertEquals(SongPhase.READY, state.phase)
        assertFalse(state.arrived)

        // 내려받아 Room 에 저장 (제목, 가사, 날짜, tripId, 로컬 경로, 커버용 경로)
        val song = db.songDao().songs().first().single()
        assertEquals(song, state.song)
        assertEquals("song_job_1", song.id)
        assertEquals("trip_1", song.tripId)
        assertEquals("강릉 경포해변 가는 길", song.title)
        assertEquals(FakeSongApi.LYRICS, song.lyrics)
        assertEquals(start.plusMillis(10_000).toEpochMilli(), song.createdAtMs)
        assertEquals(demoSummary("trip_1", passengers).waypoints, song.waypoints)
        assertEquals(File(context.filesDir, "songs/song_job_1.m4a").absolutePath, song.audioPath)
        assertArrayEquals(FakeSongApi.AUDIO, File(song.audioPath).readBytes())

        // 도착 전에는 재생하지 않는다
        assertTrue(autoPlayed.isEmpty())

        // 도착 판정 (P단) → 자동 재생 이벤트, 알림은 없음
        send(TripEvent.Arrived("trip_1", now()))
        assertEquals(listOf(song), autoPlayed)
        assertTrue(notifier.ready.isEmpty())

        orchestrator.onPlaybackStarted(song.id)
        assertEquals(SongPhase.PLAYING, state.phase)
        orchestrator.onPlaybackEnded(song.id)
        assertEquals(SongPhase.ARCHIVED, state.phase)

        // 안내 종료·P단 5분 종료가 뒤따라 와도 다시 재생하지 않는다
        send(TripEvent.Ended("trip_1", TripEndReason.PARKED, now(), demoSummary("trip_1", passengers, now())))
        assertEquals(1, autoPlayed.size)
        assertEquals(1, api.jobRequests.size)
    }

    @Test
    fun `without ETA the song starts when the trip ends and a notification follows`() = test {
        api.jobStatus = { _, _ -> SongJobStatus.READY }
        send(TripEvent.Started("trip_1", now()))
        trip.summary = demoSummary("trip_1", passengers)
        send(eta(5 * 60, etaAt = null)) // 경로는 있지만 ETA 없음
        // 주행 중 P단(휴게소 등)은 생성 전이라 무시된다
        send(TripEvent.Arrived("trip_1", now()))
        assertEquals(SongPhase.RECORDING_TRIP, state.phase)
        assertTrue(api.enrichRequests.isEmpty())
        assertEquals(0, trip.summaryRequests)

        // P단 5분 → 주행 종료 → 생성 시작. 이미 도착했으므로 "곧 완성돼요"
        val ended = demoSummary("trip_1", passengers, endedAt = now())
        send(TripEvent.Ended("trip_1", TripEndReason.PARKED, now(), ended))
        assertEquals(listOf(ended), api.enrichRequests)
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
        assertTrue(state.almostReady)

        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)
        assertFalse(state.almostReady)
        assertEquals(listOf(state.song), notifier.ready)
        assertTrue(autoPlayed.isEmpty())
    }

    @Test
    fun `navigation ended before ETA trigger starts the song and waits for the P gear`() = test {
        send(TripEvent.Started("trip_1", now()))
        send(TripEvent.Ended("trip_1", TripEndReason.NAVIGATION_ENDED, now(), demoSummary("trip_1", passengers, now())))
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
        assertFalse(state.arrived)

        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)
        send(TripEvent.Arrived("trip_1", now()))
        assertEquals(listOf(state.song), autoPlayed)
    }

    @Test
    fun `a failed job is retried once with the same lyrics and clip plan`() = test {
        api.jobStatus = { jobId, _ -> if (jobId == "job_1") SongJobStatus.FAILED else SongJobStatus.READY }

        driveUntilEtaTrigger()
        advanceTimeBy(5_001) // job_1 FAILED → 바로 재제출
        assertEquals(2, api.jobRequests.size)
        assertEquals(api.jobRequests[0], api.jobRequests[1])
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)

        advanceTimeBy(5_000)
        assertEquals(SongPhase.READY, state.phase)
        assertEquals("song_job_2", state.song?.id)
        assertEquals(1, api.lyricsRequests.size) // 가사는 다시 만들지 않는다
        assertEquals(1L, db.voiceDao().maxUsedSeq()) // 클립은 주행당 한 번만 고른다
        assertEquals(0, notifier.failed)
    }

    @Test
    fun `lyrics failure is retried once`() = test {
        api.lyricsFailures = 1

        driveUntilEtaTrigger()
        assertEquals(2, api.lyricsRequests.size)
        assertEquals(1, api.jobRequests.size)
        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)
    }

    @Test
    fun `second failure ends in FAILED with a user notification`() = test {
        api.jobStatus = { _, _ -> SongJobStatus.FAILED }

        driveUntilEtaTrigger()
        advanceTimeBy(10_001)
        assertEquals(SongPhase.FAILED, state.phase)
        assertEquals(1, notifier.failed)
        assertNull(state.song)

        advanceTimeBy(60_000)
        assertEquals(2, api.jobRequests.size) // 재시도는 한 번뿐
        send(TripEvent.Arrived("trip_1", now()))
        assertTrue(autoPlayed.isEmpty())
    }

    @Test
    fun `no READY within 5 minutes times out, retries once, then fails`() = test {
        api.jobStatus = { _, _ -> SongJobStatus.GENERATING }

        driveUntilEtaTrigger()
        advanceTimeBy(SongOrchestrator.SONG_TIMEOUT_MS - 1)
        assertEquals(1, api.jobRequests.size)
        advanceTimeBy(2)
        assertEquals(2, api.jobRequests.size) // 5분 → 타임아웃 → 재제출
        assertEquals(59, api.polls["job_1"]) // 5초 간격 (300초 째 폴링은 타임아웃과 겹쳐 취소)
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)

        advanceTimeBy(SongOrchestrator.SONG_TIMEOUT_MS)
        assertEquals(SongPhase.FAILED, state.phase)
        assertEquals(1, notifier.failed)
        assertEquals(2, api.jobRequests.size)
    }

    @Test
    fun `timeout then success on the retry`() = test {
        api.jobStatus = { jobId, _ -> if (jobId == "job_1") SongJobStatus.GENERATING else SongJobStatus.READY }

        driveUntilEtaTrigger()
        advanceTimeBy(SongOrchestrator.SONG_TIMEOUT_MS + 5_001)
        assertEquals(SongPhase.READY, state.phase)
        assertEquals("song_job_2", state.song?.id)
        assertEquals(0, notifier.failed)
    }

    @Test
    fun `auto-play off - arrival does not emit the event`() = test {
        settings.autoPlay.value = false

        driveUntilEtaTrigger()
        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)

        send(TripEvent.Arrived("trip_1", now()))
        assertTrue(autoPlayed.isEmpty())
        assertTrue(state.arrived)
        assertEquals(SongPhase.READY, state.phase)
        assertTrue(notifier.ready.isEmpty())
    }

    @Test
    fun `arrival while waiting shows almost ready and notifies on completion`() = test {
        api.jobStatus = { _, poll -> if (poll < 3) SongJobStatus.MIXING else SongJobStatus.READY }

        driveUntilEtaTrigger()
        assertFalse(state.almostReady)
        send(TripEvent.Arrived("trip_1", now()))
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
        assertTrue(state.almostReady)
        assertTrue(notifier.ready.isEmpty())

        advanceTimeBy(15_001)
        assertEquals(SongPhase.READY, state.phase)
        assertFalse(state.almostReady)
        assertEquals(listOf(state.song), notifier.ready)
        assertTrue(autoPlayed.isEmpty()) // 도착 후 완성은 알림만 (설계 §7.1)
    }

    @Test
    fun `a trip already recording when the orchestrator starts is picked up`() = runTest(dispatcher) {
        trip.state.value = TripState.Recording("trip_9", now(), route = null)
        backgroundScope.launch { orchestrator.run() }
        runCurrent()

        assertEquals(SongState(SongPhase.RECORDING_TRIP, "trip_9"), state)
    }

    @Test
    fun `a new trip during generation does not interrupt the current song`() = test {
        driveUntilEtaTrigger()
        send(TripEvent.Started("trip_2", now()))
        assertEquals("trip_1", state.tripId)

        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)
        assertEquals("trip_1", state.song?.tripId)

        // 노래가 준비된 뒤 시작한 주행은 새로 기록한다
        send(TripEvent.Started("trip_3", now()))
        assertEquals(SongState(SongPhase.RECORDING_TRIP, "trip_3"), state)
    }

    /** 설정 > 모든 데이터 삭제와 같은 순서: 오케스트레이터를 멈추고 서버 노래·잡 → 로컬 노래를 지운다. */
    private suspend fun TestScope.deleteAllData() {
        orchestrator.clearForDataDeletion {
            api.deleteAllSongs()
            songs.deleteAll()
        }
        runCurrent()
    }

    @Test
    fun `data deletion while waiting cancels the song without retrying or notifying`() = test {
        trip.state.value = TripState.Recording("trip_1", now(), route = null)
        api.jobStatus = { _, _ -> SongJobStatus.GENERATING }
        driveUntilEtaTrigger()
        advanceTimeBy(5_001)

        deleteAllData()
        assertEquals(1, api.deleteAllRequests)
        // 기록은 이어지지만 이 주행의 노래는 이미 시작했다가 지웠으므로 다시 만들지 않는다
        assertEquals(SongState(), state)

        api.jobStatus = { _, _ -> SongJobStatus.READY }
        advanceTimeBy(2 * SongOrchestrator.SONG_TIMEOUT_MS)
        send(eta(5 * 60))
        send(TripEvent.Ended("trip_1", TripEndReason.PARKED, now(), demoSummary("trip_1", passengers, now())))
        advanceTimeBy(60_000)

        assertEquals(1, api.jobRequests.size) // 지운 잡을 재시도로 다시 만들지 않는다
        assertEquals(1, api.polls["job_1"]) // 취소 뒤에는 폴링도 멈춘다
        assertEquals(0, notifier.failed)
        assertTrue(notifier.ready.isEmpty())
        assertTrue(db.songDao().songs().first().isEmpty())
        assertEquals(SongState(), state)

        // 다음 주행은 평소대로 노래를 만든다
        send(TripEvent.Started("trip_2", now()))
        assertEquals(SongState(SongPhase.RECORDING_TRIP, "trip_2"), state)
    }

    @Test
    fun `data deletion after the song is ready returns to IDLE`() = test {
        driveUntilEtaTrigger()
        advanceTimeBy(5_001)
        send(TripEvent.Arrived("trip_1", now()))
        orchestrator.onPlaybackStarted(state.song!!.id)
        orchestrator.onPlaybackEnded(state.song!!.id)
        assertEquals(SongPhase.ARCHIVED, state.phase)

        deleteAllData()

        assertEquals(SongState(), state)
        assertTrue(db.songDao().songs().first().isEmpty())
    }

    @Test
    fun `data deletion before the trigger keeps the recording trip armed`() = test {
        trip.state.value = TripState.Recording("trip_1", now(), route = null)
        trip.summary = demoSummary("trip_1", passengers)
        send(TripEvent.Started("trip_1", now()))

        deleteAllData()
        assertEquals(SongState(SongPhase.RECORDING_TRIP, "trip_1"), state)

        send(eta(10 * 60))
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)
    }

    @Test
    fun `trip events during deletion do not start a song`() = test {
        trip.state.value = TripState.Recording("trip_1", now(), route = null)
        trip.summary = demoSummary("trip_1", passengers)
        send(TripEvent.Started("trip_1", now()))

        orchestrator.clearForDataDeletion {
            send(eta(5 * 60))
            assertEquals(SongPhase.IDLE, state.phase)
        }

        assertTrue(api.jobRequests.isEmpty())
        assertEquals(SongState(SongPhase.RECORDING_TRIP, "trip_1"), state)
    }

    // --- 대화 담기 (설계 §7.5, §9.1) -------------------------------------------------

    @Test
    fun `with an uploaded conversation the server writes the lyrics and the family sings`() = test {
        conversation.uploaded = true

        driveUntilEtaTrigger()
        assertEquals(listOf("trip_1"), conversation.finished) // 잡보다 먼저 녹음을 끝내고 조각을 다 올린다
        assertTrue(api.lyricsRequests.isEmpty()) // 가사는 서버가 대화로 쓴다
        val request = api.jobRequests.single()
        assertTrue(request.conversation)
        assertNull(request.lyrics)
        assertEquals("trip_1", request.enrichedTrip?.tripId)
        assertEquals(listOf("서울 중구", "평창군", "강릉시"), request.enrichedTrip?.placeNames)
        assertTrue(request.clipPlan.isEmpty()) // 이번 주행에 녹음한 클립이 없으면 서버가 대화 속 한마디로 채운다

        advanceTimeBy(5_001)
        assertEquals(SongPhase.READY, state.phase)
        assertEquals(FakeSongApi.CONVERSATION_LYRICS, state.song?.lyrics)
        assertEquals(listOf("엄마", "첫째"), state.song?.singers)
        assertTrue(conversation.discarded.isEmpty())
    }

    @Test
    fun `a clip recorded during this trip still goes into a conversation song`() = test {
        conversation.uploaded = true
        val recordedNow = start.plusSeconds(60).toEpochMilli()
        db.voiceDao().insertClip(ClipEntity(memberId = "mem_mom", slot = ClipSlot.OUTRO, localPath = "/n", serverId = "clip_new",
            uploadState = UploadState.UPLOADED, recordedAtMs = recordedNow))

        driveUntilEtaTrigger()
        assertEquals(listOf(ClipPlanItem(ClipSlot.OUTRO, "clip_new")), api.jobRequests.single().clipPlan)
    }

    @Test
    fun `server status of a conversation job is shown while waiting`() = test {
        conversation.uploaded = true
        api.jobStatus = { _, poll ->
            when (poll) {
                1 -> SongJobStatus.ANALYZING
                2 -> SongJobStatus.CONVERTING
                else -> SongJobStatus.READY
            }
        }

        driveUntilEtaTrigger()
        assertEquals(SongJobStatus.QUEUED, state.serverStatus)
        advanceTimeBy(5_001)
        assertEquals(SongJobStatus.ANALYZING, state.serverStatus)
        advanceTimeBy(5_000)
        assertEquals(SongJobStatus.CONVERTING, state.serverStatus)
        advanceTimeBy(5_000)
        assertEquals(SongPhase.READY, state.phase)
    }

    @Test
    fun `a conversation job may take longer than five minutes`() = test {
        conversation.uploaded = true
        api.jobStatus = { _, _ -> SongJobStatus.CONVERTING }

        driveUntilEtaTrigger()
        advanceTimeBy(SongOrchestrator.SONG_TIMEOUT_MS + 5_001)
        assertEquals(1, api.jobRequests.size)
        assertEquals(SongPhase.WAITING_FOR_SONG, state.phase)
        advanceTimeBy(SongOrchestrator.CONVERSATION_SONG_TIMEOUT_MS - SongOrchestrator.SONG_TIMEOUT_MS)
        assertEquals(2, api.jobRequests.size) // 10분에 타임아웃 → 재시도 (서버는 주행 정보 + 보관한 목소리로)
        assertTrue(api.jobRequests[1].conversation)
    }

    @Test
    fun `a failed song discards the trip's conversation`() = test {
        conversation.uploaded = true
        api.jobStatus = { _, _ -> SongJobStatus.FAILED }

        driveUntilEtaTrigger()
        advanceTimeBy(10_001)
        assertEquals(SongPhase.FAILED, state.phase)
        assertEquals(listOf("trip_1"), conversation.discarded)
    }

    @Test
    fun `without an uploaded conversation the trip-only flow is unchanged`() = test {
        driveUntilEtaTrigger()
        assertEquals(listOf("trip_1"), conversation.finished)
        val request = api.jobRequests.single()
        assertFalse(request.conversation)
        assertEquals(FakeSongApi.LYRICS, request.lyrics)
        assertNull(request.enrichedTrip)
        advanceTimeBy(5_001)
        assertEquals(emptyList<String>(), state.song?.singers)
    }

    @Test
    fun `a trip that ends while another song is being made has its conversation discarded`() = test {
        driveUntilEtaTrigger()
        send(TripEvent.Started("trip_2", now()))
        send(TripEvent.Ended("trip_2", TripEndReason.PARKED, now(), demoSummary("trip_2", passengers)))
        assertEquals(listOf("trip_2"), conversation.discarded)
        assertEquals(listOf("trip_1"), conversation.finished)
    }
}
