package com.drivesong.core.vehicle.light

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pleos Vehicle SDK `Light` 로 앰비언트 라이트를 바꾼다 (설계 §6.5).
 * Pleos Vehicle 서비스가 없는 기기(휴대폰 AVD)·권한 없음·기능 없음이면 아무것도 하지 않는다.
 * [확인 필요] 색 int 가 0xRRGGBB 인지, 에뮬레이터·실차에서 실제로 바뀌는지.
 */
@Singleton
class PleosAmbientLightController @Inject constructor(
    @ApplicationContext private val context: Context,
) : AmbientLightController {

    private var vehicle: Vehicle? = null

    /** 처음 바꾸기 전 영역별 상태. null = 바꾼 것 없음. */
    private var saved: Map<SeatArea, Saved>? = null

    private data class Saved(val switch: LightSwitch?, val color: Int?)

    override suspend fun apply(colorRgb: Int): Boolean = guarded(false) {
        val light = light() ?: return@guarded false
        val areas = retryUntilBound { light.colorAreas() }
        if (areas.isEmpty()) return@guarded false
        if (saved == null) {
            saved = areas.associateWith { area ->
                Saved(runCatchingCall { light.switchOf(area) }, runCatchingCall { light.colorOf(area) })
            }
        }
        var changed = false
        for (area in areas) {
            runCatchingCall { light.setSwitch(area, LightSwitch.ON) }
            if (runCatchingCall { light.setColor(area, colorRgb) } != null) changed = true
        }
        Log.i(TAG, "color #%06X on %d areas, changed=%b".format(colorRgb, areas.size, changed))
        changed
    }

    override suspend fun restore() = guarded(Unit) {
        val before = saved ?: return@guarded
        saved = null
        val light = light() ?: return@guarded
        for ((area, state) in before) {
            state.color?.let { runCatchingCall { light.setColor(area, it) } }
            state.switch?.let { runCatchingCall { light.setSwitch(area, it) } }
        }
        Log.i(TAG, "restored %d areas: %s".format(before.size, before.values.map { "%06X/%s".format(it.color ?: 0, it.switch) }))
    }

    /** 바인딩은 메인 스레드에서, 전체 호출은 [TIMEOUT_MS] 안에. 실패는 로그만 남긴다. */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        withContext(Dispatchers.Main) { withTimeout(TIMEOUT_MS) { block() } }
    } catch (e: CancellationException) {
        if (e is kotlinx.coroutines.TimeoutCancellationException) fallback.also { Log.w(TAG, "timed out") } else throw e
    } catch (e: Exception) {
        Log.w(TAG, "ambient light unavailable", e)
        fallback
    }

    private fun light(): Light? {
        vehicle?.let { return it.getLight() }
        val hasService = context.packageManager.queryIntentServices(Intent(VEHICLE_ACTION), 0).isNotEmpty()
        if (!hasService) return null
        return Vehicle(context).also {
            it.initialize()
            vehicle = it
        }.getLight()
    }

    /** initialize 직후에는 서비스 바인딩 전이라 실패할 수 있다 → 잠깐씩 다시 시도한다. */
    private suspend fun <T> retryUntilBound(call: suspend () -> T): T {
        repeat(BIND_RETRIES - 1) {
            try {
                return call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(BIND_RETRY_MS)
            }
        }
        return call()
    }

    private suspend fun <T> runCatchingCall(call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "light call failed", e)
        null
    }

    // --- SDK 콜백 → suspend ---

    private suspend fun Light.colorAreas(): List<SeatArea> = callback { ok, fail ->
        checkAmbientLightBrightnessColorCapabilities({ _, areas -> ok(areas) }, fail)
    }

    private suspend fun Light.switchOf(area: SeatArea): LightSwitch? = callback { ok, fail ->
        getAmbientLightSwitch(area, ok, fail)
    }

    private suspend fun Light.colorOf(area: SeatArea): Int? = callback { ok, fail ->
        getAmbientLightBrightnessColor(area, ok, fail)
    }

    /** 성공하면 Unit (콜백 값은 쓰지 않는다). */
    private suspend fun Light.setSwitch(area: SeatArea, value: LightSwitch): Unit = callback { ok, fail ->
        setAmbientLightSwitch(area, value, { _, _ -> ok(Unit) }, fail)
    }

    private suspend fun Light.setColor(area: SeatArea, colorRgb: Int): Unit = callback { ok, fail ->
        setAmbientLightBrightnessColor(area, colorRgb, { _, _ -> ok(Unit) }, fail)
    }

    private suspend fun <T> callback(start: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { cont ->
            start(
                { if (cont.isActive) cont.resume(it) },
                { if (cont.isActive) cont.resumeWithException(it) },
            )
        }

    private companion object {
        const val TAG = "AmbientLight"
        /** Vehicle SDK 가 바인딩하는 서비스 액션 (SDK AndroidManifest 의 queries). */
        const val VEHICLE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"
        const val TIMEOUT_MS = 3_000L
        const val BIND_RETRIES = 5
        const val BIND_RETRY_MS = 300L
    }
}
