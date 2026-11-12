package com.drivesong.core.network.auth

import com.drivesong.core.network.DriveSongApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** 저장된 토큰을 돌려주고, 없으면 `POST /v1/devices` 로 한 번만 발급받아 저장한다 (설계 §9 인증). */
@Singleton
class DeviceTokenProvider @Inject constructor(
    private val store: DeviceTokenStore,
    private val api: Provider<DriveSongApi>, // Provider: api -> OkHttp -> AuthInterceptor -> 여기 순환을 끊는다
) {
    private val mutex = Mutex()

    suspend fun token(): String = store.get() ?: mutex.withLock {
        store.get() ?: register()
    }

    /** 서버가 [stale] 을 거부했을 때: 다른 요청이 이미 새로 받았으면 그것을, 아니면 다시 등록해 돌려준다. */
    suspend fun refresh(stale: String): String = mutex.withLock {
        store.get()?.takeIf { it != stale } ?: register()
    }

    private suspend fun register(): String = api.get().registerDevice().token.also { store.save(it) }
}
