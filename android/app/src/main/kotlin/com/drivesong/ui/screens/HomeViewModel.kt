package com.drivesong.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.player.SongPlayer
import com.drivesong.feature.song.SongOrchestrator
import com.drivesong.feature.song.SongState
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripState
import com.drivesong.feature.voice.command.VoiceCommandHandler
import com.drivesong.feature.voice.command.VoiceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 홈 (설계 §10): 진행 상태 카드(주행 기록 + 노래 생성 상태) + 최근 노래 1곡 재생. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    tripEvents: TripEvents,
    songOrchestrator: SongOrchestrator,
    private val songRepository: SongRepository,
    private val player: SongPlayer,
    private val voiceCommands: VoiceCommandHandler,
) : ViewModel() {
    val tripState: StateFlow<TripState> = tripEvents.state
    val songState: StateFlow<SongState> = songOrchestrator.state

    val recentSong: StateFlow<SongEntity?> = songRepository.songs.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    /** 홈 '우리 가족 노래 앨범': 최근 노래 다음 3곡. */
    val albumSongs: StateFlow<List<SongEntity>> = songRepository.songs.map { it.drop(1).take(ALBUM_SIZE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    /** 재생 중이면 "최근 노래" 버튼이 일시정지 버튼이 된다. */
    val isPlaying: StateFlow<Boolean> = player.nowPlaying.map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)

    /** 음성 명령 상태 한 줄 (설계 §8.2). */
    val voiceStatus: StateFlow<VoiceStatus> = voiceCommands.status

    init {
        player.connect() // 재생 상태를 받기 시작한다 (재생 화면을 거치지 않고 홈에 있어도)
    }

    /** "말로 하기": 한 번 듣고 명령을 실행한다. 화면을 떠나도 핸들러가 끝까지 처리한다. */
    fun speak() = voiceCommands.startListening()

    /** 재생 중이면 일시정지한다. */
    fun pause() {
        if (player.nowPlaying.value.isPlaying) player.togglePlayPause()
    }

    /** 최근 노래부터 보관함 전체를 재생 목록으로 올린다 ("다음" 은 그 전 노래). */
    fun playRecent() = playFrom(null)

    /** 앨범 카드: 보관함 전체를 재생 목록으로 올리고 그 노래부터 재생한다. */
    fun playSong(songId: String) = playFrom(songId)

    private fun playFrom(songId: String?) {
        viewModelScope.launch {
            val songs = songRepository.songs.first()
            val index = if (songId == null) 0 else songs.indexOfFirst { it.id == songId }
            if (index in songs.indices) player.play(songs, index)
        }
    }

    private companion object {
        const val ALBUM_SIZE = 3
    }
}
