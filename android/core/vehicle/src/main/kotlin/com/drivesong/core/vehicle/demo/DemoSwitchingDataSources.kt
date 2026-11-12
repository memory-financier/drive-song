package com.drivesong.core.vehicle.demo

import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.vehicle.LocationDataSource
import com.drivesong.core.vehicle.NavigationDataSource
import com.drivesong.core.vehicle.VehicleDataSource
import com.drivesong.core.vehicle.car.CarPropertyVehicleDataSource
import com.drivesong.core.vehicle.location.AndroidLocationDataSource
import com.drivesong.core.vehicle.navigation.PresetNavigationDataSource
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

/*
 * 데모 모드 런타임 전환 (설계 §6.4).
 * Hilt 그래프는 앱 시작 시 고정되므로, 앱에는 이 "전환" 구현이 주입된다. 각 Flow 는 데모 모드 설정을 구독해
 * 켜지면 Fake, 꺼지면 실제 구현으로 flatMapLatest 한다 → 설정을 바꾸면 구독 중인 쪽이 즉시 다른 소스로 넘어간다.
 * 실제/Fake 구현은 dagger.Lazy 로 받아 처음 필요할 때 만든다 (콜드 스타트 최소화, 설계 §2).
 */

@OptIn(ExperimentalCoroutinesApi::class)
private fun <S, T> Flow<Boolean>.switchSource(real: () -> S, fake: () -> S, select: (S) -> Flow<T>): Flow<T> =
    flatMapLatest { demo -> select(if (demo) fake() else real()) }

class DemoSwitchingVehicleDataSource internal constructor(
    demoMode: Flow<Boolean>,
    real: () -> VehicleDataSource,
    fake: () -> VehicleDataSource,
) : VehicleDataSource {
    @Inject constructor(
        settings: SettingsRepository,
        real: Lazy<CarPropertyVehicleDataSource>,
        fake: Lazy<FakeVehicleDataSource>,
    ) : this(settings.demoMode, real::get, fake::get)

    override val speedKmh: Flow<Float> = demoMode.switchSource(real, fake) { it.speedKmh }
    override val gear: Flow<Gear> = demoMode.switchSource(real, fake) { it.gear }
}

class DemoSwitchingNavigationDataSource @Inject constructor(
    settings: SettingsRepository,
    real: Lazy<PresetNavigationDataSource>,
    fake: Lazy<FakeNavigationDataSource>,
) : NavigationDataSource {
    override val activeRoute: Flow<RouteInfo?> = settings.demoMode.switchSource(real::get, fake::get) { it.activeRoute }
}

class DemoSwitchingLocationDataSource @Inject constructor(
    private val settings: SettingsRepository,
    private val real: Lazy<AndroidLocationDataSource>,
    private val fake: Lazy<FakeLocationDataSource>,
) : LocationDataSource {
    override fun locations(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint> =
        settings.demoMode.switchSource(real::get, fake::get) { it.locations(intervalMs, minDistanceM) }
}
