package com.drivesong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesong.R
import com.drivesong.ui.AdaptiveColumns
import com.drivesong.ui.PauseIcon
import com.drivesong.ui.RouteCover
import com.drivesong.ui.SkipNextIcon
import com.drivesong.ui.TwoColumnMinWidth

/**
 * 재생 화면 (설계 §10). 주행 중에도 쓰므로 버튼은 크게, 화면은 정적으로 둔다
 * (진행 막대·애니메이션 없음, 설계 §2). 분할 화면에서는 1열.
 * 1열에서는 커버 높이를 제한해 제목·버튼 자리를 남긴다 (화면이 낮아도 주행 중 쓰는 조작이 밀려나지 않게).
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val song = state.song
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        val coverMax = if (maxWidth >= TwoColumnMinWidth) maxHeight else (maxHeight - ControlsHeight).coerceAtLeast(0.dp)
        AdaptiveColumns(
            first = { RouteCover(song?.waypoints.orEmpty(), Modifier.heightIn(max = coverMax).aspectRatio(1f)) },
            second = {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        song?.title ?: stringResource(R.string.player_no_song),
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2, // ControlsHeight 에 맞춤
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FilledIconButton(
                            onClick = viewModel::togglePlayPause,
                            enabled = song != null,
                            modifier = Modifier.size(96.dp),
                        ) {
                            Icon(
                                if (state.isPlaying) PauseIcon else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                                modifier = Modifier.size(56.dp),
                            )
                        }
                        FilledTonalIconButton(
                            onClick = viewModel::next,
                            enabled = state.hasNext,
                            modifier = Modifier.size(96.dp),
                        ) {
                            Icon(SkipNextIcon, contentDescription = stringResource(R.string.player_next), modifier = Modifier.size(56.dp))
                        }
                    }
                }
            },
        )
    }
}

/** 1열에서 커버 아래 남길 높이: 열 간격 16 + 제목 최대 2줄 ~72 + 간격 24 + 버튼 96 (여유 포함). */
private val ControlsHeight = 220.dp
