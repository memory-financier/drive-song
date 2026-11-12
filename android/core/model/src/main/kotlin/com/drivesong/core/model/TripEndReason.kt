package com.drivesong.core.model

import kotlinx.serialization.Serializable

/** 주행 종료 사유 (설계 §6.2). */
@Serializable
enum class TripEndReason {
    /** P단 전환 후 5분 이상 정차. */
    PARKED,

    /** 경로 안내 종료 (안내 중이던 경로가 사라짐). */
    NAVIGATION_ENDED,

    /** 앱에서 주행 기록을 끔 (서비스 정지). */
    STOPPED,
}
