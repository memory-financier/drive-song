package com.drivesong.core.vehicle

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.haversineKm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 시각(ms)이 붙은 위치. 시각은 단조 증가하는 기준이면 무엇이든 된다 (경과 시간, 시나리오 시각). */
internal data class TimedFix(val timeMs: Long, val point: GeoPoint)

/** [LocationDataSource.locations] 규칙: 첫 위치 + (간격 경과 OR 최소 거리 이동) 때마다. */
internal fun Flow<TimedFix>.throttle(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint> = flow {
    var last: TimedFix? = null
    collect { fix ->
        val prev = last
        if (prev == null ||
            fix.timeMs - prev.timeMs >= intervalMs ||
            haversineKm(prev.point, fix.point) * 1000 >= minDistanceM
        ) {
            last = fix
            emit(fix.point)
        }
    }
}
