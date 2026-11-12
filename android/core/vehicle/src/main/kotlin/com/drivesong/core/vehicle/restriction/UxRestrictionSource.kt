package com.drivesong.core.vehicle.restriction

import kotlinx.coroutines.flow.Flow

/** 기기가 알려주는 실제 주행 제한 상태 (true = 주의 분산 최적화가 필요한 상태). */
interface UxRestrictionSource {
    val isRestricted: Flow<Boolean>
}
