package com.drivesong.feature.trip

import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.trip.TripRepository
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.vehicle.LocationDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/** 시작·종료 판정, 위치 요청 규칙, 1분 속도 집계 (설계 §6.2). 가짜 소스 + 코루틴 가상 시간 + Room 인메모리. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripRecorderRulesTest {

    private val db: DriveSongDatabase = inMemoryDatabase()
    private val dao = db.tripDao()
    private val vehicle = FakeVehicle()
    private val navigation = FakeNavigation()
    private val location = FakeLocation()
    private val start: Instant = Instant.parse("2026-11-01T00:30:00Z") // 09:30 KST, 분 경계

    @After
    fun closeDb() = db.close()

    private class Running(val recorder: TripRecorder, val clock: VirtualClock, val events: List<TripEvent>, val job: Job)

    private val settings = FakeSettings(setOf("mem_b", "mem_a"))

    private fun TestScope.startRecorder(): Running {
        val clock = VirtualClock(testScheduler, start)
        val recorder = TripRecorder(vehicle, navigation, location, TripRepository(dao), settings, clock)
        val events = mutableListOf<TripEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.events.collect { events += it } }
        val job = backgroundScope.launch { recorder.run() }
        runCurrent()
        return Running(recorder, clock, events, job)
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    private fun List<TripEvent>.ended() = filterIsInstance<TripEvent.Ended>()

    @Test
    fun `P for 5 minutes ends the trip at the moment of parking`() = runTest {
        val r = startRecorder()
        assertEquals(TripState.Idle, r.recorder.state.value)
        assertTrue("위치는 주행 중에만 요청", location.requests.isEmpty())

        vehicle.gearState.value = Gear.DRIVE
        vehicle.speed.value = 60f
        runCurrent()
        val started = r.events.first() as TripEvent.Started
        assertEquals(start, started.startedAt)
        assertTrue(r.recorder.state.value is TripState.Recording)
        assertEquals("30초 / 500m 규칙으로 요청", listOf(30_000L to 500f), location.requests)

        advance(Duration.ofMinutes(10))
        location.position.value = GeoPoint(37.60, 127.05)
        vehicle.speed.value = 0f
        vehicle.gearState.value = Gear.PARK
        runCurrent()
        val parkedAt = r.clock.instant()
        assertEquals(TripEvent.Arrived(started.tripId, parkedAt), r.events.last())

        // 진행 중 요약 (ETA 트리거용)
        val inProgress = requireNotNull(r.recorder.currentSummary())
        assertNull(inProgress.endedAt)
        assertEquals(10, inProgress.durationMin)
        assertEquals(listOf("mem_a", "mem_b"), inProgress.passengers)

        advance(Duration.ofMinutes(4).plusSeconds(50))
        assertTrue("5분 전에는 끝나지 않음", r.events.ended().isEmpty())

        advance(Duration.ofSeconds(40)) // 다음 틱(30초 주기)에서 판정
        val ended = r.events.ended().single()
        assertEquals(TripEndReason.PARKED, ended.reason)
        assertEquals(parkedAt, ended.endedAt) // 종료 시각 = P단 전환 시각
        assertEquals(TripState.Idle, r.recorder.state.value)

        val summary = requireNotNull(ended.summary)
        assertEquals(parkedAt, summary.endedAt)
        assertEquals(10, summary.durationMin)
        assertEquals(60.0, summary.maxSpeedKmh, 0.0)
        assertEquals(GeoPoint(37.5665, 126.9780), summary.origin)
        val row = requireNotNull(dao.trip(started.tripId))
        assertEquals("PARKED", row.endReason)
        assertEquals(parkedAt.toEpochMilli(), row.endedAtMs)
        assertEquals(summary, TripRepository(dao).summary(started.tripId))
    }

    @Test
    fun `brief P stop under 5 minutes does not end the trip`() = runTest {
        val r = startRecorder()
        vehicle.gearState.value = Gear.DRIVE
        runCurrent()
        advance(Duration.ofMinutes(5))
        vehicle.gearState.value = Gear.PARK // 휴게소
        runCurrent()
        advance(Duration.ofMinutes(4).plusSeconds(59))
        vehicle.gearState.value = Gear.DRIVE
        runCurrent()
        advance(Duration.ofMinutes(30))

        assertTrue(r.events.ended().isEmpty())
        assertTrue(r.recorder.state.value is TripState.Recording)
        assertEquals(1, r.events.filterIsInstance<TripEvent.Arrived>().size) // P단 전환은 알린다
        assertEquals(1, r.events.filterIsInstance<TripEvent.Started>().size)

        // 다시 P단 + 5분 → 종료 (P단 타이머는 휴게소 정차부터가 아니라 새 P단 전환부터)
        vehicle.gearState.value = Gear.PARK
        runCurrent()
        val parkedAt = r.clock.instant()
        advance(Duration.ofMinutes(5).plusSeconds(30))
        assertEquals(parkedAt, r.events.ended().single().endedAt)
    }

    @Test
    fun `navigation guidance ending ends the trip and the next trip waits for P`() = runTest {
        val destination = GeoPoint(37.80, 128.90)
        val eta = start.plus(Duration.ofHours(3))
        navigation.route.value = RouteInfo(destination, "강릉 경포해변", eta, 210.0)
        val r = startRecorder()

        vehicle.gearState.value = Gear.DRIVE
        vehicle.speed.value = 80f
        runCurrent()
        val started = r.events[0] as TripEvent.Started
        assertEquals(TripEvent.EtaUpdated(started.tripId, navigation.route.value!!, start), r.events[1])
        assertEquals(navigation.route.value, (r.recorder.state.value as TripState.Recording).route)

        advance(Duration.ofMinutes(20))
        navigation.route.value = navigation.route.value!!.copy(remainingDistanceKm = 180.0)
        runCurrent()
        assertTrue(r.events.last() is TripEvent.EtaUpdated)

        navigation.route.value = null // 안내 종료 (아직 D단)
        runCurrent()
        val ended = r.events.ended().single()
        assertEquals(TripEndReason.NAVIGATION_ENDED, ended.reason)
        assertEquals(start.plus(Duration.ofMinutes(20)), ended.endedAt)
        assertEquals("강릉 경포해변", ended.summary?.destinationName)
        assertEquals(eta, ended.summary?.etaAt)
        assertEquals(destination, ended.summary?.destination)

        advance(Duration.ofMinutes(2)) // 계속 달려도 새 주행을 시작하지 않는다
        assertEquals(1, r.events.filterIsInstance<TripEvent.Started>().size)

        vehicle.speed.value = 0f
        vehicle.gearState.value = Gear.PARK
        runCurrent()
        assertEquals(TripEvent.Arrived(started.tripId, r.clock.instant()), r.events.last()) // 끝난 주행의 도착

        vehicle.gearState.value = Gear.DRIVE
        runCurrent()
        val next = r.events.last() as TripEvent.Started
        assertTrue(next.tripId != started.tripId)
    }

    @Test
    fun `speed is stored only as 1-minute max and avg`() = runTest {
        val r = startRecorder()
        vehicle.speed.value = 50f // 움직이면 기어 정보 없이도 출발
        runCurrent()
        val tripId = r.events.filterIsInstance<TripEvent.Started>().single().tripId
        advance(Duration.ofSeconds(10))
        vehicle.speed.value = 100f
        runCurrent()
        advance(Duration.ofSeconds(10))
        vehicle.speed.value = 30f
        runCurrent()
        advance(Duration.ofSeconds(50)) // 1분 경과 (60초 틱에서 다음 분을 직전 속도 30 으로 시작)
        vehicle.speed.value = 80f
        runCurrent()
        advance(Duration.ofSeconds(10))

        r.job.cancel() // 서비스 정지 → STOPPED 로 종료·저장
        runCurrent()

        val minutes = dao.speedMinutes(tripId)
        assertEquals(2, minutes.size)
        assertEquals(start.toEpochMilli(), minutes[0].minuteStartMs)
        assertEquals(100.0, minutes[0].maxKmh, 0.0)
        assertEquals(60.0, minutes[0].avgKmh, 1e-9) // (50 + 100 + 30) / 3
        assertEquals(3, minutes[0].samples)
        assertEquals(80.0, minutes[1].maxKmh, 0.0)
        assertEquals(55.0, minutes[1].avgKmh, 1e-9) // (30 + 80) / 2
        val ended = r.events.ended().single()
        assertEquals(TripEndReason.STOPPED, ended.reason)
        assertEquals(100.0, ended.summary!!.maxSpeedKmh, 0.0)
        assertEquals("STOPPED", dao.trip(tripId)?.endReason)
        assertEquals(TripState.Off, r.recorder.state.value)
    }

    @Test
    fun `trip without any location fix ends without summary`() = runTest {
        val noFix = object : LocationDataSource {
            override fun locations(intervalMs: Long, minDistanceM: Float) = emptyFlow<GeoPoint>()
        }
        val clock = VirtualClock(testScheduler, start)
        val recorder = TripRecorder(vehicle, navigation, noFix, TripRepository(dao), FakeSettings(), clock)
        val events = mutableListOf<TripEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.events.collect { events += it } }
        val job = backgroundScope.launch { recorder.run() }
        vehicle.gearState.value = Gear.DRIVE
        runCurrent()
        assertNotNull(events.filterIsInstance<TripEvent.Started>().singleOrNull())
        job.cancel()
        runCurrent()
        assertNull(events.ended().single().summary)
    }

    @Test
    fun `navigation is subscribed only while recording so idle does not keep GPS on`() = runTest {
        val r = startRecorder()
        assertEquals("대기 중에는 경로(프리셋 → GPS)를 구독하지 않는다", 0, navigation.route.subscriptionCount.value)

        vehicle.gearState.value = Gear.DRIVE
        runCurrent()
        assertEquals(1, navigation.route.subscriptionCount.value)

        vehicle.gearState.value = Gear.PARK
        runCurrent()
        advance(Duration.ofMinutes(5).plusSeconds(30))
        assertEquals(TripEndReason.PARKED, r.events.ended().single().reason)
        assertEquals(0, navigation.route.subscriptionCount.value)
    }

    @Test
    fun `destination preset is cleared when a trip ends parked but not when guidance ends`() = runTest {
        settings.destinationPresetId.value = "gyeongpo"
        navigation.route.value = RouteInfo(GeoPoint(37.80, 128.90), "강릉 경포해변", start.plus(Duration.ofHours(3)), 210.0)
        val r = startRecorder()
        vehicle.gearState.value = Gear.DRIVE
        runCurrent()
        navigation.route.value = null // 안내 종료 → 프리셋은 그대로
        runCurrent()
        assertEquals(TripEndReason.NAVIGATION_ENDED, r.events.ended().single().reason)
        assertEquals("gyeongpo", settings.destinationPresetId.value)

        vehicle.gearState.value = Gear.PARK
        runCurrent()
        vehicle.gearState.value = Gear.DRIVE // 다음 주행 → P단 5분으로 끝남
        runCurrent()
        vehicle.gearState.value = Gear.PARK
        runCurrent()
        advance(Duration.ofMinutes(5).plusSeconds(30))
        assertEquals(TripEndReason.PARKED, r.events.ended().last().reason)
        assertEquals(null, settings.destinationPresetId.value)
    }

    @Test
    fun `raw points of trips older than 30 days are purged when recording starts`() = runTest {
        val repository = TripRepository(dao)
        val old = start.minus(Duration.ofDays(31))
        repository.startTrip("trip_old", old, emptyList())
        repository.addPoint("trip_old", old, GeoPoint(37.5, 127.0))
        repository.finishTrip("trip_old", old.plusSeconds(600), TripEndReason.PARKED, old.plusSeconds(600), SEOUL)
        val recent = start.minus(Duration.ofDays(29))
        repository.startTrip("trip_recent", recent, emptyList())
        repository.addPoint("trip_recent", recent, GeoPoint(37.5, 127.0))
        repository.finishTrip("trip_recent", recent.plusSeconds(600), TripEndReason.PARKED, recent.plusSeconds(600), SEOUL)

        startRecorder()

        assertTrue(dao.points("trip_old").isEmpty())
        assertNotNull("요약은 남는다", repository.summary("trip_old"))
        assertEquals(1, dao.points("trip_recent").size)
    }
}
