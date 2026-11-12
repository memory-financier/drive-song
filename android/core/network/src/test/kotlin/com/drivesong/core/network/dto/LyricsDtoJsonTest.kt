package com.drivesong.core.network.dto

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.TimeOfDay
import com.drivesong.core.network.NetworkJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

/** enrich / lyrics / song-jobs DTO 가 서버(server/app/api/trips.py, lyrics.py, song_jobs.py) 형식과 맞는지. */
class LyricsDtoJsonTest {

    // server EnrichedTrip.model_dump_json(by_alias=True) 출력 (pydantic 2.12 로 생성)
    private val serverEnriched = """
        {"tripId":"trip_1","startedAt":"2026-11-01T00:30:00Z","endedAt":null,"etaAt":"2026-11-01T03:30:00+09:00",
         "origin":{"lat":37.5663,"lng":126.9779},"destination":{"lat":37.804,"lng":128.906},
         "destinationName":"강릉 경포해변","waypoints":[{"lat":37.5663,"lng":126.9779}],"distanceKm":230.5,
         "durationMin":170,"avgSpeedKmh":81.4,"maxSpeedKmh":105.0,"timeOfDay":"MORNING","passengers":["mem_1"],
         "originName":"서울 중구","placeNames":["서울 중구","양평군","강릉시"],
         "weather":{"description":"맑음","temperatureC":12.5}}
    """.trimIndent()

    @Test
    fun `server enriched trip decodes`() {
        val trip = NetworkJson.decodeFromString(EnrichedTripDto.serializer(), serverEnriched)

        assertEquals(
            EnrichedTripDto(
                tripId = "trip_1",
                startedAt = Instant.parse("2026-11-01T00:30:00Z"),
                endedAt = null,
                etaAt = Instant.parse("2026-10-31T18:30:00Z"),
                origin = GeoPoint(37.5663, 126.9779),
                destination = GeoPoint(37.804, 128.906),
                destinationName = "강릉 경포해변",
                waypoints = listOf(GeoPoint(37.5663, 126.9779)),
                distanceKm = 230.5,
                durationMin = 170,
                avgSpeedKmh = 81.4,
                maxSpeedKmh = 105.0,
                timeOfDay = TimeOfDay.MORNING,
                passengers = listOf("mem_1"),
                originName = "서울 중구",
                placeNames = listOf("서울 중구", "양평군", "강릉시"),
                weather = WeatherDto("맑음", 12.5),
            ),
            trip,
        )
    }

    @Test
    fun `lyrics request sends the enriched trip back under enrichedTrip`() {
        val trip = NetworkJson.decodeFromString(EnrichedTripDto.serializer(), serverEnriched)
        val body = NetworkJson.parseToJsonElement(
            NetworkJson.encodeToString(LyricsRequest.serializer(), LyricsRequest(trip, listOf("아빠"), "kids")),
        ).jsonObject

        assertEquals(setOf("enrichedTrip", "familyNicknames", "style"), body.keys)
        val sent = body.getValue("enrichedTrip") as JsonObject
        assertEquals(NetworkJson.parseToJsonElement(serverEnriched).jsonObject.keys, sent.keys)
        assertEquals("2026-11-01T00:30:00Z", sent.getValue("startedAt").jsonPrimitive.content)
    }

    @Test
    fun `lyrics response with and without title`() {
        assertEquals(
            LyricsDto("[verse]\n가사", "강릉 경포해변 가는 길"),
            NetworkJson.decodeFromString(LyricsDto.serializer(), """{"lyrics":"[verse]\n가사","title":"강릉 경포해변 가는 길"}"""),
        )
        assertEquals(LyricsDto("x", null), NetworkJson.decodeFromString(LyricsDto.serializer(), """{"lyrics":"x","title":null}"""))
    }

    @Test
    fun `song job request omits a null title and sends a given one`() {
        val noTitle = SongJobRequest("t", "l", "kids", 75, emptyList())
        assertFalse("title" in NetworkJson.parseToJsonElement(NetworkJson.encodeToString(SongJobRequest.serializer(), noTitle)).jsonObject)

        val withTitle = NetworkJson.parseToJsonElement(
            NetworkJson.encodeToString(SongJobRequest.serializer(), noTitle.copy(title = "강릉 가는 길")),
        ).jsonObject
        assertEquals("강릉 가는 길", withTitle.getValue("title").jsonPrimitive.content)
    }
}
