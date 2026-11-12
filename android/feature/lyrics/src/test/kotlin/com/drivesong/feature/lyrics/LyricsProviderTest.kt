package com.drivesong.feature.lyrics

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.TimeOfDay
import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.ClipDto
import com.drivesong.core.network.dto.EnrichedTripDto
import com.drivesong.core.network.dto.LyricsDto
import com.drivesong.core.network.dto.LyricsRequest
import com.drivesong.core.network.dto.MemberRequest
import com.drivesong.core.network.dto.SongJobRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Instant

class LyricsProviderTest {

    private val trip = EnrichedTripDto(
        tripId = "trip_1",
        startedAt = Instant.parse("2026-11-01T00:30:00Z"),
        origin = GeoPoint(37.5, 127.0),
        distanceKm = 230.5,
        durationMin = 170,
        avgSpeedKmh = 81.4,
        maxSpeedKmh = 105.0,
        timeOfDay = TimeOfDay.MORNING,
        placeNames = listOf("서울 중구", "강릉시"),
    )
    private val input = LyricsInput(trip, listOf("엄마", "첫째"), SongStyle.KIDS)

    @Test
    fun `remote provider posts the enriched trip, nicknames and style wire value`() = runTest {
        val api = LyricsApi { LyricsDto("[verse]\n가사", "강릉 가는 길") }

        val result = RemoteLyricsProvider(api).generate(input)

        assertEquals(Lyrics("[verse]\n가사", "강릉 가는 길"), result.getOrThrow())
        assertEquals(listOf(LyricsRequest(trip, listOf("엄마", "첫째"), "kids")), api.requests)
    }

    @Test
    fun `remote provider turns server errors into a failure but rethrows cancellation`() = runTest {
        assertTrue(RemoteLyricsProvider(LyricsApi { throw IOException("offline") }).generate(input).isFailure)
        assertThrows<CancellationException> {
            RemoteLyricsProvider(LyricsApi { throw CancellationException("cancelled") }).generate(input)
        }
    }

    @Test
    fun `fallback uses the first success and skips the rest`() = runTest {
        val calls = mutableListOf<String>()
        fun provider(name: String, result: Result<Lyrics>) = object : LyricsProvider {
            override suspend fun generate(input: LyricsInput): Result<Lyrics> {
                calls += name
                return result
            }
        }
        val chain = FallbackLyricsProvider(
            listOf(
                provider("gleo", Result.failure(IllegalStateException("unavailable"))),
                provider("remote", Result.success(Lyrics("ok", null))),
                provider("never", Result.success(Lyrics("no", null))),
            ),
        )

        assertEquals(Lyrics("ok", null), chain.generate(input).getOrThrow())
        assertEquals(listOf("gleo", "remote"), calls)
    }

    @Test
    fun `fallback returns the last failure when every provider fails`() = runTest {
        val last = IOException("server down")
        val chain = FallbackLyricsProvider(
            listOf(
                object : LyricsProvider {
                    override suspend fun generate(input: LyricsInput) = Result.failure<Lyrics>(IllegalStateException())
                },
                object : LyricsProvider {
                    override suspend fun generate(input: LyricsInput) = Result.failure<Lyrics>(last)
                },
            ),
        )

        assertEquals(last, chain.generate(input).exceptionOrNull())
    }

    private class LyricsApi(private val respond: (LyricsRequest) -> LyricsDto) : DriveSongApi {
        val requests = mutableListOf<LyricsRequest>()

        override suspend fun generateLyrics(body: LyricsRequest): LyricsDto {
            requests += body
            return respond(body)
        }

        override suspend fun registerDevice() = throw UnsupportedOperationException()
        override suspend fun createMember(body: MemberRequest) = throw UnsupportedOperationException()
        override suspend fun deleteMember(memberId: String) = throw UnsupportedOperationException()
        override suspend fun uploadClip(memberId: RequestBody, slot: RequestBody, audio: MultipartBody.Part): ClipDto =
            throw UnsupportedOperationException()
        override suspend fun deleteClip(clipId: String) = throw UnsupportedOperationException()
        override suspend fun updateMember(memberId: String, body: com.drivesong.core.network.dto.MemberConsentUpdate): com.drivesong.core.network.dto.MemberDto =
            throw UnsupportedOperationException()
        override suspend fun uploadConversation(
            tripId: String,
            seq: RequestBody,
            passengers: List<MultipartBody.Part>,
            audio: MultipartBody.Part,
        ): com.drivesong.core.network.dto.ConversationChunkDto = throw UnsupportedOperationException()
        override suspend fun deleteConversation(tripId: String) = throw UnsupportedOperationException()
        override suspend fun enrichTrip(trip: TripSummary) = throw UnsupportedOperationException()
        override suspend fun createSongJob(body: SongJobRequest) = throw UnsupportedOperationException()
        override suspend fun getSongJob(jobId: String) = throw UnsupportedOperationException()
        override suspend fun downloadSongAudio(songId: String) = throw UnsupportedOperationException()
        override suspend fun deleteAllSongs() = throw UnsupportedOperationException()
    }
}
