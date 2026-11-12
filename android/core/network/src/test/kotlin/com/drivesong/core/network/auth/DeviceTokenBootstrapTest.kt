package com.drivesong.core.network.auth

import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.di.buildOkHttpClient
import com.drivesong.core.network.di.createDriveSongApi
import com.drivesong.core.network.dto.MemberRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeviceTokenBootstrapTest {

    private val server = MockWebServer().apply { start() }
    private val store = InMemoryTokenStore()

    private lateinit var client: OkHttpClient

    private fun api(): DriveSongApi {
        lateinit var api: DriveSongApi
        client = buildOkHttpClient(AuthInterceptor(DeviceTokenProvider(store) { api }))
        api = createDriveSongApi(server.url("/").toString(), client)
        return api
    }

    private val memberJson = """{"memberId": "mem_1", "nickname": "아빠", "isChild": false,
        "voiceConsent": true, "guardianConsent": false, "createdAt": "2026-11-01T00:00:00+00:00"}"""

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `registers device once then sends bearer token on every request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"deviceId": "dev_1", "token": "tok_1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody(memberJson))
        server.enqueue(MockResponse().setResponseCode(201).setBody(memberJson))
        val api = api()
        val body = MemberRequest("아빠", isChild = false, voiceConsent = true, guardianConsent = false)

        api.createMember(body)
        api.createMember(body)

        val register = server.takeRequest()
        assertEquals("POST /v1/devices HTTP/1.1", register.requestLine)
        assertNull(register.getHeader("Authorization"))
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/v1/members", request.path)
            assertEquals("Bearer tok_1", request.getHeader("Authorization"))
        }
        assertEquals(3, server.requestCount)
        assertEquals("tok_1", store.get())
    }

    @Test
    fun `uses stored token without registering`() = runTest {
        store.save("saved")
        server.enqueue(MockResponse().setResponseCode(204))

        api().deleteClip("clip_1")

        val request = server.takeRequest()
        assertEquals("DELETE /v1/clips/clip_1 HTTP/1.1", request.requestLine)
        assertEquals("Bearer saved", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failed registration fails the call with IOException instead of crashing the dispatcher thread`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val uncaught = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } }
        try {
            val api = api()

            val error = runCatching { api.deleteClip("clip_1") }.exceptionOrNull()

            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
            assertTrue(error is IOException, "got $error")
            assertEquals(emptyList<Throwable>(), synchronized(uncaught) { uncaught.toList() })
            assertNull(store.get())
            assertEquals(1, server.requestCount)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `stale token gets 401 then re-registers once and retries with the new token`() = runTest {
        store.save("old")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"deviceId": "dev_2", "token": "new"}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        api().deleteClip("clip_1")

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("POST /v1/devices HTTP/1.1", server.takeRequest().requestLine)
        val retried = server.takeRequest()
        assertEquals("DELETE /v1/clips/clip_1 HTTP/1.1", retried.requestLine)
        assertEquals("Bearer new", retried.getHeader("Authorization"))
        assertEquals(3, server.requestCount)
        assertEquals("new", store.get())
    }

    @Test
    fun `second 401 after re-registration is returned, not retried again`() = runTest {
        store.save("old")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"deviceId": "dev_2", "token": "new"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { api().deleteClip("clip_1") }.exceptionOrNull()

        assertTrue(error is retrofit2.HttpException && error.code() == 401, "got $error")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `client timeouts allow the slow lyrics and enrich calls`() {
        api()
        // 서버의 Claude 호출은 120초 타임아웃, live enrich 는 8~12초 걸린다 (OkHttp 기본값 10초로는 실패).
        assertTrue(client.readTimeoutMillis >= 150_000, "read ${client.readTimeoutMillis}")
        assertTrue(client.callTimeoutMillis == 0 || client.callTimeoutMillis > client.readTimeoutMillis)
        assertTrue(client.connectTimeoutMillis in 1..30_000)
    }

    private class InMemoryTokenStore : DeviceTokenStore {
        @Volatile private var token: String? = null
        override suspend fun get() = token
        override suspend fun save(token: String) {
            this.token = token
        }
    }
}
