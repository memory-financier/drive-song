package com.drivesong.core.vehicle.demo

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 앱 전체가 주입받는 `java.time.Clock`.
 * 데모 시나리오가 재생 중이거나 끝난 뒤 정지 전까지는 시나리오 시각(마지막 프레임, 틱 단위로 진행),
 * 그 외에는 시스템 시각. 주행 기록 타임스탬프가 시나리오 시간을 따르게 하려고 쓴다.
 */
class ScenarioClock(
    private val player: ScenarioPlayer,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Clock() {
    override fun instant(): Instant = player.frame.value?.at ?: Instant.now()
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = ScenarioClock(player, zone)
}
