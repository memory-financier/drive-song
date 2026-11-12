package com.drivesong.core.vehicle.demo

import app.cash.turbine.test
import com.drivesong.core.model.Gear
import com.drivesong.core.vehicle.VehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DemoSwitchingTest {

    private class StubVehicle(speed: Float) : VehicleDataSource {
        override val speedKmh = MutableStateFlow(speed)
        override val gear = MutableStateFlow(Gear.PARK)
    }

    @Test
    fun `toggling demo mode switches active collectors between real and fake`() = runTest {
        val demoMode = MutableStateFlow(false)
        val real = StubVehicle(0f)
        val fake = StubVehicle(100f)
        var fakeCreated = false
        val source = DemoSwitchingVehicleDataSource(demoMode, { real }, { fakeCreated = true; fake })

        source.speedKmh.test {
            assertEquals(0f, awaitItem())
            assertEquals(false, fakeCreated) // Fake 는 필요할 때까지 만들지 않는다
            demoMode.value = true
            assertEquals(100f, awaitItem())
            fake.speedKmh.value = 80f
            assertEquals(80f, awaitItem())
            demoMode.value = false
            assertEquals(0f, awaitItem())
        }
    }
}
