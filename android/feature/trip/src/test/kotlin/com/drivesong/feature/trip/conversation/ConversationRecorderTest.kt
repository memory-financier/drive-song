package com.drivesong.feature.trip.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.ClipDto
import com.drivesong.core.network.dto.ConversationChunkDto
import com.drivesong.core.network.dto.LyricsRequest
import com.drivesong.core.network.dto.MemberConsentUpdate
import com.drivesong.core.network.dto.MemberRequest
import com.drivesong.core.network.dto.SongJobRequest
import com.drivesong.feature.trip.FakeSettings
import com.drivesong.feature.trip.TripEvent
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripState
import com.drivesong.feature.trip.inMemoryDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConversationRecorderTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDatabase()
    private val api = FakeConversationApi()
    private val settings = FakeSettings(setOf("mem_mom", "mem_kid"))
    private val trips = FakeTrips()
    private val repo = ConversationRepository(api, context)
    private val mic = FakeMic()
    private var micGranted = true
    private val recorder = ConversationRecorder(
        trips, repo, VoiceRepository(db.voiceDao(), api, settings, context), settings, mic, { micGranted },
    )
    private val tripDir = File(context.filesDir, "conversation/trip_1")

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "conversation").deleteRecursively()
    }

    private fun test(kidAgreed: Boolean = true, block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        db.voiceDao().insertMember(MemberEntity("mem_mom", "엄마", false, true, false, conversationConsent = true))
        db.voiceDao().insertMember(MemberEntity("mem_kid", "첫째", true, true, true, conversationConsent = kidAgreed))
        backgroundScope.launch { recorder.run() }
        runCurrent()
        block()
    }


    private fun TestScope.drive(tripId: String = "trip_1") {
        trips.state.value = TripState.Recording(tripId, Instant.parse("2026-11-04T00:00:00Z"), null)
        runCurrent()
    }

    private fun TestScope.minutes(m: Long) {
        advanceTimeBy(m * 60_000)
        runCurrent()
    }

    @Test
    fun `records five-minute chunks on a trip and uploads each one with its passengers`() = test {
        repo.setArmed(true)
        drive()
        assertTrue(repo.recording.value)
        assertEquals(1, mic.starts.size)

        minutes(5)
        assertEquals(listOf(Upload("trip_1", "0", listOf("mem_kid", "mem_mom"))), api.uploads)
        assertEquals(2, mic.starts.size) // 다음 조각은 이미 녹음 중
        assertEquals(listOf("1.m4a"), tripDir.list()!!.toList()) // 올린 조각은 바로 지운다

        trips.state.value = TripState.Idle // 주행 종료: 마지막 조각은 노래 시작 때 올린다
        runCurrent()
        assertFalse(repo.recording.value)
        assertEquals(1, api.uploads.size)

        assertTrue(recorder.finish("trip_1"))
        assertEquals(listOf("0", "1"), api.uploads.map { it.seq })
        assertFalse(repo.armed.value) // 주행마다 한 번
        assertFalse(tripDir.exists())
    }

    @Test
    fun `finish during the trip stops recording for that trip`() = test {
        repo.setArmed(true)
        drive()
        minutes(2)
        assertTrue(recorder.finish("trip_1")) // 도착 10분 전 노래 생성 시작
        runCurrent()
        assertFalse(repo.recording.value)
        repo.setArmed(true) // 다시 켜도 노래를 시작한 주행은 담지 않는다
        runCurrent()
        assertEquals(1, mic.starts.size)
    }

    @Test
    fun `nothing is recorded unless every passenger agreed`() = test(kidAgreed = false) {
        repo.setArmed(true)
        drive()
        minutes(6)
        assertEquals(0, mic.starts.size)
        assertFalse(repo.recording.value)
        assertFalse(recorder.finish("trip_1"))
    }

    @Test
    fun `nothing is recorded when capture is off or the microphone is not allowed`() = test {
        drive()
        minutes(6)
        assertEquals(0, mic.starts.size)

        micGranted = false
        repo.setArmed(true)
        runCurrent()
        assertEquals(0, mic.starts.size)
    }

    @Test
    fun `turning it off mid-trip deletes the trip's conversation and turning it on again continues`() = test {
        repo.setArmed(true)
        drive()
        minutes(5) // 조각 0 업로드
        repo.setArmed(false) // 배너 '끄기'
        runCurrent()
        assertEquals(listOf("trip_1"), api.deletes)
        assertFalse(tripDir.exists())
        assertFalse(repo.recording.value)

        repo.setArmed(true)
        runCurrent()
        minutes(5)
        assertEquals(listOf("0", "2"), api.uploads.map { it.seq }) // 조각 1은 지웠다
    }

    @Test
    fun `failed uploads are retried at finish and never left on the device`() = test {
        api.failUploads = true
        repo.setArmed(true)
        drive()
        minutes(5)
        trips.state.value = TripState.Idle
        runCurrent()
        assertTrue(tripDir.list()!!.size == 2)

        assertFalse(recorder.finish("trip_1"))
        assertTrue(api.uploadAttempts >= 1 + ConversationRecorder.FINISH_ATTEMPTS)
        assertFalse(tripDir.exists())
    }

    @Test
    fun `discard after finish removes the conversation on the server`() = test {
        repo.setArmed(true)
        drive()
        minutes(5)
        assertTrue(recorder.finish("trip_1"))
        recorder.discard("trip_1") // 노래 생성 실패: 서버에 남은 대화를 지운다
        assertEquals(listOf("trip_1"), api.deletes)
        recorder.discard("trip_other") // 담지 않은 주행은 서버를 부르지 않는다
        assertEquals(listOf("trip_1"), api.deletes)
    }

    @Test
    fun `chunk files left by a previous process are deleted on start`() = runTest(dispatcher) {
        val old = File(context.filesDir, "conversation/trip_old/0.m4a").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        backgroundScope.launch { recorder.run() }
        runCurrent()
        assertFalse(old.exists())
    }

    data class Upload(val tripId: String, val seq: String, val passengers: List<String>)

    private class FakeMic : ChunkRecorder {
        val starts = mutableListOf<File>()
        override suspend fun start(file: File) {
            starts += file
            file.writeBytes(byteArrayOf(0, 0, 0, 24))
        }
        override fun stop() = true
    }

    private class FakeTrips : TripEvents {
        override val state = MutableStateFlow<TripState>(TripState.Idle)
        override val events = MutableSharedFlow<TripEvent>()
        override suspend fun currentSummary(): TripSummary? = null
    }

    private class FakeConversationApi : DriveSongApi {
        val uploads = mutableListOf<Upload>()
        val deletes = mutableListOf<String>()
        var failUploads = false
        var uploadAttempts = 0

        override suspend fun uploadConversation(
            tripId: String,
            seq: RequestBody,
            passengers: List<MultipartBody.Part>,
            audio: MultipartBody.Part,
        ): ConversationChunkDto {
            uploadAttempts++
            if (failUploads) throw IOException("offline")
            val seqText = seq.text()
            uploads += Upload(tripId, seqText, passengers.map { it.body.text() })
            return ConversationChunkDto(tripId, seqText.toInt(), 300.0)
        }

        override suspend fun deleteConversation(tripId: String) {
            deletes += tripId
        }

        private fun RequestBody.text() = Buffer().also { writeTo(it) }.readUtf8()

        override suspend fun registerDevice() = throw UnsupportedOperationException()
        override suspend fun createMember(body: MemberRequest) = throw UnsupportedOperationException()
        override suspend fun deleteMember(memberId: String) = throw UnsupportedOperationException()
        override suspend fun updateMember(memberId: String, body: MemberConsentUpdate) = throw UnsupportedOperationException()
        override suspend fun uploadClip(memberId: RequestBody, slot: RequestBody, audio: MultipartBody.Part): ClipDto =
            throw UnsupportedOperationException()
        override suspend fun deleteClip(clipId: String) = throw UnsupportedOperationException()
        override suspend fun enrichTrip(trip: TripSummary) = throw UnsupportedOperationException()
        override suspend fun generateLyrics(body: LyricsRequest) = throw UnsupportedOperationException()
        override suspend fun createSongJob(body: SongJobRequest) = throw UnsupportedOperationException()
        override suspend fun getSongJob(jobId: String) = throw UnsupportedOperationException()
        override suspend fun downloadSongAudio(songId: String) = throw UnsupportedOperationException()
        override suspend fun deleteAllSongs() = throw UnsupportedOperationException()
    }
}
