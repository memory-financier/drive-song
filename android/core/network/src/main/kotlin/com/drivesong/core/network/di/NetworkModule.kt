package com.drivesong.core.network.di

import com.drivesong.core.network.BuildConfig
import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.NetworkJson
import com.drivesong.core.network.auth.AuthInterceptor
import com.drivesong.core.network.auth.DataStoreDeviceTokenStore
import com.drivesong.core.network.auth.DeviceTokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun okHttpClient(authInterceptor: AuthInterceptor): OkHttpClient = buildOkHttpClient(authInterceptor)

    @Provides
    @Singleton
    fun driveSongApi(client: OkHttpClient): DriveSongApi = createDriveSongApi(BuildConfig.API_BASE_URL, client)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TokenStoreModule {
    @Binds
    abstract fun deviceTokenStore(impl: DataStoreDeviceTokenStore): DeviceTokenStore
}

/**
 * 서버는 처리가 끝날 때까지 응답 바이트를 보내지 않는다. `POST /v1/lyrics`(Claude, 서버 타임아웃 120초 × 재시도)와
 * `POST /v1/trips/enrich`(Nominatim 1 req/s × 8점, 8~12초)는 OkHttp 기본 10초를 넘기므로 읽기 타임아웃을 넉넉히 둔다.
 * 폴링 GET 은 즉시 응답하므로 긴 읽기 타임아웃이 해가 되지 않는다.
 */
internal fun buildOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(authInterceptor)
    .connectTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .callTimeout(6, TimeUnit.MINUTES) // 서버가 멈춰도 무한정 매달리지 않게
    .build()

internal fun createDriveSongApi(baseUrl: String, client: OkHttpClient): DriveSongApi = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(NetworkJson.asConverterFactory("application/json".toMediaType()))
    .build()
    .create()
