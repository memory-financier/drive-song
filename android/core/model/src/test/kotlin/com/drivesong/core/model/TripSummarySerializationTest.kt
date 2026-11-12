package com.drivesong.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TripSummarySerializationTest {

    private val trip = TripSummary(
        tripId = "trip_20261101_0930",
        startedAt = Instant.parse("2026-11-01T00:30:00Z"),
        endedAt = null,
        etaAt = Instant.parse("2026-11-01T03:30:00Z"),
        origin = GeoPoint(37.5665, 126.9780),
        destination = GeoPoint(37.7519, 128.8761),
        destinationName = "강릉",
        waypoints = listOf(GeoPoint(37.5, 127.5)),
        distanceKm = 237.4,
        durationMin = 180,
        avgSpeedKmh = 79.1,
        maxSpeedKmh = 110.0,
        timeOfDay = TimeOfDay.MORNING,
        passengers = listOf("mem_1"),
    )

    @Test
    fun `round trip keeps all fields and writes instants as ISO-8601 UTC`() {
        val json = Json.encodeToString(TripSummary.serializer(), trip)

        assertEquals(trip, Json.decodeFromString(TripSummary.serializer(), json))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("2026-11-01T00:30:00Z", obj.getValue("startedAt").jsonPrimitive.content)
        assertEquals("MORNING", obj.getValue("timeOfDay").jsonPrimitive.content)
    }

    @Test
    fun `instant with offset is parsed`() {
        val parsed = Json.decodeFromString(InstantIsoSerializer, "\"2026-11-01T09:30:00+09:00\"")

        assertEquals(Instant.parse("2026-11-01T00:30:00Z"), parsed)
    }
}
