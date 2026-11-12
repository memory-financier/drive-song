package com.drivesong.feature.trip

import com.drivesong.core.data.trip.TripRepository
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.TimeOfDay
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.model.haversineKm
import com.drivesong.core.vehicle.demo.FakeLocationDataSource
import com.drivesong.core.vehicle.demo.FakeNavigationDataSource
import com.drivesong.core.vehicle.demo.FakeVehicleDataSource
import com.drivesong.core.vehicle.demo.Scenario
import com.drivesong.core.vehicle.demo.ScenarioClock
import com.drivesong.core.vehicle.demo.ScenarioPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.LocalDate

/**
 * 완료 기준 §11-T8: 데모 모드 주행 후 DB 에 요약 저장.
 * 앱과 같은 구성(ScenarioPlayer 200배속 + Fake 소스 + ScenarioClock)으로 서울→강릉을 코루틴 가상 시간에 재생한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripRecorderDemoTest {

    private val db = inMemoryDatabase()
    private val dao = db.tripDao()

    @After
    fun closeDb() = db.close()

    @Test
    fun `seoul to gangneung demo trip is recorded and its summary persisted`() = runTest {
        val scenario = Scenario.parse(requireNotNull(javaClass.getResource("/scenarios/seoul_gangneung.json")).readText())
        val player = ScenarioPlayer()
        val clock = ScenarioClock(player, SEOUL)
        val repository = TripRepository(dao)
        val recorder = TripRecorder(
            FakeVehicleDataSource(player),
            FakeNavigationDataSource(player),
            FakeLocationDataSource(player),
            repository,
            FakeSettings(setOf("mem_mom", "mem_kid")),
            clock,
        )
        val events = mutableListOf<TripEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.events.collect { events += it } }
        backgroundScope.launch { recorder.run() }
        runCurrent()

        val startAt = scenario.startInstant(LocalDate.of(2026, 11, 1), SEOUL)
        player.play(scenario, startAt)
        runCurrent()
        val wallClockMs = currentTime

        val started = events.first() as TripEvent.Started
        val ended = events.filterIsInstance<TripEvent.Ended>().single()
        val arrivedAt = startAt.plusSeconds(scenario.arrivalSec)
        assertEquals(started.tripId, ended.tripId)
        assertEquals(TripEndReason.NAVIGATION_ENDED, ended.reason) // 도착하면 Fake 내비 안내 종료
        assertTrue(events.any { it is TripEvent.Arrived && it.tripId == started.tripId })
        assertTrue(events.any { it is TripEvent.EtaUpdated })
        assertEquals(TripState.Idle, recorder.state.value)
        assertTrue("출발 = D단 전환(10초) 직후 프레임", Duration.between(startAt, started.startedAt) <= Duration.ofSeconds(30))
        assertTrue("도착 후 첫 프레임에서 종료", Duration.between(arrivedAt, ended.endedAt) in Duration.ZERO..Duration.ofSeconds(20))

        // DB 에 저장된 요약
        val summary = requireNotNull(repository.summary(started.tripId))
        assertEquals(ended.summary, summary)
        val row = requireNotNull(dao.trip(started.tripId))
        assertEquals(ended.endedAt.toEpochMilli(), row.endedAtMs)
        assertEquals("NAVIGATION_ENDED", row.endReason)
        println("T8 demo summary: $summary (wall clock ${wallClockMs}ms)")
        assertTrue("distance ${summary.distanceKm}", summary.distanceKm in 200.0..220.0)
        assertTrue("duration ${summary.durationMin}", summary.durationMin in 178..182)
        assertTrue("avg ${summary.avgSpeedKmh}", summary.avgSpeedKmh in 60.0..90.0)
        assertTrue("max ${summary.maxSpeedKmh}", summary.maxSpeedKmh in 95.0..110.0)
        assertEquals(TimeOfDay.MORNING, summary.timeOfDay)
        assertEquals("강릉 경포해변", summary.destinationName)
        assertEquals(scenario.destination, summary.destination)
        assertEquals(arrivedAt, summary.etaAt)
        assertEquals(listOf("mem_kid", "mem_mom"), summary.passengers)
        assertTrue(summary.waypoints.size in 10..50)
        assertTrue(haversineKm(summary.origin, scenario.points.first().point) < 0.5)

        // 위치: 30초 경과 또는 500m 이동마다 (Fake 프레임 = 시나리오 30초, 도착 전 10분은 3초)
        val points = dao.points(started.tripId)
        println("T8 demo: ${points.size} points, ${dao.speedMinutes(started.tripId).size} speed minutes")
        points.zipWithNext().forEach { (a, b) ->
            val dtMs = b.recordedAtMs - a.recordedAtMs
            val dM = haversineKm(GeoPoint(a.lat, a.lng), GeoPoint(b.lat, b.lng)) * 1000
            assertTrue("dt=$dtMs dM=$dM", dtMs >= 30_000 || dM >= 500)
            assertTrue("dt=$dtMs", dtMs <= 40_000)
        }

        // 속도: 1분 단위 집계만 (약 180분 → 약 180행)
        val minutes = dao.speedMinutes(started.tripId)
        assertTrue("${minutes.size} minutes", minutes.size in 178..183)
        assertEquals(minutes.size, minutes.map { it.minuteStartMs }.distinct().size)
        assertEquals(summary.maxSpeedKmh, minutes.maxOf { it.maxKmh }, 0.05)

        assertTrue("재생 ${wallClockMs}ms", wallClockMs <= 60_000)
    }

    /**
     * 데모를 중간에 멈추고 다른 시나리오를 재생하면 (DemoDriveController: park → 잠시 뒤 다음 재생)
     * 앞 주행은 멈춘 시나리오 시각에 끝나고, 다음 시나리오는 새 주행이 된다.
     */
    @Test
    fun `stopping a demo mid-drive ends the trip and the next scenario is a separate trip`() = runTest {
        val seoul = Scenario.parse(requireNotNull(javaClass.getResource("/scenarios/seoul_gangneung.json")).readText())
        val mart = Scenario.parse(requireNotNull(javaClass.getResource("/scenarios/neighborhood_mart.json")).readText())
        val player = ScenarioPlayer()
        val recorder = TripRecorder(
            FakeVehicleDataSource(player),
            FakeNavigationDataSource(player),
            FakeLocationDataSource(player),
            TripRepository(dao),
            FakeSettings(),
            ScenarioClock(player, SEOUL),
        )
        val events = mutableListOf<TripEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.events.collect { events += it } }
        backgroundScope.launch { recorder.run() }
        runCurrent()

        val day = LocalDate.of(2026, 11, 1)
        val playing = backgroundScope.launch { player.play(seoul, seoul.startInstant(day, SEOUL)) }
        advanceTimeBy(25_000)
        playing.cancel()
        val stoppedAt = requireNotNull(player.frame.value).at
        player.park()
        advanceTimeBy(1_000) // DemoDriveController.PARK_HOLD_MS
        runCurrent()
        player.play(mart, mart.startInstant(day, SEOUL))
        runCurrent()

        val started = events.filterIsInstance<TripEvent.Started>()
        val ended = events.filterIsInstance<TripEvent.Ended>()
        assertEquals(2, started.size)
        assertEquals(started.map { it.tripId }, ended.map { it.tripId })
        assertEquals(stoppedAt, ended[0].endedAt) // 멈춘 시나리오 시각 (벽시계·다음 시나리오 시각 아님)
        assertEquals("강릉 경포해변", ended[0].summary!!.destinationName)
        assertTrue(events.filterIsInstance<TripEvent.EtaUpdated>().filter { it.tripId == started[0].tripId }
            .all { it.route.destinationName == "강릉 경포해변" })
        assertEquals("동네 마트", ended[1].summary!!.destinationName)
        assertTrue(ended[1].summary!!.durationMin < 30)
    }
}
