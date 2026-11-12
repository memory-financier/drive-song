package com.drivesong.feature.player

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 설계 §11-T11 완료 기준: 재생 중 다른 앱이 오디오 포커스를 요청하면 정책대로 동작하는지.
 * 실제 [buildSongPlayer] ExoPlayer 가 요청한 포커스를 Robolectric 이 잡아 두고, 다른 앱이 포커스를 가져갈 때
 * 시스템이 보내는 콜백을 그대로 흘려 플레이어 반응을 본다.
 * ducking 은 ExoPlayer 내부 볼륨 배율이라 공개 API 로 안 보이므로, 오디오 렌더러 자리에 볼륨 메시지를 기록하는 렌더러를 넣는다.
 */
@RunWith(RobolectricTestRunner::class)
class AudioFocusPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager: ShadowAudioManager = shadowOf(context.getSystemService(AudioManager::class.java))
    private val volumes = CopyOnWriteArrayList<Float>()
    private val player: ExoPlayer = buildSongPlayer(context, RenderersFactory { _, _, _, _, _ -> arrayOf(VolumeRecorder()) })
    private val pauseReasons = mutableListOf<Int>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) pauseReasons += reason
            }
        })
    }

    @After
    fun tearDown() = player.release()

    /** 재생을 시작한다. ExoPlayer 는 prepare 시점에 포커스를 요청한다 (파일 로딩 성공 여부와 무관). */
    private fun startPlaying(): ShadowAudioManager.AudioFocusRequest {
        player.setMediaItem(MediaItem.fromUri("file:///song.m4a"))
        player.playWhenReady = true
        player.prepare()
        idleMain()
        return checkNotNull(audioManager.lastAudioFocusRequest) { "no audio focus requested" }
    }

    private fun ShadowAudioManager.AudioFocusRequest.change(focusChange: Int) {
        listener.onAudioFocusChange(focusChange)
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** 렌더러 메시지는 재생 스레드에서 처리된다. */
    private fun awaitVolume(expected: Float) {
        val deadline = System.currentTimeMillis() + 5_000
        while (volumes.lastOrNull() != expected) {
            check(System.currentTimeMillis() < deadline) { "volume never became $expected: $volumes" }
            idleMain()
            Thread.sleep(10)
        }
    }

    private fun assertKeepsPlaying() {
        assertTrue(player.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
    }

    @Test
    fun `requests full media focus as music that ducks instead of pausing`() {
        val request = startPlaying()

        val platform = request.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, platform.focusGain)
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, platform.audioAttributes.usage)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_MUSIC, platform.audioAttributes.contentType)
        assertFalse("music must duck, not pause, for navigation prompts", platform.willPauseWhenDucked())
    }

    @Test
    fun `navigation prompt (transient can-duck) lowers volume and keeps playing, then restores`() {
        val request = startPlaying()

        request.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertKeepsPlaying()
        awaitVolume(0.2f)

        request.change(AudioManager.AUDIOFOCUS_GAIN)
        assertKeepsPlaying()
        awaitVolume(1f)
    }

    @Test
    fun `transient loss pauses and resumes when focus comes back`() {
        val request = startPlaying()

        request.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, player.playbackSuppressionReason)
        assertFalse(player.isPlaying)

        request.change(AudioManager.AUDIOFOCUS_GAIN)
        assertKeepsPlaying()
    }

    @Test
    fun `permanent loss pauses and does not resume by itself`() {
        val request = startPlaying()

        request.change(AudioManager.AUDIOFOCUS_LOSS)

        assertFalse(player.playWhenReady)
        assertEquals(listOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS), pauseReasons)
        assertSame("focus is given up, so no GAIN will resume it", request.audioFocusRequest, audioManager.lastAbandonedAudioFocusRequest)
    }

    /** 오디오 렌더러 자리: 받은 볼륨(MSG_SET_VOLUME)만 기록하고 아무 포맷도 재생하지 않는다. */
    private inner class VolumeRecorder : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        override fun handleMessage(messageType: Int, message: Any?) {
            if (messageType == Renderer.MSG_SET_VOLUME) volumes += message as Float
        }

        override fun getName() = "VolumeRecorder"
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = Unit
        override fun isReady() = true
        override fun isEnded() = false
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }
}
