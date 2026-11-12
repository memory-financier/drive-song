package com.drivesong.feature.trip

import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.trip.TripRepository
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.model.TripSummary
import com.drivesong.core.vehicle.LocationDataSource
import com.drivesong.core.vehicle.NavigationDataSource
import com.drivesong.core.vehicle.VehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 주행 기록 엔진 (설계 §6.2). [TripRecorderService] 가 켜져 있는 동안 [run] 한다.
 *
 * - 출발: P단이 아닌 기어(D/R/N) 또는 [MOVING_KMH] 이상 속도.
 * - 종료: P단 [PARKED_END_AFTER] 이상 유지 (종료 시각 = P단 전환 시각), 또는 경로 안내 종료 (경로 non-null → null).
 *   안내 종료로 끝난 뒤에는 P단을 한 번 거쳐야 다음 주행을 시작한다 (안내를 끈 채 계속 달려도 주행이 쪼개지지 않게).
 * - 위치·경로: 주행 중에만 구독한다 (대기 중 GPS 를 켜 두지 않게). 위치는 30초 / 500m (먼저 오는 쪽, 규칙은
 *   [LocationDataSource]) 로 받아 그대로 저장.
 * - 주행이 P단으로 끝나거나(PARKED) 기록을 끄면(STOPPED) 설정의 목적지 프리셋(§6.1 대체 경로)을 지운다.
 *   프리셋은 한 번의 주행용이다 (다음 주행에 지난 목적지가 붙지 않게).
 * - 기록을 시작할 때와 주행이 끝날 때 [COORDINATE_RETENTION] 이 지난 주행의 원본 좌표를 지운다 (설계 §8.1).
 * - 속도: 1분 단위 최고/평균만 저장. 평균 = 그 분에 받은 값들의 산술 평균 (분이 바뀌면 직전 속도로 시작).
 * - 모든 시각은 주입된 [Clock] (데모 재생 중에는 시나리오 시각). 입력은 한 코루틴에서 순서대로 처리한다.
 */
