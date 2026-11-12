package com.drivesong.core.network.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject

/**
 * 디바이스 등록 요청을 제외한 모든 요청에 `Authorization: Bearer <token>` 을 붙인다.
 * 401 이면 (서버 DB 초기화 등으로 토큰이 무효) 한 번만 재등록하고 다시 보낸다.
 */
class AuthInterceptor @Inject constructor(
    private val tokenProvider: DeviceTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isDeviceRegistration()) return chain.proceed(request)
        val token = fetchToken { tokenProvider.token() }
        val response = chain.proceed(request.withBearer(token))
        if (response.code != 401) return response
        response.close()
        val fresh = fetchToken { tokenProvider.refresh(stale = token) }
        return chain.proceed(request.withBearer(fresh))
    }

    /**
     * OkHttp 작업 스레드에서 실행되므로 블로킹 대기가 허용된다. 인터셉터에서 IOException 이 아닌 예외가 나가면
     * OkHttp 가 Dispatcher 스레드에서 다시 던져 프로세스가 죽으므로 (HttpException, SerializationException 등)
     * 모두 IOException 으로 감싼다.
     */
    private fun fetchToken(block: suspend () -> String): String = try {
        runBlocking { block() }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("device registration failed", e)
    }

    private fun Request.withBearer(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    private fun Request.isDeviceRegistration() =
        method == "POST" && url.encodedPath.endsWith("/v1/devices")
}
