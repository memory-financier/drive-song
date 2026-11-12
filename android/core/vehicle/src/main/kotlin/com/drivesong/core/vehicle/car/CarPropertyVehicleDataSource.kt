package com.drivesong.core.vehicle.car

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.drivesong.core.model.Gear
import com.drivesong.core.vehicle.VehicleDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 표준 AAOS `CarPropertyManager` 구현 (설계 §6.1 대체 경로). Pleos Vehicle SDK 구현은 SDK 확보 후 추가.
 * - 차속: PERF_VEHICLE_SPEED (m/s → km/h), 권한 CAR_SPEED
 * - 기어: GEAR_SELECTION, 권한 CAR_POWERTRAIN
 * 차량이 아닌 기기, Car 서비스 연결 실패, 권한 없음 → 값을 내보내지 않는다(로그만).
 */
class CarPropertyVehicleDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : VehicleDataSource {

    private val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    override val speedKmh: Flow<Float> =
        propertyValues(VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            .map { (it as Float) * MPS_TO_KMH }
            .distinctUntilChanged()

    override val gear: Flow<Gear> =
        propertyValues(VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            .map { gearOf(it as Int) }
            .distinctUntilChanged()

    private fun propertyValues(propertyId: Int, rate: Float): Flow<Any> {
        if (!automotive) return emptyFlow()
        return callbackFlow {
            val car = try {
                Car.createCar(context) // Android 10+ 에서는 연결될 때까지 블로킹 → flowOn(IO)
            } catch (e: Throwable) { // android.car 라이브러리가 없을 때 NoClassDefFoundError 포함
                Log.w(TAG, "Car service unavailable", e)
                null
            }
            val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    @Suppress("DEPRECATION") // getPropertyStatus() 는 API 34+
                    if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value?.let { trySend(it) }
                }

                override fun onErrorEvent(propId: Int, zone: Int) {
                    Log.w(TAG, "property $propId error (area $zone)")
                }
            }
            // subscribePropertyEvents 는 API 35+ → minSdk 29 에서는 registerCallback 사용
            @Suppress("DEPRECATION")
            val registered = try {
                manager?.registerCallback(callback, propertyId, rate) == true
            } catch (e: SecurityException) { // CAR_SPEED / CAR_POWERTRAIN 미허가
                Log.w(TAG, "no permission for property $propertyId", e)
                false
            }
            if (!registered) Log.w(TAG, "property $propertyId not available")
            awaitClose {
                @Suppress("DEPRECATION")
                if (registered) manager?.unregisterCallback(callback, propertyId)
                car?.disconnect()
            }
        }.flowOn(Dispatchers.IO)
    }

    private companion object {
        const val TAG = "CarPropertyVehicle"
        const val MPS_TO_KMH = 3.6f
    }
}

/** `VehicleGear` 값 → [Gear]. 수동 1~9단은 주행 중이므로 DRIVE. */
internal fun gearOf(value: Int): Gear = when (value) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE
}
