package com.drivesong.core.data.erase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.db.ClipEntity
import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.db.TripEntity
import com.drivesong.core.data.db.TripPointEntity
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.trip.TripRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.ClipDto
import com.drivesong.core.network.dto.LyricsRequest
import com.drivesong.core.network.dto.MemberRequest
import com.drivesong.core.network.dto.SongJobRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class AllDataDeleterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, DriveSongDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    private val api = FakeApi()
    private val voice = VoiceRepository(db.voiceDao(), api, FakeSettings(), context)
    private val songs = SongRepository(db.songDao(), api, context)
    private val trips = TripRepository(db.tripDao())
    private val conversation = ConversationRepository(api, context)
    private val deleter = AllDataDeleter(voice, songs, trips, api, conversation)
    private val zone = ZoneId.of("Asia/Seoul")
    private val t0 = Instant.parse("2026-11-01T00:00:00Z")

    @After
    fun tearDown() = db.close()

    private suspend fun seed(): Pair<File, File> {
        val clipFile = File(context.filesDir, "voice_clips/c1.m4a").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        db.voiceDao().insertMember(MemberEntity("mem_1", "엄마", false, true, false))
        db.voiceDao().insertMember(MemberEntity("mem_2", "첫째", true, true, true))
        db.voiceDao().insertClip(ClipEntity(memberId = "mem_1", slot = ClipSlot.INTRO, localPath = clipFile.path, serverId = "clip_1"))
        val songFile = File(context.filesDir, "songs/song_1.m4a").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(2)) }
        db.songDao().upsert(SongEntity("song_1", "trip_done", "강릉 가는 길", "[verse]", 1, songFile.path, listOf(GeoPoint(37.5, 127.0))))
        trips.startTrip("trip_done", t0, emptyList())
        trips.addPoint("trip_done", t0, GeoPoint(37.5, 127.0))
        trips.finishTrip("trip_done", t0.plusSeconds(600), TripEndReason.PARKED, t0.plusSeconds(600), zone)
        trips.startTrip("trip_now", t0.plusSeconds(3600), emptyList())
        trips.addPoint("trip_now", t0.plusSeconds(3600), GeoPoint(37.6, 127.1))
        return clipFile to songFile
    }

    @Test
    fun `turns conversation capture off and deletes local conversation chunks`() = runTest {
        conversation.setArmed(true)
        val chunk = conversation.chunkFile("trip_now", 0).apply { writeBytes(byteArrayOf(3)) }

        deleter.deleteAll()

        assertFalse(conversation.armed.value)
        assertFalse(chunk.exists())
        assertFalse(File(context.filesDir, "conversation").exists())
    }

    @Test
    fun `deletes members on the server, local clips, songs and finished trips but keeps the trip being recorded`() = runTest {
        val (clipFile, songFile) = seed()

        deleter.deleteAll()

        assertEquals(listOf("mem_1", "mem_2"), api.deletedMembers) // 서버가 클립도 지운다 (설계 §9 DELETE /v1/members/{id})
        assertEquals(1, api.songDeletes) // DELETE /v1/songs: 서버의 노래·잡(가사)
        assertTrue(voice.members.first().isEmpty())
        assertTrue(voice.clips.first().isEmpty())
        assertFalse(clipFile.exists())
        assertTrue(songs.songs.first().isEmpty())
        assertFalse(songFile.exists())
        assertNull(db.tripDao().trip("trip_done"))
        assertTrue(db.tripDao().points("trip_done").isEmpty()) // CASCADE
        assertNotNull(db.tripDao().trip("trip_now"))
        assertEquals(1, db.tripDao().points("trip_now").size)
    }

    @Test
    fun `trips left open by a process death and unsaved recordings are deleted too`() = runTest {
        val (clipFile, _) = seed()
        // 이전 프로세스가 기록 중에 죽어 끝나지 못한 주행 (이 저장소는 기록 중으로 알지 못한다)
        db.tripDao().insertTrip(TripEntity(id = "trip_orphan", startedAtMs = 0, passengers = ""))
        db.tripDao().insertPoint(TripPointEntity(tripId = "trip_orphan", recordedAtMs = 0, lat = 37.5, lng = 127.0))
        val draft = File(context.filesDir, "voice_clips/draft.m4a").apply { writeBytes(byteArrayOf(3)) }

        deleter.deleteAll()

        assertNull(db.tripDao().trip("trip_orphan"))
        assertTrue(db.tripDao().points("trip_orphan").isEmpty())
        assertNotNull(db.tripDao().trip("trip_now")) // 기록 중인 주행은 남는다
        assertFalse(draft.exists())
        assertFalse(clipFile.exists())
    }

    @Test
    fun `unsaved recordings are deleted but clips kept for a retry keep their files`() = runTest {
        val (clipFile, _) = seed()
        api.failMember = "mem_1"
        val draft = File(context.filesDir, "voice_clips/draft.m4a").apply { writeBytes(byteArrayOf(3)) }

        runCatching { deleter.deleteAll() }

        assertTrue(clipFile.exists())
        assertFalse(draft.exists())
    }

    @Test
    fun `a member the server could not delete stays for a retry while everything else is deleted`() = runTest {
        seed()
        api.failMember = "mem_1"

        try {
            deleter.deleteAll()
            fail("expected the server failure to be reported")
        } catch (e: IOException) {
            // 예상한 실패
        }

        assertEquals(listOf("mem_1"), voice.members.first().map { it.id })
        assertEquals(1, voice.clips.first().size)
        assertEquals(listOf("mem_2"), api.deletedMembers)
        assertTrue(songs.songs.first().isEmpty())
        assertNull(db.tripDao().trip("trip_done"))
    }

    @Test
    fun `server song deletion failure still deletes local songs and is reported`() = runTest {
        val (_, songFile) = seed()
        api.failSongs = true

        try {
            deleter.deleteAll()
            fail("expected the server failure to be reported")
        } catch (e: IOException) {
            // 예상한 실패: 다시 시도하면 DELETE /v1/songs 를 다시 보낸다
        }

        assertEquals(listOf("mem_1", "mem_2"), api.deletedMembers)
        assertTrue(songs.songs.first().isEmpty())
        assertFalse(songFile.exists())
        assertNull(db.tripDao().trip("trip_done"))
    }

    private class FakeApi : DriveSongApi {
        var failMember: String? = null
        val deletedMembers = mutableListOf<String>()
        var failSongs = false
        var songDeletes = 0

        override suspend fun deleteAllSongs() {
            if (failSongs) throw IOException("offline")
            songDeletes++
        }

        override suspend fun deleteMember(memberId: String) {
            if (memberId == failMember) throw IOException("offline")
            deletedMembers += memberId
        }

        override suspend fun registerDevice() = throw UnsupportedOperationException()
        override suspend fun createMember(body: MemberRequest) = throw UnsupportedOperationException()
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
        override suspend fun generateLyrics(body: LyricsRequest) = throw UnsupportedOperationException()
        override suspend fun createSongJob(body: SongJobRequest) = throw UnsupportedOperationException()
        override suspend fun getSongJob(jobId: String) = throw UnsupportedOperationException()
        override suspend fun downloadSongAudio(songId: String) = throw UnsupportedOperationException()
    }

    private class FakeSettings : SettingsRepository {
        override val simulateDrivingRestriction = MutableStateFlow(false)
        override suspend fun setSimulateDrivingRestriction(enabled: Boolean) = Unit
        override val demoMode = MutableStateFlow(false)
        override suspend fun setDemoMode(enabled: Boolean) = Unit
        override val destinationPresetId = MutableStateFlow<String?>(null)
        override suspend fun setDestinationPresetId(id: String?) = Unit
        override val selectedPassengerIds = MutableStateFlow(emptySet<String>())
        override suspend fun setSelectedPassengerIds(ids: Set<String>) {
            selectedPassengerIds.value = ids
        }
        override val clipPlanMode = MutableStateFlow(ClipPlanMode.RANDOM)
        override suspend fun setClipPlanMode(mode: ClipPlanMode) = Unit
        override val songStyle = MutableStateFlow(SongStyle.CITY_POP)
        override suspend fun setSongStyle(style: SongStyle) = Unit
        override val autoPlay = MutableStateFlow(true)
        override suspend fun setAutoPlay(enabled: Boolean) = Unit
        override val ambientLightShow = MutableStateFlow(true)
        override suspend fun setAmbientLightShow(enabled: Boolean) = Unit
    }
}
