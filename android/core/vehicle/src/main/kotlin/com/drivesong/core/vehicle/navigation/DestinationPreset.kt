package com.drivesong.core.vehicle.navigation

import com.drivesong.core.model.GeoPoint

/** NaviHelper 대신 사용자가 고르는 목적지 (설계 §6.1 대체 경로, 텍스트 입력 없음 §2). */
data class DestinationPreset(val id: String, val name: String, val point: GeoPoint)

/** 좌표는 대표 지점의 근사값. */
object DestinationPresets {
    val all = listOf(
        DestinationPreset("gangneung_gyeongpo", "강릉 경포해변", GeoPoint(37.8040, 128.9060)),
        DestinationPreset("sokcho_beach", "속초해수욕장", GeoPoint(38.1903, 128.6006)),
        DestinationPreset("busan_haeundae", "부산 해운대", GeoPoint(35.1587, 129.1604)),
    )

    fun byId(id: String?): DestinationPreset? = all.firstOrNull { it.id == id }
}
