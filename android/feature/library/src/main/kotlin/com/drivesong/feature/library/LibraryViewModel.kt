package com.drivesong.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.player.SongPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    /** 최근 노래부터. */
    val songs: List<SongEntity> = emptyList(),
    /** 가사를 펼친 노래. */
    val lyricsSongId: String? = null,
)

/** 노래 보관함 (설계 §10, 정차 전용): 날짜·제목 목록, 가사 보기, 재생. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val player: SongPlayer,
) : ViewModel() {

    private val lyricsSongId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LibraryUiState> = combine(songRepository.songs, lyricsSongId) { songs, lyricsId ->
        LibraryUiState(songs, lyricsId?.takeIf { id -> songs.any { it.id == id } })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun toggleLyrics(songId: String) = lyricsSongId.update { if (it == songId) null else songId }

    /** 보관함 전체를 재생 목록으로 올려 이 노래부터 재생한다 ("다음" 은 그다음 노래). */
    fun play(songId: String) {
        viewModelScope.launch {
            val songs = songRepository.songs.first()
            val index = songs.indexOfFirst { it.id == songId }
            if (index >= 0) player.play(songs, index)
        }
    }
}
