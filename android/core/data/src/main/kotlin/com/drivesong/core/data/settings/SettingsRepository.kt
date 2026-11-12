package com.drivesong.core.data.settings

import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.SongStyle
import kotlinx.coroutines.flow.Flow

/** 사용자 설정 (설계 §10 설정 화면). 항목은 이후 Task에서 추가한다. */
interface SettingsRepository {
    /** "주행 제한 시뮬레이션": 켜면 실제 차량 상태와 관계없이 주행 제한 상태로 동작 (테스트용). */
    val simulateDrivingRestriction: Flow<Boolean>

    suspend fun setSimulateDrivingRestriction(enabled: Boolean)

    /** "데모 모드": 켜면 차량·내비·위치 데이터를 시나리오 재생(Fake)으로 대신한다 (설계 §6.4). */
    val demoMode: Flow<Boolean>

    suspend fun setDemoMode(enabled: Boolean)

    /** NaviHelper 대신 쓰는 목적지 프리셋 id (설계 §6.1 대체 경로). null = 목적지 없음. */
    val destinationPresetId: Flow<String?>

    suspend fun setDestinationPresetId(id: String?)

    /** 이번 주행 탑승자(가족 구성원 id). 주행 시작 시 기록되고, 다음 주행의 기본값이 된다 (설계 §10). */
    val selectedPassengerIds: Flow<Set<String>>

    suspend fun setSelectedPassengerIds(ids: Set<String>)

    /** 주행마다 가족 클립을 고르는 방식 (설계 §8: 랜덤 또는 순서대로). 기본값 RANDOM. */
    val clipPlanMode: Flow<ClipPlanMode>

    suspend fun setClipPlanMode(mode: ClipPlanMode)

    /** 노래 스타일 프리셋 (설계 §7.3, §10). 기본값 CITY_POP (설계 §9 예시 값). */
    val songStyle: Flow<SongStyle>

    suspend fun setSongStyle(style: SongStyle)

    /** 도착하면 완성된 노래를 자동 재생 (설계 §7.1, §10). 기본값 켜짐. */
    val autoPlay: Flow<Boolean>

    suspend fun setAutoPlay(enabled: Boolean)

    /** 노래 재생 중(정차 시) 실내 앰비언트 라이트를 노래 스타일 색으로 바꾼다 (설계 §6.5). 기본값 켜짐. */
    val ambientLightShow: Flow<Boolean>

    suspend fun setAmbientLightShow(enabled: Boolean)
}
