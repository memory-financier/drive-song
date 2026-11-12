package com.drivesong.ui

import androidx.compose.ui.geometry.Offset
import com.drivesong.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RouteCoverTest {

    private fun assertNear(expected: Offset, actual: Offset) =
        assertTrue(abs(expected.x - actual.x) < 1e-4 && abs(expected.y - actual.y) < 1e-4, "expected $expected, was $actual")

    @Test
    fun `empty route draws nothing and a single point sits in the middle`() {
        assertEquals(emptyList<Offset>(), fitRoute(emptyList()))
        assertEquals(listOf(Offset(0.5f, 0.5f)), fitRoute(listOf(GeoPoint(37.5, 127.0))))
    }

    @Test
    fun `east-west route spans the width, is centred vertically, north is up`() {
        // 평균 위도 60.125도에서 경도 1도는 위도 약 0.4983도와 같은 거리
        val fitted = fitRoute(listOf(GeoPoint(60.0, 10.0), GeoPoint(60.25, 11.0)))

        val x = Math.cos(Math.toRadians(60.125))
        val margin = ((x - 0.25) / 2 / x).toFloat() // 세로로 남는 여백 (정사각형 기준)
        assertNear(Offset(0f, 1 - margin), fitted[0])
        assertNear(Offset(1f, margin), fitted[1]) // 북쪽(위도 큼)이 위
    }

    @Test
    fun `north-south route spans the height and keeps its aspect ratio`() {
        val fitted = fitRoute(listOf(GeoPoint(0.0, 0.0), GeoPoint(2.0, 0.0), GeoPoint(2.0, 1.0)))

        // 평균 위도 4/3도: 경도 1도 ≈ 0.99973 (적도 근처라 거의 1)
        val w = Math.cos(Math.toRadians(4.0 / 3)).toFloat() / 2
        assertNear(Offset(0.5f - w / 2, 1f), fitted[0])
        assertNear(Offset(0.5f - w / 2, 0f), fitted[1])
        assertNear(Offset(0.5f + w / 2, 0f), fitted[2])
    }
}
