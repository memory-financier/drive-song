package com.drivesong.feature.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory

/** 음악 재생 오디오 속성 (설계 §2 오디오, §11-T11). */
val SONG_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .build()

/**
 * PlaybackService 의 ExoPlayer. 오디오 포커스는 ExoPlayer 가 직접 처리한다 (handleAudioFocus = true):
 * - 다른 앱이 잠깐 포커스를 가져가며 ducking 을 허용하면 (내비 안내 음성 등, LOSS_TRANSIENT_CAN_DUCK) 볼륨만 줄이고 계속 재생.
 *   음악(CONTENT_TYPE_MUSIC)이라 willPauseWhenDucked = false.
 * - 잠깐 가져가면 (LOSS_TRANSIENT) 일시정지했다가 포커스가 돌아오면 다시 재생.
 * - 아예 가져가면 (LOSS) 일시정지하고 자동으로 다시 재생하지 않는다.
 * [확인 필요] Pleos/AAOS 의 포커스 정책(내비 음성과 미디어의 동시 재생 매트릭스)에서 앱이 실제로 어떤 콜백을 받는지.
 *
 * @param renderersFactory 테스트에서 볼륨 변화를 기록하는 렌더러를 넣을 때만 바꾼다.
 */
fun buildSongPlayer(context: Context, renderersFactory: RenderersFactory = DefaultRenderersFactory(context)): ExoPlayer =
    ExoPlayer.Builder(context, renderersFactory)
        .setAudioAttributes(SONG_AUDIO_ATTRIBUTES, /* handleAudioFocus = */ true)
        .build()
