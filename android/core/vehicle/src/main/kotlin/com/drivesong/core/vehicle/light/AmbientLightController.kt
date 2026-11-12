package com.drivesong.core.vehicle.light

/**
 * 실내 앰비언트 라이트 (설계 §6.5). 지원하지 않는 차량·서비스 없음·권한 없음이면 아무것도 하지 않는다.
 * 호출은 한 번에 하나씩 온다고 가정한다 (LightShowDirector 가 순서대로 부른다).
 */
interface AmbientLightController {
    /** 지금 상태를 기억하고 [colorRgb] (0xRRGGBB) 로 켠다. 하나라도 바뀌었으면 true. */
    suspend fun apply(colorRgb: Int): Boolean

    /** 처음 [apply] 하기 전 상태로 되돌린다. 바꾼 것이 없으면 아무것도 안 한다. */
    suspend fun restore()
}
