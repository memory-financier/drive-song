package com.drivesong.feature.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.song.SongOrchestrator
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import javax.inject.Inject

/**
 * 노래 재생 서비스 (설계 §3 PlaybackService, §4.1 Media3).
 *
 * MediaSessionService 대신 MediaLibraryService 를 쓴다 (설계 §4.1 [확인 필요] 에 대한 선택):
 * AAOS 의 시스템 미디어 UI(미디어 센터)는 MediaBrowserService 로 앱의 탐색 트리를 읽어 재생하므로, 세션만 있으면
 * 앱 화면 밖(스티어링 휠·시스템 미디어 화면)에서 노래를 고를 수 없다. 트리는 루트 → 노래(최근 순) 한 단계뿐이다.
 * [확인 필요] 설계 §13-5: Pleos 가 시스템 미디어 UI 를 요구하는지, 자체 UI 만으로 되는지.
 *
 * - 오디오 포커스: [buildSongPlayer] (ExoPlayer 가 처리).
 * - 재생 시작/종료를 [SongOrchestrator] 에 알린다 ([PlaybackReporter]): PLAYING → ARCHIVED.
 * - 컨트롤러가 보낸 MediaItem 은 mediaId 로 Room 에서 다시 찾아 로컬 파일 URI 를 붙인다 (시스템 UI 는 URI 를 보내지 않는다).
 * - 재생 중에는 Media3 가 미디어 알림과 함께 Foreground Service 로 올린다.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var songs: SongRepository

    @Inject lateinit var orchestrator: SongOrchestrator

    private val scope = MainScope()
    private var session: MediaLibrarySession? = null
    private lateinit var reporter: PlaybackReporter

    override fun onCreate() {
        super.onCreate()
        reporter = PlaybackReporter(orchestrator::onPlaybackStarted, orchestrator::onPlaybackEnded)
        val player = buildSongPlayer(this)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) =
                reporter.update(player.currentMediaItem?.mediaId, player.isPlaying, player.playbackState == Player.STATE_ENDED)
        })
        val builder = MediaLibrarySession.Builder(this, player, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** 앱을 최근 목록에서 지웠을 때, 재생 중이 아니면 서비스도 끝낸다. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            reporter.update(mediaId = null, isPlaying = false, ended = false) // 재생 중이던 노래는 종료로 알린다
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(libraryRoot(getString(R.string.player_library_root)), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (parentId != LIBRARY_ROOT_ID) return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            val skip = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() // 기본 pageSize 는 Int.MAX_VALUE
            val items = songs.songs.first().drop(skip).take(pageSize).map { it.toMediaItem() }
            LibraryResult.ofItemList(items, params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = if (mediaId == LIBRARY_ROOT_ID) {
                libraryRoot(getString(R.string.player_library_root))
            } else {
                songs.song(mediaId)?.toMediaItem()
            }
            item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /** 없는(지워진) 노래는 빼고 넣는다. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.mapNotNull { songs.song(it.mediaId)?.toMediaItem() }.toMutableList()
        }
    }
}
