package com.drivesong.core.network

import com.drivesong.core.network.auth.AuthInterceptor
import com.drivesong.core.network.auth.DeviceTokenProvider
import com.drivesong.core.network.auth.DeviceTokenStore
import com.drivesong.core.network.di.buildOkHttpClient
import com.drivesong.core.network.di.createDriveSongApi
import com.drivesong.core.network.dto.MemberConsentUpdate
import com.drivesong.core.network.dto.SongJobRequest
import com.drivesong.core.network.dto.SongJobStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveSongApiTest {

    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `deleteAllSongs sends an authorized DELETE v1 songs and accepts 204`() = runTest {
        val store = object : DeviceTokenStore {
            override suspend fun get() = "tok_1"
            override suspend fun save(token: String) = Unit
        }
        lateinit var api: DriveSongApi
        api = createDriveSongApi(server.url("/").toString(), buildOkHttpClient(AuthInterceptor(DeviceTokenProvider(store) { api })))
        server.enqueue(MockResponse().setResponseCode(204))

        api.deleteAllSongs()

        val request = server.takeRequest()
        assertEquals("DELETE /v1/songs HTTP/1.1", request.requestLine)
        assertEquals("Bearer tok_1", request.getHeader("Authorization"))
    }
}

class ConversationApiTest {

    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun api(): DriveSongApi {
        val store = object : DeviceTokenStore {
            override suspend fun get() = "tok_1"
            override suspend fun save(token: String) = Unit
        }
        lateinit var api: DriveSongApi
        api = createDriveSongApi(server.url("/").toString(), buildOkHttpClient(AuthInterceptor(DeviceTokenProvider(store) { api })))
        return api
    }

    @Test
    fun `conversation chunk upload repeats the passengers field`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"tripId":"trip_1","seq":2,"durationSec":300.0}"""))
        val text = "text/plain".toMediaType()
        val chunk = api().uploadConversation(
            tripId = "trip_1",
            seq = "2".toRequestBody(text),
            passengers = listOf("mem_a", "mem_b").map { MultipartBody.Part.createFormData("passengers", it) },
            audio = MultipartBody.Part.createFormData("audio", "2.m4a", byteArrayOf(1, 2).toRequestBody("audio/mp4".toMediaType())),
        )
        assertEquals(2, chunk.seq)
        val request = server.takeRequest()
        assertEquals("POST /v1/trips/trip_1/conversation HTTP/1.1", request.requestLine)
        val body = request.body.readUtf8()
        assertEquals(2, Regex("name=\"passengers\"").findAll(body).count())
        assertTrue(body.contains("mem_a") && body.contains("mem_b") && body.contains("name=\"seq\""))
    }

    @Test
    fun `consent patch, conversation delete and conversation job body`() = runTest {
        val api = api()
        server.enqueue(MockResponse().setBody(
            """{"memberId":"mem_a","nickname":"엄마","isChild":false,"voiceConsent":true,"guardianConsent":false,""" +
                """"createdAt":"2026-11-04T00:00:00Z","conversationConsent":true,"voiceConversionConsent":false}""",
        ))
        val member = api.updateMember("mem_a", MemberConsentUpdate(conversationConsent = true, voiceConversionConsent = false))
        assertTrue(member.conversationConsent)
        val patch = server.takeRequest()
        assertEquals("PATCH /v1/members/mem_a HTTP/1.1", patch.requestLine)
        assertEquals("""{"conversationConsent":true,"voiceConversionConsent":false}""", patch.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteConversation("trip_1")
        assertEquals("DELETE /v1/trips/trip_1/conversation HTTP/1.1", server.takeRequest().requestLine)

        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"jobId":"job_1","status":"QUEUED"}"""))
        api.createSongJob(SongJobRequest(tripId = "trip_1", style = "kids", durationSec = 75, clipPlan = emptyList(), conversation = true))
        val job = NetworkJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("tripId", "style", "durationSec", "clipPlan", "conversation"), job.keys) // 가사·제목은 비우면 보내지 않는다

        // 새 상태 값과 응답 필드를 읽는다
        server.enqueue(MockResponse().setBody(
            """{"jobId":"job_1","status":"CONVERTING","lyrics":"[verse]","singers":["mem_a"]}""",
        ))
        val status = api.getSongJob("job_1")
        assertEquals(SongJobStatus.CONVERTING, status.status)
        assertEquals(listOf("mem_a"), status.singers)
    }
}
