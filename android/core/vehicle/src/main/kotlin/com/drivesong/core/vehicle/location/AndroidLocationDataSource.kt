package com.drivesong.core.vehicle.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.vehicle.LocationDataSource
import com.drivesong.core.vehicle.TimedFix
import com.drivesong.core.vehicle.throttle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Android `LocationManager`(GPS) 구현 (설계 §6.1: Fused Location SDK 는 "준비 중" → 대체 경로로 시작).
 * GPS 를 최대 [BASE_SAMPLING_MS] 간격으로 받아 "간격 OR 거리" 규칙으로 거른다.
 * 위치 권한이 없거나 GPS 제공자가 없으면 값을 내보내지 않는다(로그만). 권한 요청은 호출하는 쪽 책임.
 */
class AndroidLocationDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationDataSource {

    override fun locations(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        // API 29 에서는 LocationListener 의 나머지 메서드가 abstract → 람다 대신 모두 구현한다.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val elapsedMs = location.elapsedRealtimeNanos / 1_000_000
                trySend(TimedFix(elapsedMs, GeoPoint(location.latitude, location.longitude)))
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) Log.w(TAG, "ACCESS_FINE_LOCATION not granted")
        val requested = granted && try {
            // [확인 필요] Pleos Connect 에뮬레이터/실차의 GPS 제공자 유무
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minOf(intervalMs, BASE_SAMPLING_MS),
                0f,
                listener,
                Looper.getMainLooper(),
            )
            true
        } catch (e: IllegalArgumentException) { // GPS 제공자 없음
            Log.w(TAG, "GPS provider unavailable", e)
            false
        }
        awaitClose { if (requested) manager.removeUpdates(listener) }
    }.throttle(intervalMs, minDistanceM)

    private companion object {
        const val TAG = "AndroidLocation"

        /** 거리 조건(예: 500m)을 놓치지 않을 만큼만 자주 받는다. 100km/h 에서 5초 ≈ 140m. */
        const val BASE_SAMPLING_MS = 5_000L
    }
}
