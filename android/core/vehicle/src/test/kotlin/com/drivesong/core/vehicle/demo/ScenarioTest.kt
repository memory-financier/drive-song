package com.drivesong.core.vehicle.demo

import com.drivesong.core.model.Gear
import com.drivesong.core.model.haversineKm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

internal fun loadScenario(demo: DemoScenario): Scenario =
    Scenario.parse(ScenarioTest::class.java.classLoader!!.getResource(demo.assetPath)!!.readText())

class ScenarioTest {

    private val start = Instant.parse("2026-11-01T00:30:00Z")

    @ParameterizedTest
    @EnumSource(DemoScenario::class)
    fun `scenario starts and ends parked and its timing matches its speeds`(demo: DemoScenario) {
        val scenario = loadScenario(demo)

        assertEquals(Gear.PARK, scenario.points.first().gear)
        assertEquals(Gear.PARK, scenario.points.last().gear)
        assertEquals(0f, scenario.points.last().speedKmh)
        assertTrue(scenario.parkHoldSec >= 5 * 60, "P단 5분 정차(설계 §6.2 종료 판정)가 시나리오 안에서 일어나야 함")
        scenario.points.zipWithNext().forEach { (a, b) ->
            val km = haversineKm(a.point, b.point)
            if (km > 0) {
                val implied = km / ((b.t - a.t) / 3600.0)
                val expected = (a.speedKmh + b.speedKmh) / 2.0
                assertTrue(abs(implied - expected) <= expected * 0.15, "${a.label}→${b.label}: $implied vs $expected km/h")
            }
        }
    }

    @Test
    fun `scenario lengths`() {
        val seoul = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        assertTrue(seoul.arrivalSec in 2 * 3600 + 45 * 60..3 * 3600 + 15 * 60, "약 3시간: ${seoul.arrivalSec}s")
        assertTrue(seoul.routeLengthKm in 170.0..260.0, "${seoul.routeLengthKm} km")
        val mart = loadScenario(DemoScenario.NEIGHBORHOOD_MART)
        assertTrue(mart.arrivalSec in 5 * 60..15 * 60, "약 10분: ${mart.arrivalSec}s")
    }

    @Test
    fun `frame interpolates position and speed, route ends on arrival`() {
        val scenario = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        val a = scenario.points[10]
        val b = scenario.points[11]

        val mid = scenario.frameAt((a.t + b.t) / 2.0, start)
        assertEquals((a.lat + b.lat) / 2, mid.location.lat, 1e-3)
        assertEquals((a.speedKmh + b.speedKmh) / 2, mid.speedKmh, 1f)
        assertEquals(Gear.DRIVE, mid.gear)
        assertEquals(start.plusMillis(((a.t + b.t) / 2.0 * 1000).toLong()), mid.at)
        val route = requireNotNull(mid.route)
        assertEquals(start.plusSeconds(scenario.arrivalSec), route.etaAt)
        assertEquals(scenario.destination, route.destination)

        val arrived = scenario.frameAt(scenario.arrivalSec.toDouble(), start)
        assertEquals(Gear.PARK, arrived.gear)
        assertEquals(0f, arrived.speedKmh)
        assertNull(arrived.route)
        assertEquals(scenario.destination, arrived.location)
        assertEquals(Gear.PARK, scenario.frameAt(0.0, start).gear)
    }

    @Test
    fun `remaining distance shrinks to zero`() {
        val scenario = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        val remaining = (0 until scenario.arrivalSec step 60).map {
            scenario.frameAt(it.toDouble(), start).route!!.remainingDistanceKm!!
        }
        assertEquals(scenario.routeLengthKm, remaining.first(), 1e-9)
        assertEquals(remaining, remaining.sortedDescending())
        assertTrue(remaining.last() < 1.0)
    }

    @Test
    fun `start instant uses the local start time`() {
        val scenario = loadScenario(DemoScenario.SEOUL_GANGNEUNG)
        assertEquals(
            Instant.parse("2026-11-01T00:30:00Z"),
            scenario.startInstant(LocalDate.of(2026, 11, 1), ZoneId.of("Asia/Seoul")),
        )
    }
}
