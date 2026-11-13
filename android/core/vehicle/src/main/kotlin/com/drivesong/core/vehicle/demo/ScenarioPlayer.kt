package com.drivesong.core.vehicle.demo

import com.drivesong.core.model.Gear
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import kotlin.math.min

/**
 * 시나리오를 가상 시계로 빠르게 재생한다 (설계 §6.4 데모 모드).
 * [tickMs] 마다(코루틴 delay) 시나리오 시간을 `tickMs × 배속` 만큼 진행해 [frame] 을 갱신한다.
 * 도착 [finaleSec] 전부터 도착까지는 [finaleSpeedUp] 으로 느리게 재생한다: ETA ≤ 10분 트리거(§7.1) 뒤
 * 가사·잡·폴링이 끝나 도착 전에 READY 가 되고, 도착 판정 때 자동 재생할 수 있게 (§11-T10).
 * 기본값(100ms 틱): 300배속(틱당 30초), 마지막 10분은 30배속(틱당 3초).
 * 서울→강릉: 10,231초 ÷ 300 + 600초 ÷ 30 + 정차 360초 ÷ 300 ≈ 55초 재생.
 * 대화 담기(설계 §7.5, T19)를 켜고 재생하면 마지막 10분을 [CONVERSATION_FINALE_SPEED_UP] 으로 더 느리게 재생한다:
 * 대화 노래는 분석·후보 곡 고르기·가족 목소리 변환까지 3.5~5.5분 걸린다 (실제 주행은 도착 10분 전에 시작하므로 충분하다).
 */
class ScenarioPlayer(
    private val speedUp: Double = DEMO_SPEED_UP,
    private val tickMs: Long = TICK_MS,
    private val finaleSec: Long = FINALE_SEC,
    private val finaleSpeedUp: Double = FINALE_SPEED_UP,
) {
    private val _frame = MutableStateFlow<ScenarioFrame?>(null)

    /** 현재 상태. 재생 전·[reset] 후에는 null. 재생이 끝나도 마지막 프레임(P단, 목적지)은 유지된다. */
    val frame: StateFlow<ScenarioFrame?> = _frame.asStateFlow()

    /** 처음부터 끝까지 재생하고 반환한다. [startAt] = 시나리오 출발 시각. [finale] = 도착 전 마지막 구간 배속. */
    suspend fun play(scenario: Scenario, startAt: Instant, finale: Double = finaleSpeedUp) {
        val total = scenario.totalSec.toDouble()
        var sec = 0.0
        while (true) {
            _frame.value = scenario.frameAt(sec, startAt)
            if (sec >= total) return
            delay(tickMs)
            val inFinale = sec < scenario.arrivalSec && scenario.arrivalSec - sec <= finaleSec
            sec = min(sec + tickMs * (if (inFinale) finale else speedUp) / 1000.0, total)
        }
    }

    /**
     * 재생을 중간에 멈출 때: 그 자리·그 시각에서 정차(P단, 0km/h)하고 안내를 끝낸 프레임으로 바꾼다.
     * 진행 중인 주행이 시나리오 시각으로 끝나게 하려고 [reset] 전에 부른다. 프레임이 없으면 아무것도 안 한다.
     */
    fun park() {
        _frame.value = _frame.value?.copy(speedKmh = 0f, gear = Gear.PARK, route = null)
    }

    fun reset() {
        _frame.value = null
    }

    companion object {
        const val DEMO_SPEED_UP = 300.0
        const val TICK_MS = 100L

        /** 느리게 재생하는 도착 전 구간 = ETA 트리거(10분). */
        const val FINALE_SEC = 600L
        const val FINALE_SPEED_UP = 30.0

        /**
         * 대화 담기 데모: 마지막 10분을 1.25배속으로 (트리거부터 도착까지 약 457초). 대화 노래 실측: 서버만 168초(T17), 에뮬레이터·화면 녹화와
         * GPU 를 함께 쓰면 트리거부터 READY 까지 218초(T19). 2026-11-13 에 후보 3곡 고르기와 변환 60단계를 넣은 뒤로는
         * 서버만 205~225초, 메모리가 빠듯한 PC 에서는 335초까지 걸려 2배속(300초)에서 늘렸다.
         */
        const val CONVERSATION_FINALE_SPEED_UP = 1.25
    }
}
