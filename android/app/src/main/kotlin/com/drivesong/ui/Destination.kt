package com.drivesong.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.drivesong.R

/** 설계 §10 화면 목록. [parkedOnly] 화면은 주행 제한 중 "정차 후 이용할 수 있어요" 만 표시한다. */
enum class Destination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val parkedOnly: Boolean,
) {
    Home("home", R.string.nav_home, Icons.Filled.Home, parkedOnly = false),
    Player("player", R.string.nav_player, Icons.Filled.PlayArrow, parkedOnly = false),
    Library("library", R.string.nav_library, Icons.AutoMirrored.Filled.List, parkedOnly = true),
    Members("members", R.string.nav_members, Icons.Filled.Person, parkedOnly = true),
    Passengers("passengers", R.string.nav_passengers, Icons.Filled.Face, parkedOnly = true),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings, parkedOnly = true),
}
