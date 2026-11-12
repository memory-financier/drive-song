package com.drivesong.core.vehicle.demo

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.vehicle.LocationDataSource
import com.drivesong.core.vehicle.NavigationDataSource
import com.drivesong.core.vehicle.TimedFix
import com.drivesong.core.vehicle.VehicleDataSource
import com.drivesong.core.vehicle.throttle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// 시나리오 재생기로 움직이는 Fake 구현 (설계 §6.4). 재생 전에는 아무 값도 내보내지 않는다.

class FakeVehicleDataSource @Inject constructor(player: ScenarioPlayer) : VehicleDataSource {
    private val frames = player.frame.filterNotNull()
    override val speedKmh: Flow<Float> = frames.map { it.speedKmh }.distinctUntilChanged()
    override val gear: Flow<Gear> = frames.map { it.gear }.distinctUntilChanged()
}

/** NaviHelper 처럼 정확한 남은 거리·ETA 를 준다. 도착하면 안내 종료(null). */
class FakeNavigationDataSource @Inject constructor(player: ScenarioPlayer) : NavigationDataSource {
    override val activeRoute: Flow<RouteInfo?> = player.frame.filterNotNull().map { it.route }.distinctUntilChanged()
}

/** 간격·거리 판정은 시나리오 시각 기준. */
class FakeLocationDataSource @Inject constructor(private val player: ScenarioPlayer) : LocationDataSource {
    override fun locations(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint> =
        player.frame.filterNotNull()
            .map { TimedFix(it.at.toEpochMilli(), it.location) }
            .throttle(intervalMs, minDistanceM)
}
