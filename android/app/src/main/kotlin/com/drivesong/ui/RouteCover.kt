package com.drivesong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.drivesong.core.model.GeoPoint
import kotlin.math.cos
import kotlin.math.max

/**
 * 정적 커버: 주행 경로를 지도처럼 그린 썸네일 (설계 §10 재생 화면). 지도 SDK·네트워크 없이 Canvas 로만 그린다.
 * 움직이는 요소가 없어 주행 중에도 그대로 둔다 (설계 §2).
 */
@Composable
fun RouteCover(waypoints: List<GeoPoint>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawRoundRect(colors.surfaceVariant, cornerRadius = CornerRadius(24.dp.toPx()))
        // 지도 느낌의 격자
        val grid = colors.outlineVariant
        for (i in 1 until GRID_LINES) {
            val f = i / GRID_LINES.toFloat()
            drawLine(grid, Offset(size.width * f, 0f), Offset(size.width * f, size.height), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = 1.dp.toPx())
        }
        val inset = size.minDimension * 0.15f
        val box = size.minDimension - 2 * inset
        val left = (size.width - box) / 2
        val top = (size.height - box) / 2
        val points = fitRoute(waypoints).map { Offset(left + it.x * box, top + it.y * box) }
        if (points.isEmpty()) return@Canvas
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, colors.primary, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(colors.tertiary, radius = 10.dp.toPx(), center = points.first())
        drawCircle(colors.primary, radius = 14.dp.toPx(), center = points.last())
    }
}

private const val GRID_LINES = 6

/**
 * 경로를 (0..1, 0..1) 정사각형 안에 비율을 지켜 가운데 맞춘다. 위쪽이 북쪽.
 * 경도 간격은 평균 위도의 cos 로 줄여 실제 모양에 가깝게 한다. 점이 하나뿐이거나 모두 같으면 가운데.
 */
internal fun fitRoute(points: List<GeoPoint>): List<Offset> {
    if (points.isEmpty()) return emptyList()
    val lngScale = cos(Math.toRadians(points.map { it.lat }.average()))
    val xs = points.map { it.lng * lngScale }
    val ys = points.map { it.lat }
    val width = xs.max() - xs.min()
    val height = ys.max() - ys.min()
    val span = max(width, height)
    if (span == 0.0) return points.map { Offset(0.5f, 0.5f) }
    val left = xs.min() - (span - width) / 2
    val bottom = ys.min() - (span - height) / 2
    return points.indices.map { i ->
        Offset(((xs[i] - left) / span).toFloat(), (1 - (ys[i] - bottom) / span).toFloat())
    }
}
