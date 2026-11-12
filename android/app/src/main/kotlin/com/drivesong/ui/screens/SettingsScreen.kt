package com.drivesong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesong.R
import com.drivesong.core.model.SongStyle
import com.drivesong.core.vehicle.demo.DemoScenario
import com.drivesong.core.vehicle.navigation.DestinationPresets

/** 설정 (설계 §10, 정차 전용). */
@Composable
fun SettingsScreen(
    restrictionSimulation: Boolean,
    onRestrictionSimulationChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val playing by viewModel.playingScenario.collectAsStateWithLifecycle()
    val destinationId by viewModel.destinationPresetId.collectAsStateWithLifecycle()
    val songStyle by viewModel.songStyle.collectAsStateWithLifecycle()
    val autoPlay by viewModel.autoPlay.collectAsStateWithLifecycle()
    val ambientLightShow by viewModel.ambientLightShow.collectAsStateWithLifecycle()
    val deleteStatus by viewModel.deleteStatus.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)
        SongStyleRow(songStyle, viewModel::setSongStyle)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_autoplay_title)) },
            supportingContent = { Text(stringResource(R.string.settings_autoplay_description)) },
            trailingContent = { Switch(checked = autoPlay, onCheckedChange = viewModel::setAutoPlay) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_light_title)) },
            supportingContent = { Text(stringResource(R.string.settings_light_description)) },
            trailingContent = { Switch(checked = ambientLightShow, onCheckedChange = viewModel::setAmbientLightShow) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_simulation_title)) },
            supportingContent = { Text(stringResource(R.string.settings_simulation_description)) },
            trailingContent = {
                Switch(checked = restrictionSimulation, onCheckedChange = onRestrictionSimulationChange)
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_demo_title)) },
            supportingContent = { Text(stringResource(R.string.settings_demo_description)) },
            trailingContent = { Switch(checked = demoMode, onCheckedChange = viewModel::setDemoMode) },
        )
        if (demoMode) {
            DemoScenario.entries.forEach { scenario ->
                ListItem(
                    headlineContent = { Text(stringResource(scenario.label)) },
                    supportingContent = {
                        if (playing == scenario) Text(stringResource(R.string.settings_demo_playing))
                    },
                    trailingContent = {
                        if (playing == scenario) {
                            OutlinedButton(onClick = viewModel::stop) { Text(stringResource(R.string.settings_demo_stop)) }
                        } else {
                            Button(onClick = { viewModel.play(scenario) }) { Text(stringResource(R.string.settings_demo_play)) }
                        }
                    },
                )
            }
            val demoFamilyStatus by viewModel.demoFamilyStatus.collectAsStateWithLifecycle()
            DemoFamilyRow(demoFamilyStatus, viewModel::loadDemoFamily)
        }
        DestinationPresetRow(destinationId, viewModel::setDestinationPreset)
        DeleteAllRow(deleteStatus, viewModel::deleteAllData)
    }
}

/** 데모 가족 (T19): 같은 호칭의 가족을 지우고 새로 만들므로 한 번 더 묻는다. */
@Composable
private fun DemoFamilyRow(status: DeleteStatus, onLoad: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_demo_family_title)) },
        supportingContent = {
            Text(
                stringResource(
                    when (status) {
                        DeleteStatus.DELETING -> R.string.settings_demo_family_loading
                        DeleteStatus.DONE -> R.string.settings_demo_family_done
                        DeleteStatus.FAILED -> R.string.settings_demo_family_failed
                        DeleteStatus.IDLE -> R.string.settings_demo_family_description
                    },
                ),
            )
        },
        trailingContent = {
            OutlinedButton(onClick = { confirming = true }, enabled = status != DeleteStatus.DELETING) {
                Text(stringResource(R.string.settings_demo_family_load))
            }
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_demo_family_title)) },
            text = { Text(stringResource(R.string.settings_demo_family_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onLoad()
                }) { Text(stringResource(R.string.settings_demo_family_load)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_delete_cancel)) } },
        )
    }
}

/** 모든 데이터 삭제 (설계 §8.1, §10). 되돌릴 수 없으므로 한 번 더 묻는다. */
@Composable
private fun DeleteAllRow(status: DeleteStatus, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_delete_title)) },
        supportingContent = {
            val message = when (status) {
                DeleteStatus.IDLE -> R.string.settings_delete_description
                DeleteStatus.DELETING -> R.string.settings_delete_running
                DeleteStatus.DONE -> R.string.settings_delete_done
                DeleteStatus.FAILED -> R.string.settings_delete_failed
            }
            Text(stringResource(message))
        },
        trailingContent = {
            OutlinedButton(onClick = { confirming = true }, enabled = status != DeleteStatus.DELETING) {
                Text(stringResource(R.string.settings_delete_button))
            }
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text(stringResource(R.string.settings_delete_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_delete_cancel)) }
            },
        )
    }
}

/** NaviHelper 대신 쓰는 목적지 프리셋 (설계 §6.1 대체 경로). 실제 주행(데모 모드 꺼짐)에서만 쓰인다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DestinationPresetRow(selectedId: String?, onSelect: (String?) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_destination_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_destination_description))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedId == null,
                        onClick = { onSelect(null) },
                        label = { Text(stringResource(R.string.settings_destination_none)) },
                    )
                    DestinationPresets.all.forEach { preset ->
                        FilterChip(
                            selected = selectedId == preset.id,
                            onClick = { onSelect(preset.id) },
                            label = { Text(preset.name) },
                        )
                    }
                }
            }
        },
    )
}

/** 노래 스타일 프리셋 (설계 §7.3). 사용자는 프리셋만 고른다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongStyleRow(selected: SongStyle, onSelect: (SongStyle) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_style_title)) },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SongStyle.entries.forEach { style ->
                    FilterChip(
                        selected = selected == style,
                        onClick = { onSelect(style) },
                        label = { Text(stringResource(style.label)) },
                    )
                }
            }
        },
    )
}

private val SongStyle.label: Int
    get() = when (this) {
        SongStyle.KIDS -> R.string.style_kids
        SongStyle.CITY_POP -> R.string.style_city_pop
        SongStyle.ACOUSTIC -> R.string.style_acoustic
        SongStyle.ROAD_TRIP -> R.string.style_road_trip
    }

private val DemoScenario.label: Int
    get() = when (this) {
        DemoScenario.SEOUL_GANGNEUNG -> R.string.demo_scenario_seoul_gangneung
        DemoScenario.NEIGHBORHOOD_MART -> R.string.demo_scenario_neighborhood_mart
    }
