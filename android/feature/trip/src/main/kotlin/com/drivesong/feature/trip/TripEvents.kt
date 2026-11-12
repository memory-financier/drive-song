package com.drivesong.feature.trip

import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.model.TripSummary
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** SongOrchestrator(T10)·UI 가 구독하는 주행 기록 API. 구현: [TripRecorder]. */
interface TripEvents {
    val state: StateFlow<TripState>

    /** 구독 중일 때만 받는다 (replay 없음). 놓치면 안 되는 쪽은 앱 시작 시 미리 구독할 것. */
    val events: SharedFlow<TripEvent>

    /** 진행 중인 주행의 요약 (endedAt = null, ETA 트리거용). 주행 중이 아니거나 위치가 아직 없으면 null. */
    suspend fun currentSummary(): TripSummary?
}

sealed interface TripState {
    /** 주행 기록 서비스가 꺼져 있음. */
    data object Off : TripState

    /** 서비스는 켜져 있고 출발(P단 해제 또는 주행)을 기다림. */
    data object Idle : TripState

    data class Recording(val tripId: String, val startedAt: Instant, val route: RouteInfo?) : TripState
}

sealed interface TripEvent {
    val tripId: String

    data class Started(override val tripId: String, val startedAt: Instant) : TripEvent

    /** 경로 정보(목적지·ETA·남은 거리)가 바뀜. [at] = 받은 시각 (ETA 까지 남은 시간 계산용). */
    data class EtaUpdated(override val tripId: String, val route: RouteInfo, val at: Instant) : TripEvent

    /**
     * P단 전환 (도착 판정, 설계 §7.1). 주행 중 P단으로 바뀔 때마다 온다 (휴게소 정차 포함).
     * 경로 안내 종료로 주행이 먼저 끝난 경우에는 그 뒤 첫 P단 전환 때 그 주행 id 로 한 번 온다.
     */
    data class Arrived(override val tripId: String, val at: Instant) : TripEvent

    /** 주행 종료. [summary] 는 DB 에 저장된 요약 (위치를 하나도 못 받았으면 null). */
    data class Ended(
        override val tripId: String,
        val reason: TripEndReason,
        val endedAt: Instant,
        val summary: TripSummary?,
    ) : TripEvent
}
