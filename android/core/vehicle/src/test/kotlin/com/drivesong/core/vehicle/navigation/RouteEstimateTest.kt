package com.drivesong.core.vehicle.navigation

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.haversineKm
import com.drivesong.core.vehicle.TimedFix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RouteEstimateTest {

    private val preset = DestinationPreset("p", "목적지", GeoPoint(38.0, 127.0))
    private val t0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

    @Test
    fun `no location yet gives destination only`() {
        val route = estimateRoute(preset, emptyList())

        assertEquals(preset.point, route.destination)
        assertEquals("목적지", route.destinationName)
        assertNull(route.etaAt)
        assertNull(route.remainingDistanceKm)
    }

    @Test
    fun `eta from remaining distance and recent average speed`() {
        // 10분 동안 위도 0.1도(≈11.12 km) 북상 → 평균 ≈ 66.7 km/h, 남은 직선 0.4도(≈44.48 km)
        val recent = listOf(
            TimedFix(t0, GeoPoint(37.5, 127.0)),
            TimedFix(t0 + 300_000, GeoPoint(37.55, 127.0)),
            TimedFix(t0 + 600_000, GeoPoint(37.6, 127.0)),
        )

        val route = estimateRoute(preset, recent)

        val remaining = haversineKm(GeoPoint(37.6, 127.0), preset.point) * ROAD_DETOUR_FACTOR
        assertEquals(remaining, route.remainingDistanceKm!!, 1e-9)
        val avgKmh = haversineKm(GeoPoint(37.5, 127.0), GeoPoint(37.6, 127.0)) / (10 / 60.0)
        val expectedEta = Instant.ofEpochMilli(t0 + 600_000).plusMillis((remaining / avgKmh * 3_600_000).toLong())
        assertEquals(expectedEta, route.etaAt)
        // 57.8 km ÷ 66.7 km/h = 0.867 h ≈ 52분
        assertEquals(52.0, Duration.between(Instant.ofEpochMilli(t0 + 600_000), route.etaAt).seconds / 60.0, 0.1)
    }

    @Test
    fun `no eta while stopped`() {
        val recent = listOf(TimedFix(t0, GeoPoint(37.6, 127.0)), TimedFix(t0 + 600_000, GeoPoint(37.6, 127.0)))

        val route = estimateRoute(preset, recent)

        assertNull(route.etaAt)
        assertEquals(haversineKm(GeoPoint(37.6, 127.0), preset.point) * ROAD_DETOUR_FACTOR, route.remainingDistanceKm!!, 1e-9)
    }
}
