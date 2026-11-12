package com.drivesong.core.network.dto

import com.drivesong.core.model.ClipSlot
import com.drivesong.core.network.NetworkJson
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** 설계 §9 JSON 예시와 DTO가 일치하는지 확인한다. */
class SongJobDtoJsonTest {

    private fun json(text: String): JsonElement = NetworkJson.parseToJsonElement(text)

    @Test
    fun `song job request matches design sample`() {
        val sample = """
            {
              "tripId": "trip_20261101_0930",
              "lyrics": "[verse]\n...\n[chorus]\n...",
              "style": "city_pop",
              "durationSec": 75,
              "clipPlan": [
                {"slot": "INTRO", "clipId": "clip_a1"},
                {"slot": "CALL", "clipId": "clip_b2"},
                {"slot": "OUTRO", "clipId": "clip_c3"}
              ],
              "conversation": false
            }
        """.trimIndent()
        val expected = SongJobRequest(
            tripId = "trip_20261101_0930",
            lyrics = "[verse]\n...\n[chorus]\n...",
            style = "city_pop",
            durationSec = 75,
            clipPlan = listOf(
                ClipPlanItem(ClipSlot.INTRO, "clip_a1"),
                ClipPlanItem(ClipSlot.CALL, "clip_b2"),
                ClipPlanItem(ClipSlot.OUTRO, "clip_c3"),
            ),
        )

        assertEquals(expected, NetworkJson.decodeFromString(SongJobRequest.serializer(), sample))
        assertEquals(json(sample), json(NetworkJson.encodeToString(SongJobRequest.serializer(), expected)))
    }

    // 설계 예시의 status 는 "QUEUED | GENERATING | MIXING | READY | FAILED" 로 표기되어 있어 값마다 확인한다.
    // v0.4 (§9.1): ANALYZING·CONVERTING 상태, lyrics·singers 필드, 요청의 conversation (대화 잡이 아니면 false).
    @ParameterizedTest
    @EnumSource(SongJobStatus::class)
    fun `song job response matches design sample`(status: SongJobStatus) {
        val sample = """
            {
              "jobId": "job_123",
              "status": "${status.name}",
              "songId": "song_456",
              "audioUrl": "/v1/songs/song_456/audio",
              "title": "강릉 가는 길",
              "error": null,
              "lyrics": null,
              "singers": []
            }
        """.trimIndent()
        val expected = SongJobDto(
            jobId = "job_123",
            status = status,
            songId = "song_456",
            audioUrl = "/v1/songs/song_456/audio",
            title = "강릉 가는 길",
            error = null,
        )

        assertEquals(expected, NetworkJson.decodeFromString(SongJobDto.serializer(), sample))
        assertEquals(json(sample), json(NetworkJson.encodeToString(SongJobDto.serializer(), expected)))
    }

    @Test
    fun `queued job without song fields and with unknown keys is accepted`() {
        val decoded = NetworkJson.decodeFromString(
            SongJobDto.serializer(),
            """{"jobId": "job_1", "status": "QUEUED", "createdAt": "2026-11-01T00:00:00Z"}""",
        )

        assertEquals(SongJobDto(jobId = "job_1", status = SongJobStatus.QUEUED), decoded)
    }

    @Test
    fun `device and clip responses match server schema`() {
        assertEquals(
            DeviceDto("dev_1", "tok"),
            NetworkJson.decodeFromString(DeviceDto.serializer(), """{"deviceId": "dev_1", "token": "tok"}"""),
        )
        assertEquals(
            ClipDto("clip_1", "mem_1", ClipSlot.CALL, 2.5, "2026-11-01T00:00:00+00:00"),
            NetworkJson.decodeFromString(
                ClipDto.serializer(),
                """{"clipId": "clip_1", "memberId": "mem_1", "slot": "CALL", "durationSec": 2.5,
                   "createdAt": "2026-11-01T00:00:00+00:00"}""",
            ),
        )
    }
}
