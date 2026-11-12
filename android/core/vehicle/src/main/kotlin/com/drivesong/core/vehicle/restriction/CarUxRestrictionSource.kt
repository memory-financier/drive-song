package com.drivesong.core.vehicle.restriction

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * `CarUxRestrictionsManager` 래퍼 (설계 §2, §11-T6).
 * - 차량이 아닌 기기(폰 AVD 등): 주행 제한 없음(false).
 * - 차량인데 Car 서비스에 연결할 수 없으면: 안전을 위해 제한 상태(true)로 본다.
 */
class CarUxRestrictionSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : UxRestrictionSource {

    override val isRestricted: Flow<Boolean> =
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            flowOf(false)
        } else {
            carRestrictions().distinctUntilChanged()
        }

    private fun carRestrictions(): Flow<Boolean> = callbackFlow {
        val car = try {
            Car.createCar(context) // Android 10+ 에서는 연결될 때까지 블로킹 → flowOn(IO)
        } catch (e: Throwable) { // android.car 라이브러리가 없을 때 NoClassDefFoundError 포함
            Log.w(TAG, "Car service unavailable", e)
            null
        }
        val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        if (manager == null) {
            trySend(true)
            awaitClose { car?.disconnect() }
            return@callbackFlow
        }
        trySend(manager.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true)
        manager.registerListener { trySend(it.isRequiresDistractionOptimization) }
        awaitClose {
            manager.unregisterListener()
            car.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "CarUxRestriction"
    }
}
