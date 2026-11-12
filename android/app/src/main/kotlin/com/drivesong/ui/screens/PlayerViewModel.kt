package com.drivesong.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.player.SongPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    /** 플레이어에 올라간 노래, 없으면 가장 최근 노래 (재생 버튼으로 바로 틀 수 있게). */
    val song: SongEntity? = null,
    val isPlaying: Boolean = false,
    val hasNext: Boolean = false,
)

/** 재생 화면 (설계 §10): 제목, 정적 커버, 재생/일시정지/다음. 주행 중에도 쓴다. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val player: SongPlayer,
) : ViewModel() {

    val uiState: StateFlow<PlayerUiState> = combine(player.nowPlaying, songRepository.songs) { now, songs ->
        val loaded = now.songId?.let { id -> songs.find { it.id == id } }
        if (loaded != null) PlayerUiState(loaded, now.isPlaying, now.hasNext) else PlayerUiState(songs.firstOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        player.connect()
    }

    /** 올라간 노래면 재생/일시정지, 아니면 (최근 노래) 보관함 전체를 목록으로 올려 그 노래부터 재생. */
    fun togglePlayPause() {
        val song = uiState.value.song ?: return
        if (player.nowPlaying.value.songId == song.id) {
            player.togglePlayPause()
        } else {
            viewModelScope.launch {
                val songs = songRepository.songs.first()
                val index = songs.indexOfFirst { it.id == song.id }
                if (index >= 0) player.play(songs, index)
            }
        }
    }

    fun next() = player.next()
}
