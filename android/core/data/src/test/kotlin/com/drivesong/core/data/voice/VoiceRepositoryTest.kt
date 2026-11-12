package com.drivesong.core.data.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.db.UploadState
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.NetworkJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VoiceRepositoryTest {

    private val server = MockWebServer().apply { start() }
    private val api: DriveSongApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(NetworkJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, DriveSongDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    private val settings = FakeSettings()
    private val clock = TestClock()
    private val repo = VoiceRepository(db.voiceDao(), api, settings, context, clock)

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun `addMember posts nickname and consents then stores the server member`() = runTest {
        server.enqueue(memberResponse("mem_1", "첫째", isChild = true))

        val member = repo.addMember("첫째", isChild = true, voiceConsent = true, guardianConsent = true)

        val request = server.takeRequest()
        assertEquals("POST /v1/members HTTP/1.1", request.requestLine)
        assertEquals(
            """{"nickname":"첫째","isChild":true,"voiceConsent":true,"guardianConsent":true,""" +
                """"conversationConsent":false,"voiceConversionConsent":false}""",
            request.body.readUtf8(),
        )
        assertEquals("mem_1", member.id)
        assertEquals(listOf(member), repo.members.first())
    }

    @Test
    fun `optional consents are sent on add and changed with PATCH`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"memberId":"mem_1","nickname":"엄마","isChild":false,"voiceConsent":true,"guardianConsent":false,""" +
                """"createdAt":"2026-11-04T00:00:00","conversationConsent":true,"voiceConversionConsent":true}""",
        ))
        val added = repo.addMember("엄마", false, voiceConsent = true, guardianConsent = false,
            conversationConsent = true, voiceConversionConsent = true)
        assertTrue(server.takeRequest().body.readUtf8().contains(""""conversationConsent":true,"voiceConversionConsent":true"""))
        assertTrue(added.conversationConsent && added.voiceConversionConsent)

        server.enqueue(MockResponse().setBody(
            """{"memberId":"mem_1","nickname":"엄마","isChild":false,"voiceConsent":true,"guardianConsent":false,""" +
                """"createdAt":"2026-11-04T00:00:00","conversationConsent":true,"voiceConversionConsent":false}""",
        ))
        repo.updateConsents("mem_1", conversationConsent = true, voiceConversionConsent = false)
        val patch = server.takeRequest()
        assertEquals("PATCH /v1/members/mem_1 HTTP/1.1", patch.requestLine)
        val stored = repo.members.first().single()
        assertTrue(stored.conversationConsent)
        assertFalse(stored.voiceConversionConsent)

        // 서버가 실패하면 로컬도 바꾸지 않는다
        server.enqueue(MockResponse().setResponseCode(500))
        expectThrows<HttpException> { repo.updateConsents("mem_1", conversationConsent = false, voiceConversionConsent = false) }
        assertTrue(repo.members.first().single().conversationConsent)
    }

    @Test
    fun `addMember without required consent sends nothing`() = runTest {
        expectThrows<IllegalArgumentException> { repo.addMember("엄마", false, voiceConsent = false, guardianConsent = false) }
        expectThrows<IllegalArgumentException> { repo.addMember("둘째", true, voiceConsent = true, guardianConsent = false) }
        assertEquals(0, server.requestCount)
        assertTrue(repo.members.first().isEmpty())
    }

    @Test
    fun `upload sends multipart memberId, slot and m4a audio`() = runTest {
        addMember("mem_1")
        val file = recording(byteArrayOf(1, 2, 3, 4))
        server.enqueue(clipResponse("clip_a1", "mem_1", "CALL"))

        val clip = repo.upload(repo.addClip("mem_1", ClipSlot.CALL, file))

        val request = server.takeRequest()
        assertEquals("POST /v1/clips HTTP/1.1", request.requestLine)
        val parts = multipartParts(request)
        assertEquals(listOf("memberId", "slot", "audio"), parts.map { it.name })
        assertEquals("mem_1", parts[0].text)
        assertEquals("CALL", parts[1].text)
        assertEquals(file.name, parts[2].filename)
        assertTrue(file.name.endsWith(".m4a"))
        assertEquals("audio/mp4", parts[2].contentType)
        assertEquals(listOf<Byte>(1, 2, 3, 4), parts[2].bytes.toList())

        assertEquals("clip_a1", clip.serverId)
        assertEquals(UploadState.UPLOADED, clip.uploadState)
        assertEquals(listOf(clip), repo.clips.first())
    }

    @Test
    fun `failed upload keeps the clip as FAILED and retry uploads it`() = runTest {
        addMember("mem_1")
        server.enqueue(MockResponse().setResponseCode(500))

        expectThrows<HttpException> { repo.upload(repo.addClip("mem_1", ClipSlot.INTRO, recording())) }
        val failed = repo.clips.first().single()
        assertEquals(UploadState.FAILED, failed.uploadState)
        assertNull(failed.serverId)

        server.takeRequest()
        server.enqueue(clipResponse("clip_x", "mem_1", "INTRO"))
        repo.upload(failed)
        val uploaded = repo.clips.first().single()
        assertEquals(UploadState.UPLOADED, uploaded.uploadState)
        assertEquals("clip_x", uploaded.serverId)
    }

    @Test
    fun `at most three clips per member and slot`() = runTest {
        addMember("mem_1")
        repeat(3) {
            server.enqueue(clipResponse("clip_$it", "mem_1", "OUTRO"))
            repo.upload(repo.addClip("mem_1", ClipSlot.OUTRO, recording()))
        }
        expectThrows<IllegalStateException> { repo.upload(repo.addClip("mem_1", ClipSlot.OUTRO, recording())) }
        assertEquals(4, server.requestCount) // 구성원 1 + 클립 3

        server.enqueue(clipResponse("clip_i", "mem_1", "INTRO"))
        repo.upload(repo.addClip("mem_1", ClipSlot.INTRO, recording())) // 다른 슬롯은 따로 센다
        assertEquals(4, repo.clips.first().size)
    }

    @Test
    fun `deleteClip deletes on the server then removes the local file and row`() = runTest {
        addMember("mem_1")
        val file = recording()
        server.enqueue(clipResponse("clip_a1", "mem_1", "INTRO"))
        val clip = repo.upload(repo.addClip("mem_1", ClipSlot.INTRO, file))
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))

        repo.deleteClip(clip)

        assertEquals("DELETE /v1/clips/clip_a1 HTTP/1.1", server.takeRequest().requestLine)
        assertFalse(file.exists())
        assertTrue(repo.clips.first().isEmpty())
    }

    @Test
    fun `deleteClip of a never uploaded clip only removes it locally`() = runTest {
        addMember("mem_1")
        server.enqueue(MockResponse().setResponseCode(500))
        val file = recording()
        expectThrows<HttpException> { repo.upload(repo.addClip("mem_1", ClipSlot.INTRO, file)) }

        repo.deleteClip(repo.clips.first().single())

        assertEquals(2, server.requestCount) // 구성원 1 + 실패한 업로드 1, 삭제 요청 없음
        assertFalse(file.exists())
        assertTrue(repo.clips.first().isEmpty())
    }

    @Test
    fun `deleteMember removes server data, local clips and the passenger selection`() = runTest {
        addMember("mem_1")
        addMember("mem_2")
        val files = List(2) { recording() }
        files.forEachIndexed { i, file ->
            server.enqueue(clipResponse("clip_$i", "mem_1", "INTRO"))
            repo.upload(repo.addClip("mem_1", ClipSlot.INTRO, file))
        }
        repeat(2) { server.takeRequest() }
        settings.selectedPassengerIds.value = setOf("mem_1", "mem_2")
        server.enqueue(MockResponse().setResponseCode(204))

        repo.deleteMember("mem_1")

        assertEquals("DELETE /v1/members/mem_1 HTTP/1.1", server.takeRequest().requestLine)
        files.forEach { assertFalse(it.exists()) }
        assertEquals(listOf("mem_2"), repo.members.first().map { it.id })
        assertTrue(repo.clips.first().isEmpty())
        assertEquals(setOf("mem_2"), settings.selectedPassengerIds.value)
    }

    @Test
    fun `deleteMember treats 404 as already deleted but keeps everything on other errors`() = runTest {
        addMember("mem_1")
        addMember("mem_2")
        server.enqueue(MockResponse().setResponseCode(500))
        expectThrows<HttpException> { repo.deleteMember("mem_1") }
        assertEquals(2, repo.members.first().size)

        server.enqueue(MockResponse().setResponseCode(404))
        repo.deleteMember("mem_1")
        assertEquals(listOf("mem_2"), repo.members.first().map { it.id })
    }

    @Test
    fun `planClips uses only uploaded clips of passengers and rotates in order`() = runTest {
        addMember("mom")
        addMember("kid")
        uploadClip("mom", ClipSlot.INTRO, "c_mom1")
        uploadClip("mom", ClipSlot.INTRO, "c_mom2")
        uploadClip("kid", ClipSlot.CALL, "c_kid_call")
        server.enqueue(MockResponse().setResponseCode(500)) // 업로드 안 된 클립은 고르지 않는다
        expectThrows<HttpException> { repo.upload(repo.addClip("kid", ClipSlot.OUTRO, recording())) }
        settings.clipPlanMode.value = ClipPlanMode.IN_ORDER

        val first = repo.planClips(setOf("mom", "kid"))
        val second = repo.planClips(setOf("mom", "kid"))
        val third = repo.planClips(setOf("mom", "kid"))

        assertEquals(mapOf(ClipSlot.INTRO to "c_mom1", ClipSlot.CALL to "c_kid_call"), first)
        assertEquals(mapOf(ClipSlot.INTRO to "c_mom2", ClipSlot.CALL to "c_kid_call"), second)
        assertEquals("c_mom1", third[ClipSlot.INTRO])
        assertEquals(mapOf(ClipSlot.CALL to "c_kid_call"), repo.planClips(setOf("kid")))
        assertEquals(emptyMap<ClipSlot, String>(), repo.planClips(emptySet()))
    }

    @Test
    fun `addClipReplacingOldest deletes the first recording of a full slot, then saves`() = runTest {
        addMember("mem_1")
        repeat(3) { uploadClip("mem_1", ClipSlot.CALL, "clip_$it") }
        val oldest = repo.clips.first().first()
        server.enqueue(MockResponse().setResponseCode(204))

        val added = repo.addClipReplacingOldest("mem_1", ClipSlot.CALL, recording())

        assertEquals("DELETE /v1/clips/clip_0 HTTP/1.1", server.takeRequest().requestLine)
        assertFalse(File(oldest.localPath).exists())
        val clips = repo.clips.first()
        assertEquals(listOf("clip_1", "clip_2", null), clips.map { it.serverId })
        assertEquals(added.localId, clips.last().localId)
    }

    @Test
    fun `addClipReplacingOldest with room left deletes nothing`() = runTest {
        addMember("mem_1")
        uploadClip("mem_1", ClipSlot.CALL, "clip_0")

        repo.addClipReplacingOldest("mem_1", ClipSlot.CALL, recording())

        assertEquals(2, server.requestCount) // 구성원 1 + 업로드 1, 삭제 없음
        assertEquals(2, repo.clips.first().size)
    }

    @Test
    fun `addClipReplacingOldest keeps everything when the server delete fails`() = runTest {
        addMember("mem_1")
        repeat(3) { uploadClip("mem_1", ClipSlot.CALL, "clip_$it") }
        server.enqueue(MockResponse().setResponseCode(500))

        expectThrows<HttpException> { repo.addClipReplacingOldest("mem_1", ClipSlot.CALL, recording()) }

        assertEquals(listOf("clip_0", "clip_1", "clip_2"), repo.clips.first().map { it.serverId })
    }

    @Test
    fun `planClips prefers a clip recorded after the trip started`() = runTest {
        addMember("kid")
        clock.millis = 1_000
        uploadClip("kid", ClipSlot.CALL, "c_old")
        clock.millis = 5_000 // 주행 시작(3_000) 이후 음성 녹음
        uploadClip("kid", ClipSlot.CALL, "c_new")
        settings.clipPlanMode.value = ClipPlanMode.IN_ORDER // 이 규칙이 없으면 c_old 가 먼저

        assertEquals(mapOf(ClipSlot.CALL to "c_new"), repo.planClips(setOf("kid"), recordedSinceMs = 3_000))
        assertEquals(5_000, repo.clips.first().last().recordedAtMs)
    }

    // --- helpers ---

    private class TestClock(var millis: Long = 0) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): java.time.Instant = java.time.Instant.ofEpochMilli(millis)
        override fun millis() = millis
    }

    private suspend fun addMember(id: String) {
        server.enqueue(memberResponse(id, id, isChild = false))
        repo.addMember(id, isChild = false, voiceConsent = true, guardianConsent = false)
        server.takeRequest()
    }

    private suspend fun uploadClip(memberId: String, slot: ClipSlot, clipId: String) {
        server.enqueue(clipResponse(clipId, memberId, slot.name))
        repo.upload(repo.addClip(memberId, slot, recording()))
        server.takeRequest()
    }

    private fun recording(bytes: ByteArray = byteArrayOf(0)): File = repo.newRecordingFile().apply { writeBytes(bytes) }

    private fun memberResponse(id: String, nickname: String, isChild: Boolean) = MockResponse().setResponseCode(201).setBody(
        """{"memberId":"$id","nickname":"$nickname","isChild":$isChild,"voiceConsent":true,
            "guardianConsent":$isChild,"createdAt":"2026-11-01T00:00:00"}""",
    )

    private fun clipResponse(id: String, memberId: String, slot: String) = MockResponse().setResponseCode(201).setBody(
        """{"clipId":"$id","memberId":"$memberId","slot":"$slot","durationSec":2.5,"createdAt":"2026-11-01T00:00:00"}""",
    )

    private class Part(val name: String?, val filename: String?, val contentType: String?, val bytes: ByteArray) {
        val text get() = bytes.decodeToString()
    }

    private fun multipartParts(request: RecordedRequest): List<Part> {
        val contentType = request.getHeader("Content-Type")!!
        assertTrue(contentType, contentType.startsWith("multipart/form-data"))
        val reader = MultipartReader(request.body, contentType.substringAfter("boundary="))
        return generateSequence { reader.nextPart() }.map { part ->
            val disposition = part.headers["Content-Disposition"]!!
            Part(
                name = Regex("""\bname="([^"]*)"""").find(disposition)?.groupValues?.get(1),
                filename = Regex("""filename="([^"]*)"""").find(disposition)?.groupValues?.get(1),
                contentType = part.headers["Content-Type"]?.substringBefore(";"),
                bytes = part.body.readByteArray(),
            )
        }.toList()
    }

    private suspend inline fun <reified T : Throwable> expectThrows(crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        fail("expected ${T::class.simpleName}")
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
}
