package com.drivesong.feature.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.drivesong.core.data.db.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackService] 에 MediaController 로 붙는 [SongPlayer]. 처음 쓸 때 연결하고 프로세스 동안 유지한다.
 * 서비스가 끊기면 다음 동작 때 다시 연결한다. MediaController 는 메인 스레드에서만 다룬다.
 */
@Singleton
class MediaControllerSongPlayer @Inject constructor(@ApplicationContext private val context: Context) : SongPlayer {

    private val scope = MainScope()
    private var connection: Deferred<MediaController>? = null
    private val _nowPlaying = MutableStateFlow(NowPlaying())
    override val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    override fun connect() = withController {}

    override fun play(songs: List<SongEntity>, startIndex: Int) = withController {
        it.setMediaItems(songs.map { song -> song.toMediaItem() }, startIndex, C.TIME_UNSET)
        it.prepare()
        it.play()
    }

    override fun togglePlayPause() = withController {
        if (it.showsPlay()) {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
            it.play()
        } else {
            it.pause()
        }
    }

    override fun next() = withController { it.seekToNextMediaItem() }

    override fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }

    private fun withController(action: (MediaController) -> Unit) {
        scope.launch {
            val controller = try {
                controller().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cannot connect to PlaybackService", e)
                connection = null
                return@launch
            }
            action(controller)
        }
    }

    private fun controller(): Deferred<MediaController> = connection ?: scope.async {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    connection = null
                    _nowPlaying.value = NowPlaying()
                }
            })
            .buildAsync()
            .await()
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish(player)
        })
        publish(controller)
        controller
    }.also { connection = it }

    private fun publish(player: Player) {
        _nowPlaying.value = NowPlaying(player.currentMediaItem?.mediaId, !player.showsPlay(), player.hasNextMediaItem())
    }

    private companion object {
        const val TAG = "SongPlayer"

        /** 재생 버튼을 보여줄 상태: 멈춤, 준비 전, 끝까지 재생됨. */
        fun Player.showsPlay(): Boolean =
            !playWhenReady || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED
    }
}
