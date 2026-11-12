package com.drivesong.core.vehicle.demo

import android.content.Context
import com.drivesong.core.data.conversation.ConversationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class DemoScenario(val assetPath: String) {
    SEOUL_GANGNEUNG("scenarios/seoul_gangneung.json"),
    NEIGHBORHOOD_MART("scenarios/neighborhood_mart.json"),
}

/**
 * 데모 주행 시작/정지 (설정 화면에서 호출). 모든 호출과 재생은 메인 스레드에서 한다
 * (틱당 프레임 계산 하나뿐이라 가볍고, 시작/정지 경쟁 상태가 없다).
 */
@Singleton
class DemoDriveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val player: ScenarioPlayer,
    private val conversation: ConversationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _playing = MutableStateFlow<DemoScenario?>(null)

    /** 재생 중인 시나리오. 끝나면 null 로 돌아가지만 마지막 프레임(도착·P단)과 시나리오 시계는 [stop] 전까지 유지된다. */
    val playing: StateFlow<DemoScenario?> = _playing.asStateFlow()

    fun start(demo: DemoScenario) {
        val scenario = Scenario.parse(context.assets.open(demo.assetPath).bufferedReader().use { it.readText() })
        val zone = ZoneId.systemDefault()
        val interrupted = endCurrent()
        _playing.value = demo
        job = scope.launch {
            if (interrupted) delay(PARK_HOLD_MS) // 앞 시나리오의 주행이 먼저 끝나게
            // 대화 담기를 켜고 재생하면 도착 전 10분을 느리게: 가족 목소리 노래가 도착 전에 완성되게 (T19)
            val finale = if (conversation.armed.value) ScenarioPlayer.CONVERSATION_FINALE_SPEED_UP else ScenarioPlayer.FINALE_SPEED_UP
            player.play(scenario, scenario.startInstant(LocalDate.now(zone), zone), finale)
            _playing.value = null
        }
    }

    /** 재생을 멈추고 Fake 데이터·시나리오 시계를 비운다 (시계는 시스템 시각으로 돌아감). */
    fun stop() {
        if (endCurrent()) {
            job = scope.launch {
                delay(PARK_HOLD_MS)
                player.reset()
            }
        }
    }

    /**
     * 재생 중이거나 끝난 시나리오가 있으면 멈추고 그 자리에서 정차·안내 종료 프레임을 낸다 ([ScenarioPlayer.park]).
     * 프레임을 바로 비우거나 다음 시나리오로 덮으면 주행 기록이 종료 신호를 못 받아 다음 시나리오가 같은 주행에
     * 이어 붙고, 시계도 바뀐 뒤라 종료 시각이 틀어진다. 그래서 [PARK_HOLD_MS] 동안 정차 프레임을 유지한다.
     * @return 정차 프레임을 냈으면 true
     */
    private fun endCurrent(): Boolean {
        job?.cancel()
        job = null
        _playing.value = null
        if (player.frame.value == null) return false
        player.park()
        return true
    }

    private companion object {
        /** 주행 기록(다른 스레드)이 정차 프레임을 받아 처리할 여유. */
        const val PARK_HOLD_MS = 1_000L
    }
}
