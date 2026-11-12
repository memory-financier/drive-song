package com.drivesong.core.vehicle

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import kotlinx.coroutines.flow.Flow

// 차량 데이터 래퍼 (설계 §6.4). 앱에는 데모 모드 설정에 따라 실제/Fake 를 고르는 구현이 주입된다 (di/VehicleModule).

interface VehicleDataSource {
    val speedKmh: Flow<Float>
    val gear: Flow<Gear>
}

interface NavigationDataSource {
    /** 목적지, ETA, 남은 거리. 안내 중인 경로가 없으면 null. */
    val activeRoute: Flow<RouteInfo?>
}

interface LocationDataSource {
    /** 첫 위치, 그 뒤로는 [intervalMs] 경과 또는 [minDistanceM] 이동 중 먼저 오는 쪽마다 내보낸다 (설계 §6.2). */
    fun locations(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint>
}
