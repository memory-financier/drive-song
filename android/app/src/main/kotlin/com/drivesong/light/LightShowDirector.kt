package com.drivesong.light

import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.SongStyle
import com.drivesong.core.vehicle.light.AmbientLightController
import com.drivesong.core.vehicle.restriction.UxRestrictionsMonitor
import com.drivesong.feature.player.SongPlayer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 노래에 맞춘 실내 조명 연출 (설계 §6.5): 정차 중 + 재생 중 + 설정 켬 → 스타일 색, 그 밖에는 원래 조명.
 * 재생 상태는 SongPlayer 가 연결된 뒤부터 온다 (홈 화면·자동 재생이 연결한다). 여기서 따로 연결하지 않는다.
 */
class LightShowDirector @Inject constructor(
    private val player: SongPlayer,
    private val restrictions: UxRestrictionsMonitor,
    private val settings: SettingsRepository,
    private val light: AmbientLightController,
) {
    /** 앱 프로세스 동안 돈다. 끝나지 않는다. */
    suspend fun run() {
        combine(
            player.nowPlaying.map { it.isPlaying },
            restrictions.isRestricted,
            settings.ambientLightShow,
            settings.songStyle,
        ) { playing, restricted, enabled, style ->
            if (playing && !restricted && enabled) colorOf(style) else null
        }
            .distinctUntilChanged()
            .collect { color -> if (color != null) light.apply(color) else light.restore() }
    }

    companion object {
        /** 스타일별 조명 색 0xRRGGBB (설계 §6.5). */
        fun colorOf(style: SongStyle): Int = when (style) {
            SongStyle.KIDS -> 0xFFC83D
            SongStyle.CITY_POP -> 0xB84DFF
            SongStyle.ACOUSTIC -> 0xFF8A3D
            SongStyle.ROAD_TRIP -> 0x00AAD2
        }
    }
}