@Singleton
class TripRecorder @Inject constructor(
    private val vehicle: VehicleDataSource,
    private val navigation: NavigationDataSource,
    private val location: LocationDataSource,
    private val repository: TripRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : TripEvents {

    private val _state = MutableStateFlow<TripState>(TripState.Off)
    override val state: StateFlow<TripState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TripEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TripEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    private var gear = Gear.UNKNOWN
    private var speedKmh = 0f
    private var route: RouteInfo? = null
    private var trip: ActiveTrip? = null

    /** 안내 종료로 끝났는데 아직 P단을 못 본 주행 id. null 이 아니면 새 주행을 시작하지 않는다. */
    private var awaitingParkFor: String? = null

    /** 취소될 때까지 기록한다. 취소되면 진행 중인 주행을 [TripEndReason.STOPPED] 로 끝내 저장한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        check(_state.value == TripState.Off) { "already running" }
        _state.value = TripState.Idle
        try {
            purgeOldCoordinates()
            val recordingId = _state.map { (it as? TripState.Recording)?.tripId }.distinctUntilChanged()
            val locations = recordingId.flatMapLatest { id ->
                if (id == null) emptyFlow() else location.locations(LOCATION_INTERVAL_MS, LOCATION_MIN_DISTANCE_M).map { Input.Location(id, it) }
            }
            val routes = recordingId.flatMapLatest { id ->
                if (id == null) emptyFlow() else navigation.activeRoute.map { Input.Route(id, it) }
            }
            merge(
                vehicle.gear.map(Input::GearChanged),
                vehicle.speedKmh.map(Input::Speed),
                routes,
                locations,
                flow { while (true) { delay(TICK_MS); emit(Input.Tick) } }, // 정차 중 입력이 없어도 종료 판정
            ).collect { input -> mutex.withLock { handle(input) } }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    trip?.let { end(it, clock.instant(), TripEndReason.STOPPED) }
                    gear = Gear.UNKNOWN
                    speedKmh = 0f
                    route = null
                    awaitingParkFor = null
                    _state.value = TripState.Off
                }
            }
        }
    }

    override suspend fun currentSummary(): TripSummary? = mutex.withLock {
        val active = trip ?: return null
        active.minute?.let { save(active.id, it) }
        repository.buildSummary(active.id, endedAt = null, now = clock.instant(), zone = clock.zone)
    }

    private suspend fun handle(input: Input) {
        val now = clock.instant()
        trip?.let { rollMinute(it, now) }
        when (input) {
            is Input.GearChanged -> onGear(input.gear, now)
            is Input.Speed -> {
                speedKmh = input.kmh
                trip?.minute?.add(input.kmh)
            }
            is Input.Route -> if (trip?.id == input.tripId) onRoute(input.route, now)
            is Input.Location -> if (trip?.id == input.tripId) repository.addPoint(input.tripId, now, input.point)
            Input.Tick -> Unit
        }
        val active = trip
        if (active == null) {
            if (awaitingParkFor == null && (gear in MOVING_GEARS || speedKmh >= MOVING_KMH)) start(now)
        } else {
            val parkedSince = active.parkedSince
            if (parkedSince != null && Duration.between(parkedSince, now) >= PARKED_END_AFTER) {
                end(active, parkedSince, TripEndReason.PARKED)
            }
        }
    }

    private suspend fun onGear(newGear: Gear, now: Instant) {
        val previous = gear
        gear = newGear
        val active = trip
        if (newGear == Gear.PARK && previous != Gear.PARK) {
            if (active != null) {
                active.parkedSince = now
                _events.emit(TripEvent.Arrived(active.id, now))
            } else {
                awaitingParkFor?.let { _events.emit(TripEvent.Arrived(it, now)) }
                awaitingParkFor = null
            }
        } else if (newGear in MOVING_GEARS) {
            active?.parkedSince = null // P단에서 다시 출발: 짧은 정차는 주행을 끝내지 않는다
        }
    }

    private suspend fun onRoute(newRoute: RouteInfo?, now: Instant) {
        val previous = route
        route = newRoute
        val active = trip ?: return
        if (newRoute != null) {
            if (previous?.destination != newRoute.destination ||
                previous.destinationName != newRoute.destinationName ||
                previous.etaAt != newRoute.etaAt
            ) {
                repository.updateRoute(active.id, newRoute) // 남은 거리만 바뀐 경우는 저장하지 않는다
            }
            _state.value = TripState.Recording(active.id, active.startedAt, newRoute)
            _events.emit(TripEvent.EtaUpdated(active.id, newRoute, now))
        } else if (previous != null) {
            end(active, now, TripEndReason.NAVIGATION_ENDED)
            if (gear != Gear.PARK) awaitingParkFor = active.id
        }
    }

    /** 경로는 주행이 시작된 뒤 구독하므로 첫 경로는 [onRoute] 로 들어온다. */
    private suspend fun start(now: Instant) {
        val id = newTripId(now)
        repository.startTrip(id, now, settings.selectedPassengerIds.first().sorted())
        val active = ActiveTrip(id, now).apply { minute = SpeedMinute(minuteOf(now)).also { it.add(speedKmh) } }
        trip = active
        _state.value = TripState.Recording(id, now, null)
        _events.emit(TripEvent.Started(id, now))
    }

    private suspend fun end(active: ActiveTrip, endedAt: Instant, reason: TripEndReason) {
        active.minute?.let { save(active.id, it) }
        val summary = repository.finishTrip(active.id, endedAt, reason, now = clock.instant(), zone = clock.zone)
        trip = null
        route = null // 경로 구독은 주행 중에만 (다음 주행은 새로 받은 경로로 시작)
        _state.value = TripState.Idle
        _events.emit(TripEvent.Ended(active.id, reason, endedAt, summary))
        // 안내 종료는 프리셋을 지운 결과일 수 있으므로 제외. 데모 주행은 실제 주행용 프리셋을 건드리지 않는다.
        if (reason != TripEndReason.NAVIGATION_ENDED && !settings.demoMode.first()) settings.setDestinationPresetId(null)
        purgeOldCoordinates()
    }

    private suspend fun purgeOldCoordinates() = repository.purgeCoordinates(before = clock.instant().minus(COORDINATE_RETENTION))

    /** 분이 바뀌었으면 지난 분을 저장하고, 새 분을 직전 속도로 시작한다 (값이 그대로면 속도 이벤트가 오지 않으므로). */
    private suspend fun rollMinute(active: ActiveTrip, now: Instant) {
        val current = active.minute ?: return
        val minuteStart = minuteOf(now)
        if (current.start == minuteStart) return
        save(active.id, current)
        active.minute = SpeedMinute(minuteStart).also { it.add(speedKmh) }
    }

    private suspend fun save(tripId: String, m: SpeedMinute) =
        repository.saveSpeedMinute(tripId, m.start, m.max.toDouble(), m.sum / m.count, m.count)

    private fun minuteOf(t: Instant) = t.truncatedTo(ChronoUnit.MINUTES)

    /** 예: trip_20261101_093010_3fa2 (같은 데모를 같은 날 다시 재생해도 겹치지 않게 임의 접미사). */
    private fun newTripId(now: Instant): String =
        "trip_" + TRIP_ID_TIME.format(now.atZone(clock.zone)) + "_" + UUID.randomUUID().toString().take(4)

    private class ActiveTrip(val id: String, val startedAt: Instant) {
        var parkedSince: Instant? = null
        var minute: SpeedMinute? = null
    }

    private class SpeedMinute(val start: Instant) {
        var max = 0f
        var sum = 0.0
        var count = 0

        fun add(kmh: Float) {
            max = maxOf(max, kmh)
            sum += kmh
            count++
        }
    }

    private sealed interface Input {
        data class GearChanged(val gear: Gear) : Input
        data class Speed(val kmh: Float) : Input
        data class Route(val tripId: String, val route: RouteInfo?) : Input
        data class Location(val tripId: String, val point: GeoPoint) : Input
        data object Tick : Input
    }

    companion object {
        const val LOCATION_INTERVAL_MS = 30_000L
        const val LOCATION_MIN_DISTANCE_M = 500f
        const val MOVING_KMH = 5f
        val PARKED_END_AFTER: Duration = Duration.ofMinutes(5)

        /** 설계 §8.1: 노래 생성 후 30일 뒤 원본 좌표 삭제 (노래는 주행이 끝날 때 만든다). */
        val COORDINATE_RETENTION: Duration = Duration.ofDays(30)

        /** 정차 중 종료 판정 주기 (벽시계). 깨어나 비교 한 번 하는 것뿐이라 CPU 부담 없음. */
        const val TICK_MS = 30_000L

        private val MOVING_GEARS = setOf(Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL)
        private val TRIP_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
