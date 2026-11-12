package com.drivesong.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI 디자인 (2026-11-11, 사용자 선택: 시안 5 '가족 앨범' 구성 + 시안 2 '나이트 드라이브' 색).
 * 현대차 홈페이지 분위기의 네이비 위에 하늘색 강조, 차 화면에 맞춰 늘 다크.
 * Pleos 화면(2560×1440, 160dpi → 1dp = 1px, 앱 영역 약 1700×1200)에 맞춰 글자를 크게 한다.
 * [확인 필요] Pleos Connect UI Design Guide 의 최소 글자·터치 크기.
 */
object Night {
    val Background = Color(0xFF0A1628)
    val Rail = Color(0xFF07101E)
    val Card = Color(0xFF12233A)
    val Hero = Color(0xFF13294A)
    val HeroArt = Color(0xFF0E2140)
    val Line = Color(0xFF1E3654)
    val Text = Color(0xFFFFFFFF)
    val SubText = Color(0xFFA9B8CC)
    val Accent = Color(0xFF36C2E8)
    val Recording = Color(0xFF2A1520)
    val RecordingLine = Color(0xFF6B2B3A)
    val RecordingText = Color(0xFFFFD9D9)
    val RecordingDot = Color(0xFFFF7A7A)
}

private val NightColors = darkColorScheme(
    primary = Night.Accent,
    onPrimary = Night.Background,
    primaryContainer = Night.Hero,
    onPrimaryContainer = Night.Text,
    secondary = Night.SubText,
    onSecondary = Night.Background,
    secondaryContainer = Night.Hero,
    onSecondaryContainer = Night.Text,
    tertiary = Night.Text,
    onTertiary = Night.Background,
    tertiaryContainer = Night.Hero,
    onTertiaryContainer = Night.Text,
    background = Night.Background,
    onBackground = Night.Text,
    surface = Night.Background,
    onSurface = Night.Text,
    surfaceVariant = Night.HeroArt,
    onSurfaceVariant = Night.SubText,
    surfaceContainerLowest = Night.Rail,
    surfaceContainerLow = Night.Card,
    surfaceContainer = Night.Card,
    surfaceContainerHigh = Night.Hero,
    surfaceContainerHighest = Night.Hero,
    outline = Night.Line,
    outlineVariant = Night.Line,
    error = Night.RecordingDot,
    onError = Night.Recording,
    errorContainer = Night.Recording,
    onErrorContainer = Night.RecordingText,
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.3f) =
    TextStyle(fontSize = size.sp, lineHeight = (size * line).sp, fontWeight = weight)

/** 앱 영역 1700dp 기준 (기본 Material 크기의 약 1.6배). */
private val DriveSongTypography = Typography(
    displaySmall = style(56, FontWeight.Bold, 1.2f),
    headlineLarge = style(48, FontWeight.Bold, 1.2f),
    headlineMedium = style(40, FontWeight.Bold, 1.25f),
    headlineSmall = style(32, FontWeight.Bold),
    titleLarge = style(30, FontWeight.Bold),
    titleMedium = style(26, FontWeight.Medium),
    titleSmall = style(24, FontWeight.Medium),
    bodyLarge = style(26),
    bodyMedium = style(22),
    bodySmall = style(20),
    labelLarge = style(26, FontWeight.Bold),
    labelMedium = style(22, FontWeight.Medium),
    labelSmall = style(20, FontWeight.Medium),
)

private val DriveSongShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun DriveSongTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColors,
        typography = DriveSongTypography,
        shapes = DriveSongShapes,
        content = content,
    )
}
