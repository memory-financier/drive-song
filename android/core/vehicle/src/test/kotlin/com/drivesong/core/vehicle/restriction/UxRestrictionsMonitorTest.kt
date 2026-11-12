package com.drivesong.core.vehicle.restriction

import app.cash.turbine.test
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.SongStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UxRestrictionsMonitorTest {

    private val source = FakeSource()
    private val settings = FakeSettings()
    private val monitor = UxRestrictionsMonitor(source, settings)

    @Test
    fun `follows the actual restriction state`() = runTest {
        monitor.isRestricted.test {
            assertEquals(false, awaitItem())
            source.isRestricted.value = true
            assertEquals(true, awaitItem())
            source.isRestricted.value = false
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `simulation forces restricted while parked`() = runTest {
        monitor.isRestricted.test {
            assertEquals(false, awaitItem())
            settings.setSimulateDrivingRestriction(true)
            assertEquals(true, awaitItem())
            settings.setSimulateDrivingRestriction(false)
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `turning simulation off while actually driving stays restricted without duplicate emissions`() = runTest {
        source.isRestricted.value = true
        settings.setSimulateDrivingRestriction(true)
        monitor.isRestricted.test {
            assertEquals(true, awaitItem())
            settings.setSimulateDrivingRestriction(false)
            source.isRestricted.value = false
            assertEquals(false, awaitItem())
            expectNoEvents()
        }
    }

    private class FakeSource : UxRestrictionSource {
        override val isRestricted = MutableStateFlow(false)
    }

    private class FakeSettings : SettingsRepository {
        override val simulateDrivingRestriction = MutableStateFlow(false)
        override suspend fun setSimulateDrivingRestriction(enabled: Boolean) {
            simulateDrivingRestriction.value = enabled
        }
        override val demoMode = MutableStateFlow(false)
        override suspend fun setDemoMode(enabled: Boolean) = Unit
        override val destinationPresetId = MutableStateFlow<String?>(null)
        override suspend fun setDestinationPresetId(id: String?) = Unit
        override val selectedPassengerIds = MutableStateFlow(emptySet<String>())
        override suspend fun setSelectedPassengerIds(ids: Set<String>) = Unit
        override val clipPlanMode = MutableStateFlow(ClipPlanMode.RANDOM)
        override suspend fun setClipPlanMode(mode: ClipPlanMode) = Unit
        override val songStyle = MutableStateFlow(SongStyle.CITY_POP)
        override suspend fun setSongStyle(style: SongStyle) = Unit
        override val autoPlay = MutableStateFlow(true)
        override suspend fun setAutoPlay(enabled: Boolean) = Unit
        override val ambientLightShow = MutableStateFlow(true)
        override suspend fun setAmbientLightShow(enabled: Boolean) = Unit
    }
}
