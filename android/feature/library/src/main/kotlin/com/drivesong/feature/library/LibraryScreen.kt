package com.drivesong.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesong.core.data.db.SongEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 노래 보관함 (설계 §10). 정차 전용이라 주행 제한 중에는 앱 셸이 이 화면 대신 안내를 보여준다. */
@Composable
fun LibraryScreen(onPlay: () -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium)
        if (state.songs.isEmpty()) {
            Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.songs, key = { it.id }) { song ->
                SongCard(
                    song = song,
                    showLyrics = state.lyricsSongId == song.id,
                    onToggleLyrics = { viewModel.toggleLyrics(song.id) },
                    onPlay = {
                        viewModel.play(song.id)
                        onPlay()
                    },
                )
            }
        }
    }
}

@Composable
private fun SongCard(song: SongEntity, showLyrics: Boolean, onToggleLyrics: () -> Unit, onPlay: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge)
                    Text(formatDate(song.createdAtMs), style = MaterialTheme.typography.bodyMedium)
                    if (song.singers.isNotEmpty()) {
                        Text(stringResource(R.string.library_singers, song.singers.joinToString(" · ")), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.library_play), Modifier.size(40.dp))
                }
            }
            TextButton(onClick = onToggleLyrics, modifier = Modifier.height(72.dp)) {
                Text(stringResource(if (showLyrics) R.string.library_hide_lyrics else R.string.library_show_lyrics))
            }
            if (showLyrics) {
                Text(song.lyrics, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

private fun formatDate(epochMs: Long): String =
    DATE_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
