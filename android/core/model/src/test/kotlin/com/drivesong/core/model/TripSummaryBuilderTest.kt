package com.drivesong.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class TripSummaryBuilderTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `haversine of one degree of latitude is R times pi over 180`() {
        assertEquals(6371.0 * Math.PI / 180, haversineKm(GeoPoint(37.0, 127.0), GeoPoint(38.0, 127.0)), 1e-9)
        assertEquals(0.0, haversineKm(GeoPoint(37.5, 127.0), GeoPoint(37.5, 127.0)), 1e-12)
    }

    @Test
    fun `downsample keeps first and last and caps at 50`() {
        val track = List(361) { GeoPoint(37.0 + it * 0.001, 127.0) }

        val sampled = TripSummaryBuilder.downsample(track)

        assertEquals(50, sampled.size)
        assertEquals(track.first(), sampled.first())
        assertEquals(track.last(), sampled.last())
        assertEquals(sampled, sampled.sortedBy { it.lat }) // 순서 유지
        val short = track.take(50)
        assertSame(short, TripSummaryBuilder.downsample(short))
    }

    @Test
    fun `builds distance from the full track, duration, speeds and time of day`() {
        // 위도 0.1도씩 10구간 = 1도 ≈ 111.19 km, 2시간
        val track = List(11) { GeoPoint(37.0 + it * 0.1, 127.0) }
        val start = Instant.parse("2026-11-01T00:30:00Z") // 09:30 KST

        val summary = TripSummaryBuilder.build(
            tripId = "t1",
            startedAt = start,
            endedAt = start.plusSeconds(7200),
            now = start.plusSeconds(9999),
            track = track,
            speedSamplesKmh = listOf(40.0, 98.4, 72.0),
            destinationName = "강릉",
            passengers = listOf("m1"),
            zone = seoul,
        )

        assertEquals(111.2, summary.distanceKm)
        assertEquals(120, summary.durationMin)
        assertEquals(55.6, summary.avgSpeedKmh)
        assertEquals(98.4, summary.maxSpeedKmh)
        assertEquals(TimeOfDay.MORNING, summary.timeOfDay)
        assertEquals(track.first(), summary.origin)
        assertEquals(11, summary.waypoints.size)
        assertEquals(listOf("m1"), summary.passengers)
    }

    @Test
    fun `in-progress trip measures duration until now`() {
        val start = Instant.parse("2026-11-01T12:00:00Z")

        val summary = TripSummaryBuilder.build(
            tripId = "t2", startedAt = start, endedAt = null, now = start.plusSeconds(1800),
            track = listOf(GeoPoint(37.0, 127.0)), speedSamplesKmh = emptyList(), zone = seoul,
        )

        assertNull(summary.endedAt)
        assertEquals(30, summary.durationMin)
        assertEquals(0.0, summary.distanceKm)
        assertEquals(0.0, summary.maxSpeedKmh)
        assertEquals(TimeOfDay.NIGHT, summary.timeOfDay) // 21:00 KST
    }

    @Test
    fun `time of day bucket boundaries`() {
        val expected = mapOf(
            3 to TimeOfDay.NIGHT, 4 to TimeOfDay.DAWN, 6 to TimeOfDay.DAWN, 7 to TimeOfDay.MORNING,
            11 to TimeOfDay.MORNING, 12 to TimeOfDay.AFTERNOON, 16 to TimeOfDay.AFTERNOON,
            17 to TimeOfDay.EVENING, 20 to TimeOfDay.EVENING, 21 to TimeOfDay.NIGHT, 0 to TimeOfDay.NIGHT,
        )
        expected.forEach { (hour, bucket) -> assertEquals(bucket, TripSummaryBuilder.timeOfDayOf(hour), "hour $hour") }
    }
}
