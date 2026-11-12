package com.drivesong.core.vehicle.navigation

import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.haversineKm
import com.drivesong.core.model.pathLengthKm
import com.drivesong.core.vehicle.NavigationDataSource
import com.drivesong.core.vehicle.TimedFix
import com.drivesong.core.vehicle.location.AndroidLocationDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * NaviHelper SDK 없이 쓰는 경로 정보 (설계 §6.1 대체 경로).
 * 설정의 목적지 프리셋 + 현재 위치 + 최근 평균 속도로 남은 거리·ETA 를 추정한다 ([estimateRoute]).
 * NaviHelper 구현은 SDK 확보 후 같은 인터페이스로 추가한다. [확인 필요] NaviHelper 반환 필드.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresetNavigationDataSource @Inject constructor(
    settings: SettingsRepository,
    private val location: AndroidLocationDataSource,
    private val clock: Clock,
) : NavigationDataSource {

    override val activeRoute: Flow<RouteInfo?> = settings.destinationPresetId.flatMapLatest { id ->
        val preset = DestinationPresets.byId(id) ?: return@flatMapLatest flowOf(null)
        location.locations(SAMPLE_INTERVAL_MS, SAMPLE_MIN_DISTANCE_M)
            .map { TimedFix(clock.millis(), it) }
            .scan(emptyList<TimedFix>()) { recent, fix -> (recent + fix).filter { fix.timeMs - it.timeMs <= SPEED_WINDOW_MS } }
            .map { estimateRoute(preset, it) }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 30_000L
        const val SAMPLE_MIN_DISTANCE_M = 500f
        const val SPEED_WINDOW_MS = 10 * 60_000L
    }
}

/** 직선 거리를 도로 거리로 보정하는 계수. 근거 없는 가정값 [확인 필요] — 실주행 비교 후 조정. */
internal const val ROAD_DETOUR_FACTOR = 1.3

/** 이보다 느리면 (정차·정체) ETA 를 추정하지 않는다. */
internal const val MIN_MOVING_KMH = 5.0

/**
 * @param recent 최근 위치 (시간순, 마지막 = 현재 위치). 비어 있으면 거리·ETA 모두 null.
 * 남은 거리 = 직선 거리 × [ROAD_DETOUR_FACTOR], ETA = 현재 + 남은 거리 / 최근 평균 속도(지나온 경로 길이 ÷ 시간).
 */
internal fun estimateRoute(preset: DestinationPreset, recent: List<TimedFix>): RouteInfo {
    val current = recent.lastOrNull() ?: return RouteInfo(preset.point, preset.name, etaAt = null, remainingDistanceKm = null)
    val remainingKm = haversineKm(current.point, preset.point) * ROAD_DETOUR_FACTOR
    val hours = (current.timeMs - recent.first().timeMs) / 3_600_000.0
    val avgKmh = if (hours > 0) pathLengthKm(recent.map { it.point }) / hours else 0.0
    val etaAt = if (avgKmh >= MIN_MOVING_KMH) {
        Instant.ofEpochMilli(current.timeMs + (remainingKm / avgKmh * 3_600_000).toLong())
    } else {
        null
    }
    return RouteInfo(preset.point, preset.name, etaAt, remainingKm)
}
