package com.drivesong.core.vehicle.demo

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.TimeOfDay
import com.drivesong.core.model.TripSummaryBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 완료 기준 §11-T7: 데모 모드로 3시간 주행을 60초 안에 재생하며 TripSummary 생성.
 * 코루틴 가상 시간으로 돌리므로 테스트는 즉시 끝나고, [currentTime] 이 실제 재생에 걸릴 벽시계 시간이다.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime
class DemoReplayTest {

    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `seoul to gangneung replays within 60 s and yields a valid TripSummary`() = runTest {
        val scenario = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        val player = ScenarioPlayer() // 앱과 같은 기본값: 300배속(마지막 10분은 30배속), 100ms 틱
        val clock = ScenarioClock(player, zone)
        val vehicle = FakeVehicleDataSource(player)
        val navigation = FakeNavigationDataSource(player)
        val location = FakeLocationDataSource(player)

        // T8 TripRecorder 가 할 일을 단순하게 흉내: D단 = 출발, P단 = 도착, 위치는 30초/500m 규칙
        val track = mutableListOf<GeoPoint>()
        val speeds = mutableListOf<Double>()
        val routes = mutableListOf<RouteInfo>()
        var startedAt: Instant? = null
        var endedAt: Instant? = null
        backgroundScope.launch { location.locations(30_000, 500f).collect { track += it } }
        backgroundScope.launch { vehicle.speedKmh.collect { speeds += it.toDouble() } }
        backgroundScope.launch { navigation.activeRoute.filterNotNull().collect { routes += it } }
        backgroundScope.launch {
            vehicle.gear.collect { gear ->
                if (gear == Gear.DRIVE && startedAt == null) startedAt = clock.instant()
                if (gear == Gear.PARK && startedAt != null) endedAt = clock.instant()
            }
        }

        val startAt = scenario.startInstant(LocalDate.of(2026, 11, 1), zone)
        player.play(scenario, startAt)
        val wallClockMs = currentTime

        assertTrue(wallClockMs <= 60_000, "재생 시간 ${wallClockMs}ms")
        // 틱당 30초로 10,260초까지 342틱 + 틱당 3초로 도착(10,831초)까지 191틱 + 정차 358초 12틱 = 545틱
        assertEquals(54_500L, wallClockMs)
        assertEquals(startAt.plusSeconds(scenario.totalSec), clock.instant()) // 시계 = 시나리오 시각

        val started = requireNotNull(startedAt) { "never left P" }
        val ended = requireNotNull(endedAt) { "never parked" }
        // 도착 직전 프레임은 시나리오 3초 단위 → 도착 후 첫 프레임에서 P단 감지
        assertTrue(Duration.between(startAt.plusSeconds(scenario.arrivalSec), ended) in Duration.ZERO..Duration.ofSeconds(20))
        val firstRoute = routes.first()
        val summary = TripSummaryBuilder.build(
            tripId = "trip_demo",
            startedAt = started,
            endedAt = ended,
            now = clock.instant(),
            track = track,
            speedSamplesKmh = speeds,
            etaAt = firstRoute.etaAt,
            destination = firstRoute.destination,
            destinationName = firstRoute.destinationName,
            passengers = listOf("mem_1"),
            zone = zone,
        )

        println("demo summary: $summary (track ${track.size} points, wall clock ${wallClockMs}ms)")
        assertTrue(summary.distanceKm in 170.0..260.0, "distance ${summary.distanceKm}")
        assertTrue(summary.waypoints.size <= 50 && summary.waypoints.size >= 10, "waypoints ${summary.waypoints.size}")
        assertEquals(summary.origin, summary.waypoints.first())
        assertEquals(scenario.destination, summary.waypoints.last())
        assertEquals(ended, summary.endedAt)
        assertTrue(Duration.ofMinutes(summary.durationMin.toLong()) in Duration.ofMinutes(170)..Duration.ofMinutes(190))
        assertTrue(summary.avgSpeedKmh in 60.0..90.0, "avg ${summary.avgSpeedKmh}")
        assertTrue(summary.maxSpeedKmh in 95.0..110.0, "max ${summary.maxSpeedKmh}")
        assertEquals(TimeOfDay.MORNING, summary.timeOfDay) // 09:30 출발
        assertEquals("강릉 경포해변", summary.destinationName)
        assertEquals(startAt.plusSeconds(scenario.arrivalSec), summary.etaAt)
        assertTrue(routes.last().remainingDistanceKm!! < 10.0)
    }

