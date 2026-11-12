package com.drivesong.core.vehicle.di

import com.drivesong.core.vehicle.LocationDataSource
import com.drivesong.core.vehicle.NavigationDataSource
import com.drivesong.core.vehicle.VehicleDataSource
import com.drivesong.core.vehicle.demo.DemoSwitchingLocationDataSource
import com.drivesong.core.vehicle.demo.DemoSwitchingNavigationDataSource
import com.drivesong.core.vehicle.demo.DemoSwitchingVehicleDataSource
import com.drivesong.core.vehicle.demo.ScenarioClock
import com.drivesong.core.vehicle.demo.ScenarioPlayer
import com.drivesong.core.vehicle.light.AmbientLightController
import com.drivesong.core.vehicle.light.PleosAmbientLightController
import com.drivesong.core.vehicle.restriction.CarUxRestrictionSource
import com.drivesong.core.vehicle.restriction.UxRestrictionSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleModule {
    @Binds
    abstract fun uxRestrictionSource(impl: CarUxRestrictionSource): UxRestrictionSource

    // 데모 모드 설정에 따라 실제/Fake 로 런타임 전환되는 구현 (demo/DemoSwitchingDataSources.kt)
    @Binds
    abstract fun vehicleDataSource(impl: DemoSwitchingVehicleDataSource): VehicleDataSource

    @Binds
    abstract fun navigationDataSource(impl: DemoSwitchingNavigationDataSource): NavigationDataSource

    @Binds
    abstract fun locationDataSource(impl: DemoSwitchingLocationDataSource): LocationDataSource

    /** Pleos Vehicle 서비스가 없으면 아무것도 하지 않는다 (설계 §6.5). */
    @Binds
    abstract fun ambientLight(impl: PleosAmbientLightController): AmbientLightController

    companion object {
        @Provides
        @Singleton
        fun scenarioPlayer(): ScenarioPlayer = ScenarioPlayer()

        /** 주행 기록 타임스탬프용 시계: 데모 재생 중에는 시나리오 시각. */
        @Provides
        @Singleton
        fun clock(player: ScenarioPlayer): Clock = ScenarioClock(player)
    }
}
