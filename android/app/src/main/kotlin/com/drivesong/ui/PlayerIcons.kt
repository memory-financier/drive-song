package com.drivesong.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// material-icons-core 에 없는 재생 아이콘 2개 (Material Icons 의 Pause, SkipNext 경로, Apache 2.0).
// 이 둘 때문에 큰 material-icons-extended 를 넣지 않는다.

val PauseIcon: ImageVector = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")

val SkipNextIcon: ImageVector = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")

private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
