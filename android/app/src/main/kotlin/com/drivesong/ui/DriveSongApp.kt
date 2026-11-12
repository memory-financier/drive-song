package com.drivesong.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.drivesong.R
import com.drivesong.feature.library.LibraryScreen
import com.drivesong.feature.voice.MembersScreen
import com.drivesong.feature.voice.PassengersScreen
import com.drivesong.ui.screens.HomeScreen
import com.drivesong.ui.screens.PlayerScreen
import com.drivesong.ui.screens.SettingsScreen
import com.drivesong.ui.theme.Night

/** 앱 셸: 내비게이션 레일 + 화면. 주행 제한 중에는 화면 전환 애니메이션을 끈다 (설계 §2). */
@Composable
fun DriveSongApp(
    restricted: Boolean,
    restrictionSimulation: Boolean,
    onRestrictionSimulationChange: (Boolean) -> Unit,
    conversation: ConversationBanner = ConversationBanner(),
    onTurnOffConversation: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    CompositionLocalProvider(LocalUxRestricted provides restricted) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.safeDrawingPadding()) {
                NavRail(currentRoute, onSelect = { navController.navigateTopLevel(it) })
                Column(Modifier.weight(1f)) {
                    if (conversation.on || restrictionSimulation) {
                        Column(
                            Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (conversation.on) ConversationBannerRow(conversation.recording, onTurnOffConversation)
                            if (restrictionSimulation) {
                                SimulationBanner(onTurnOff = { onRestrictionSimulationChange(false) })
                            }
                        }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = Destination.Home.route,
                        modifier = Modifier.weight(1f),
                        enterTransition = { if (restricted) EnterTransition.None else fadeIn() },
                        exitTransition = { if (restricted) ExitTransition.None else fadeOut() },
                    ) {
                        Destination.entries.forEach { destination ->
                            composable(destination.route) {
                                if (destination.parkedOnly) {
                                    ParkedOnlyGate {
                                        DestinationContent(destination, navController, restrictionSimulation, onRestrictionSimulationChange)
                                    }
                                } else {
                                    DestinationContent(destination, navController, restrictionSimulation, onRestrictionSimulationChange)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    navController: NavController,
    restrictionSimulation: Boolean,
    onRestrictionSimulationChange: (Boolean) -> Unit,
) {
    when (destination) {
        Destination.Home -> HomeScreen(
            onPlayRecent = { navController.navigateTopLevel(Destination.Player) },
            onChoosePassengers = { navController.navigateTopLevel(Destination.Passengers) },
        )
        Destination.Player -> PlayerScreen()
        Destination.Library -> LibraryScreen(onPlay = { navController.navigateTopLevel(Destination.Player) })
        Destination.Members -> MembersScreen()
        Destination.Passengers -> PassengersScreen(onManageMembers = { navController.navigateTopLevel(Destination.Members) })
        Destination.Settings -> SettingsScreen(restrictionSimulation, onRestrictionSimulationChange)
    }
}

/** 대화 담기 배너 상태 (설계 §8.3). */
data class ConversationBanner(val on: Boolean = false, val recording: Boolean = false)

/** 대화 담기가 켜져 있는 동안 모든 화면 위에 정적으로 (주행 중에도 한 번 터치로 끈다). */
@Composable
private fun ConversationBannerRow(recording: Boolean, onTurnOff: () -> Unit) {
    PillBanner(
        text = stringResource(if (recording) R.string.conversation_banner_recording else R.string.conversation_banner_armed),
        action = stringResource(R.string.conversation_turn_off),
        onAction = onTurnOff,
        container = Night.Recording,
        line = Night.RecordingLine,
        content = Night.RecordingText,
        dot = if (recording) Night.RecordingDot else null,
    )
}

/** 시뮬레이션 중에는 정차 전용인 설정 화면이 막히므로, 어느 화면에서든 끌 수 있게 한다. */
@Composable
private fun SimulationBanner(onTurnOff: () -> Unit) {
    PillBanner(
        text = stringResource(R.string.simulation_banner),
        action = stringResource(R.string.simulation_turn_off),
        onAction = onTurnOff,
        container = Night.Card,
        line = Night.Line,
        content = Night.Text,
        dot = null,
    )
}

/** 화면 위 알약 모양 배너 (시안 6). 끄기 버튼은 주행 중에도 한 번에 누를 수 있게 크게. */
@Composable
private fun PillBanner(text: String, action: String, onAction: () -> Unit, container: Color, line: Color, content: Color, dot: Color?) {
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .clip(CircleShape).background(container).border(1.dp, line, CircleShape)
            .padding(start = 28.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (dot != null) Box(Modifier.size(16.dp).clip(CircleShape).background(dot))
        Text(text, color = content, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = container),
            modifier = Modifier.height(56.dp),
        ) { Text(action, style = MaterialTheme.typography.labelMedium) }
    }
}

/** 왼쪽 내비게이션 (시안 6): 큰 아이콘·글자, 고른 화면은 남색 칸에 하늘색. */
@Composable
private fun NavRail(currentRoute: String?, onSelect: (Destination) -> Unit) {
    Column(
        Modifier.width(168.dp).fillMaxHeight().background(Night.Rail).padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Destination.entries.forEach { destination ->
            val selected = currentRoute == destination.route
            val color = if (selected) Night.Accent else Night.SubText
            Column(
                Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (selected) Night.Hero else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(destination.icon, contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(destination.label),
                    color = color,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun NavController.navigateTopLevel(destination: Destination) = navigate(destination.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
