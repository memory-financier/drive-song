package com.drivesong.feature.player

import com.drivesong.core.data.db.SongEntity
import kotlinx.coroutines.flow.StateFlow

/** 재생 화면에 보일 현재 상태. */
data class NowPlaying(
    /** 플레이어에 올라간 노래 id. 없으면 null. */
    val songId: String? = null,
    /** 재생 중으로 표시 (일시정지 버튼을 보여줄지). */
    val isPlaying: Boolean = false,
    val hasNext: Boolean = false,
)

/**
 * 앱 화면·자동 재생이 쓰는 재생 제어. 실제 재생은 [PlaybackService] 가 하고, 구현은 Media3 MediaController 로 붙는다.
 * 모든 동작은 비동기로 메인 스레드에서 처리되고 바로 돌아온다.
 */
interface SongPlayer {
    val nowPlaying: StateFlow<NowPlaying>

    /** 재생 서비스에 연결해 [nowPlaying] 을 받기 시작한다 (재생 화면이 열릴 때). */
    fun connect()

    /** [songs] 를 재생 목록으로 올리고 [startIndex] 번째부터 재생한다 ("다음" 은 목록의 다음 노래). */
    fun play(songs: List<SongEntity>, startIndex: Int = 0)

    fun togglePlayPause()

    fun next()

    /** 멈추고 재생 목록을 비운다 (데이터 삭제 전). */
    fun stop()
}
