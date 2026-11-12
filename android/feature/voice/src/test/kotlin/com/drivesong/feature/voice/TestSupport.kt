package com.drivesong.feature.voice

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.ClipDto
import com.drivesong.core.network.dto.ConversationChunkDto
import com.drivesong.core.network.dto.LyricsRequest
import com.drivesong.core.network.dto.MemberConsentUpdate
import com.drivesong.core.network.dto.MemberDto
import com.drivesong.core.network.dto.MemberRequest
import com.drivesong.core.network.dto.SongJobRequest
import com.drivesong.core.vehicle.restriction.UxRestrictionSource
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import java.io.File
import java.io.IOException

/** Room 인메모리 DB. 쿼리를 호출한 스레드에서 바로 실행해 코루틴 테스트 디스패처와 함께 쓸 수 있게 한다. */
fun inMemoryDatabase(): DriveSongDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DriveSongDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

/** 구성원·클립 API 만 흉내 낸다. */
class FakeApi : DriveSongApi {
    var failUploads = false
    var failDeletes = false
    val uploads = mutableListOf<Pair<String, ClipSlot>>()
    val deletedMembers = mutableListOf<String>()
    val deletedClips = mutableListOf<String>()
    private var nextId = 0

    override suspend fun createMember(body: MemberRequest) = MemberDto(
        "mem_${++nextId}", body.nickname, body.isChild, body.voiceConsent, body.guardianConsent, "2026-11-01T00:00:00",
        body.conversationConsent, body.voiceConversionConsent,
    )

    val consentUpdates = mutableListOf<Pair<String, MemberConsentUpdate>>()

    override suspend fun updateMember(memberId: String, body: MemberConsentUpdate): MemberDto {
        if (failDeletes) throw IOException("offline")
        consentUpdates += memberId to body
        return MemberDto(memberId, "엄마", false, true, false, "2026-11-01T00:00:00",
            body.conversationConsent, body.voiceConversionConsent)
    }

    override suspend fun uploadConversation(
        tripId: String,
        seq: RequestBody,
        passengers: List<MultipartBody.Part>,
        audio: MultipartBody.Part,
    ): ConversationChunkDto = throw UnsupportedOperationException()

    override suspend fun deleteConversation(tripId: String) = throw UnsupportedOperationException()

    override suspend fun deleteMember(memberId: String) {
        if (failDeletes) throw IOException("offline")
        deletedMembers += memberId
    }

    override suspend fun uploadClip(memberId: RequestBody, slot: RequestBody, audio: MultipartBody.Part): ClipDto {
        if (failUploads) throw IOException("offline")
        val member = memberId.text()
        val clipSlot = ClipSlot.valueOf(slot.text())
        uploads += member to clipSlot
        return ClipDto("clip_${++nextId}", member, clipSlot, 1.0, "2026-11-01T00:00:00")
    }

    override suspend fun deleteClip(clipId: String) {
        if (failDeletes) throw IOException("offline")
        deletedClips += clipId
    }

    override suspend fun registerDevice() = throw UnsupportedOperationException()
    override suspend fun enrichTrip(trip: TripSummary) = throw UnsupportedOperationException()
    override suspend fun generateLyrics(body: LyricsRequest) = throw UnsupportedOperationException()
    override suspend fun createSongJob(body: SongJobRequest) = throw UnsupportedOperationException()
    override suspend fun getSongJob(jobId: String) = throw UnsupportedOperationException()
    override suspend fun downloadSongAudio(songId: String) = throw UnsupportedOperationException()
    override suspend fun deleteAllSongs() = throw UnsupportedOperationException()

    private fun RequestBody.text() = Buffer().also { writeTo(it) }.readUtf8()
}

/** 녹음 시작/정지 시각을 기록한다. 정지하면 파일에 가짜 오디오를 쓴다. */
class FakeRecorder(private val now: () -> Long) : VoiceRecorder {
    var recording: File? = null
    var stopResult = true
    var failStart = false
    val stoppedAt = mutableListOf<Long>()
    var starts = 0

    override fun start(file: File) {
        if (failStart) throw IllegalStateException("no microphone")
        starts++
        recording = file
    }

    override fun stop(): Boolean {
        val file = recording ?: return false
        recording = null
        stoppedAt += now()
        if (stopResult) file.writeBytes(byteArrayOf(1, 2, 3))
        return stopResult
    }
}

class FakePlayer : ClipPlayer {
    val played = mutableListOf<File>()
    override fun play(file: File) {
        played += file
    }
    override fun stop() = Unit
}

class FakeRestrictionSource : UxRestrictionSource {
    override val isRestricted = MutableStateFlow(false)
}

class FakeSettings : SettingsRepository {
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
    override suspend fun setClipPlanMode(mode: ClipPlanMode) {
        clipPlanMode.value = mode
    }
    override val songStyle = MutableStateFlow(SongStyle.CITY_POP)
    override suspend fun setSongStyle(style: SongStyle) = Unit
    override val autoPlay = MutableStateFlow(true)
    override suspend fun setAutoPlay(enabled: Boolean) = Unit
    override val ambientLightShow = MutableStateFlow(true)
    override suspend fun setAmbientLightShow(enabled: Boolean) = Unit
}
