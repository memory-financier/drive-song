package com.drivesong.feature.trip

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 위치 소스는 GPS(FINE)만 쓰므로 대략적 위치(COARSE)만으로는 기록을 시작하지 않는다. */
@RunWith(RobolectricTestRunner::class)
class TripRecorderServicePermissionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `approximate location only is not enough to record`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(TripRecorderService.hasLocationPermission(app))
    }

    @Test
    fun `precise location starts recording`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(TripRecorderService.hasLocationPermission(app))
    }
}
