package com.drivesong.core.vehicle

import com.drivesong.core.model.GeoPoint
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocationThrottleTest {

    private val home = GeoPoint(37.5, 127.0)
    private fun north(m: Double) = GeoPoint(37.5 + m / 111_195.0, 127.0) // 위도 1도 ≈ 111.195 km

    @Test
    fun `emits first fix, then whichever comes first of interval or distance`() = runTest {
        val fixes = listOf(
            TimedFix(0, home), // 첫 위치
            TimedFix(10_000, north(100.0)), // 10초, 100m → 버림
            TimedFix(20_000, north(600.0)), // 600m → 거리 조건
            TimedFix(30_000, north(650.0)), // 10초, 50m → 버림
            TimedFix(50_000, north(700.0)), // 30초 경과 → 간격 조건
            TimedFix(55_000, north(700.0)), // 버림
        )

        val out = fixes.asFlow().throttle(intervalMs = 30_000, minDistanceM = 500f).toList()

        assertEquals(listOf(home, north(600.0), north(700.0)), out)
    }
}
