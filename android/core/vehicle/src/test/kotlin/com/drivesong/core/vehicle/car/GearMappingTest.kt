package com.drivesong.core.vehicle.car

import android.car.VehicleGear
import com.drivesong.core.model.Gear
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GearMappingTest {
    @Test
    fun `vehicle gear values map to Gear`() {
        assertEquals(Gear.PARK, gearOf(VehicleGear.GEAR_PARK))
        assertEquals(Gear.REVERSE, gearOf(VehicleGear.GEAR_REVERSE))
        assertEquals(Gear.NEUTRAL, gearOf(VehicleGear.GEAR_NEUTRAL))
        assertEquals(Gear.UNKNOWN, gearOf(VehicleGear.GEAR_UNKNOWN))
        assertEquals(Gear.DRIVE, gearOf(VehicleGear.GEAR_DRIVE))
        assertEquals(Gear.DRIVE, gearOf(VehicleGear.GEAR_THIRD)) // 수동 단수
    }
}
