package com.drivesong.light

import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.SongStyle
import com.drivesong.core.vehicle.light.AmbientLightController
import com.drivesong.core.vehicle.restriction.UxRestrictionSource
import com.drivesong.core.vehicle.restriction.UxRestrictionsMonitor
import com.drivesong.feature.player.NowPlaying
import com.drivesong.ui.screens.FakeSongPlayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightShowDirectorTest {

    private val player = FakeSongPlayer()
    private val actualRestricted = MutableStateFlow(false)
    private val simulated = MutableStateFlow(false)
    private val enabled = MutableStateFlow(true)
    private val style = MutableStateFlow(SongStyle.ROAD_TRIP)
    private val settings = mockk<SettingsRepository> {
        every { simulateDrivingRestriction } returns simulated
        every { ambientLightShow } returns enabled
        every { songStyle } returns style
    }
    private val restrictions = UxRestrictionsMonitor(
        object : UxRestrictionSource {
            override val isRestricted = actualRestricted
        },
        settings,
    )
    private val light = RecordingLight()

    private fun TestScope.startDirector() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            LightShowDirector(player, restrictions, settings, light).run()
        }
    }

    private fun playing(isPlaying: Boolean) {
        player.nowPlaying.value = NowPlaying(songId = "s1", isPlaying = isPlaying)
    }

    @Test
    fun `playing while parked shows the style color and stopping restores`() = runTest {
        startDirector()
        playing(true)
        playing(false)
        assertEquals(listOf("restore", "apply 00AAD2", "restore"), light.calls)
    }

    @Test
    fun `nothing changes while driving, and driving off mid-song restores at once`() = runTest {
        actualRestricted.value = true
        startDirector()
        playing(true)
        assertEquals(listOf("restore"), light.calls) // 처음 상태 (바꾼 것 없음)

        actualRestricted.value = false // 정차
        actualRestricted.value = true // 다시 출발
        assertEquals(listOf("restore", "apply 00AAD2", "restore"), light.calls)
    }

    @Test
    fun `the simulated restriction switch also blocks the show`() = runTest {
        simulated.value = true
        startDirector()
        playing(true)
        assertEquals(listOf("restore"), light.calls)
    }

    @Test
    fun `turning the setting off restores, and a style change recolors`() = runTest {
        startDirector()
        playing(true)
        style.value = SongStyle.KIDS
        enabled.value = false
        assertEquals(listOf("restore", "apply 00AAD2", "apply FFC83D", "restore"), light.calls)
    }

    @Test
    fun `every style has its own color`() {
        assertEquals(SongStyle.entries.size, SongStyle.entries.map(LightShowDirector::colorOf).toSet().size)
    }

    private class RecordingLight : AmbientLightController {
        val calls = mutableListOf<String>()

        override suspend fun apply(colorRgb: Int): Boolean {
            calls += "apply %06X".format(colorRgb)
            return true
        }

        override suspend fun restore() {
            calls += "restore"
        }
    }
}
