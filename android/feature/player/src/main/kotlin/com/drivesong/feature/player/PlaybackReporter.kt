package com.drivesong.feature.player

/**
 * 재생 시작/종료를 SongOrchestrator 에 알린다 (설계 §7.1 READY → PLAYING → ARCHIVED).
 * 플레이어 이벤트마다 현재 상태를 [update] 로 넘긴다. 같은 노래는 시작·종료를 한 번씩만 알린다.
 * - 시작: 그 노래가 실제로 재생되기 시작함.
 * - 종료: 끝까지 재생됨, 다른 노래로 넘어감, 목록이 비워짐, 또는 서비스 종료.
 *   (일시정지는 종료가 아니다.)
 * 오케스트레이터는 자기가 만든 노래가 아니면 무시하므로 보관함의 다른 노래도 그대로 알린다.
 */
class PlaybackReporter(
    private val onStarted: (songId: String) -> Unit,
    private val onEnded: (songId: String) -> Unit,
) {
    private var current: String? = null

    fun update(mediaId: String?, isPlaying: Boolean, ended: Boolean) {
        current?.let {
            if (it != mediaId || ended) {
                current = null
                onEnded(it)
            }
        }
        if (isPlaying && mediaId != null && current == null) {
            current = mediaId
            onStarted(mediaId)
        }
    }
}
