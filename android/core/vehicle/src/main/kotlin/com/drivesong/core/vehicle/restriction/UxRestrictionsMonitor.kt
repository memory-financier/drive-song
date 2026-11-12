package com.drivesong.core.vehicle.restriction

import com.drivesong.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 앱 전체가 보는 주행 제한 상태.
 * 실제 기기 상태 OR "주행 제한 시뮬레이션" 설정 → 어떤 에뮬레이터에서도 제한 동작을 시험할 수 있다.
 */
@Singleton
class UxRestrictionsMonitor @Inject constructor(
    source: UxRestrictionSource,
    settings: SettingsRepository,
) {
    val isRestricted: Flow<Boolean> =
        combine(source.isRestricted, settings.simulateDrivingRestriction) { actual, simulated ->
            actual || simulated
        }.distinctUntilChanged()
}
