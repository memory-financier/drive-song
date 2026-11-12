package com.drivesong.core.network.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** 서버가 발급한 디바이스 토큰 저장소. */
interface DeviceTokenStore {
    suspend fun get(): String?
    suspend fun save(token: String)
}

private val Context.deviceTokenDataStore by preferencesDataStore(name = "device_token")

@Singleton
class DataStoreDeviceTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceTokenStore {

    override suspend fun get(): String? = context.deviceTokenDataStore.data.first()[TOKEN]

    override suspend fun save(token: String) {
        context.deviceTokenDataStore.edit { it[TOKEN] = token }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token")
    }
}
