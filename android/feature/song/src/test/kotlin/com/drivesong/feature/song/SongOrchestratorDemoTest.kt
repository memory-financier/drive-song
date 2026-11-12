package com.drivesong.feature.song

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.trip.TripRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.network.dto.SongJobStatus
import com.drivesong.core.vehicle.demo.FakeLocationDataSource
import com.drivesong.core.vehicle.demo.FakeNavigationDataSource
import com.drivesong.core.vehicle.demo.FakeVehicleDataSource
import com.drivesong.core.vehicle.demo.Scenario
import com.drivesong.core.vehicle.demo.ScenarioClock
import com.drivesong.core.vehicle.demo.ScenarioPlayer
import com.drivesong.feature.lyrics.RemoteLyricsProvider
import com.drivesong.feature.trip.TripRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * 완료 기준 §11-T10 을 앱과 같은 구성으로: 실제 ScenarioPlayer(기본 배속) + Fake 소스 → TripRecorder → SongOrchestrator.
 * 서버 처리 시간을 넣어도 도착 전에 READY 가 되어 도착 판정 때 자동 재생 이벤트가 나와야 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SongOrchestratorDemoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDatabase()
    private val zone = ZoneId.of("Asia/Seoul")

    @After
    fun tearDown() = db.close()

    @Test
    fun `seoul to gangneung demo - song is READY before arrival and auto-plays on arrival`() = runTest {
        val scenario = Scenario.parse(requireNotNull(javaClass.getResource("/scenarios/seoul_gangneung.json")).readText())
        val player = ScenarioPlayer()
        val clock = ScenarioClock(player, zone)
        val settings = FakeSettings()
        // 가짜 서버: enrich·가사 각 1.5초, 잡은 두 번째 폴링(제출 10초 뒤)에 READY
        val api = FakeSongApi().apply {
            callDelayMs = 1_500
            jobStatus = { _, poll -> if (poll < 2) SongJobStatus.GENERATING else SongJobStatus.READY }
        }
        val recorder = TripRecorder(
            FakeVehicleDataSource(player),
            FakeNavigationDataSource(player),
            FakeLocationDataSource(player),
            TripRepository(db.tripDao()),
            settings,
            clock,
        )
        val notifier = FakeNotifier()
        val orchestrator = SongOrchestrator(
            tripEvents = recorder,
            lyricsProvider = RemoteLyricsProvider(api),
            api = api,
            voice = VoiceRepository(db.voiceDao(), api, settings, context),
            songs = SongRepository(db.songDao(), api, context),
            settings = settings,
            notifier = notifier,
            clock = clock,
            conversation = FakeConversation(),
        )
        val autoPlayed = mutableListOf<SongEntity>()
        backgroundScope.launch { orchestrator.autoPlay.collect { autoPlayed += it } }
        backgroundScope.launch { orchestrator.run() }
        backgroundScope.launch { recorder.run() }
        runCurrent()

        player.play(scenario, scenario.startInstant(LocalDate.of(2026, 11, 1), zone))
        runCurrent()

        assertEquals(1, api.jobRequests.size)
        assertEquals(1, autoPlayed.size)
        assertEquals(autoPlayed.single(), orchestrator.state.value.song)
        assertTrue("도착 전에 완성됐으므로 완료 알림 없음", notifier.ready.isEmpty())
        assertEquals(0, notifier.failed)
    }
}