    /** §11-T10: ETA ≤ 10분 트리거부터 도착(P단)까지 노래를 만들 벽시계 시간이 남아야 도착 시 자동 재생된다. */
    @Test
    fun `the last 10 minutes before arrival play slowly enough to finish the song`() = runTest {
        val scenario = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        val player = ScenarioPlayer()
        var triggerAtMs: Long? = null
        var parkedAtMs: Long? = null
        backgroundScope.launch {
            player.frame.filterNotNull().collect { f ->
                val eta = f.route?.etaAt
                if (triggerAtMs == null && eta != null && Duration.between(f.at, eta) <= Duration.ofMinutes(10)) triggerAtMs = currentTime
                if (parkedAtMs == null && triggerAtMs != null && f.gear == Gear.PARK) parkedAtMs = currentTime
            }
        }

        player.play(scenario, scenario.startInstant(LocalDate.of(2026, 11, 1), zone))

        val window = requireNotNull(parkedAtMs) - requireNotNull(triggerAtMs)
        // 가짜 서버 기준: 첫 폴링 5초(FAKE_MUSIC_DELAY 3초 + 믹싱), 늦어도 두 번째 폴링 10초 + 다운로드
        assertTrue(window >= 19_000, "ETA 트리거 → 도착 ${window}ms")
    }

    /** T19: 대화 담기 데모는 대화 분석·가족 목소리 변환(약 170초, 설계 §7.5)이 도착 전에 끝날 만큼 느리게. */
    @Test
    fun `with conversation capture the last 10 minutes leave time for the family voices`() = runTest {
        val scenario = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        val player = ScenarioPlayer()
        var triggerAtMs: Long? = null
        var parkedAtMs: Long? = null
        backgroundScope.launch {
            player.frame.filterNotNull().collect { f ->
                val eta = f.route?.etaAt
                if (triggerAtMs == null && eta != null && Duration.between(f.at, eta) <= Duration.ofMinutes(10)) triggerAtMs = currentTime
                if (parkedAtMs == null && triggerAtMs != null && f.gear == Gear.PARK) parkedAtMs = currentTime
            }
        }

        player.play(scenario, scenario.startInstant(LocalDate.of(2026, 11, 1), zone), ScenarioPlayer.CONVERSATION_FINALE_SPEED_UP)

        val window = requireNotNull(parkedAtMs) - requireNotNull(triggerAtMs)
        assertTrue(window in 440_000..480_000, "ETA 트리거 → 도착 ${window}ms")
        assertTrue(currentTime <= 540_000, "전체 재생 ${currentTime}ms") // 9분 안
    }

    @Test
    fun `neighborhood mart replay ends parked at the mart`() = runTest {
        val scenario = loadScenario(DemoScenario.NEIGHBORHOOD_MART)
        val player = ScenarioPlayer()
        val navigation = FakeNavigationDataSource(player)
        val routes = mutableListOf<RouteInfo?>()
        backgroundScope.launch { navigation.activeRoute.collect { routes += it } }

        player.play(scenario, scenario.startInstant(LocalDate.of(2026, 11, 1), zone))

        assertTrue(currentTime <= 20_000, "재생 시간 ${currentTime}ms") // 10분 안쪽 주행이라 전부 느린 구간
        val last = requireNotNull(player.frame.value)
        assertEquals(Gear.PARK, last.gear)
        assertEquals(scenario.destination, last.location)
        assertEquals(null, routes.last()) // 도착하면 안내 종료
    }
}
