package com.drivesong.feature.voice.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drivesong.feature.voice.command.VoiceCommandHandler
import com.drivesong.feature.voice.command.VoiceResult
import com.drivesong.feature.voice.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gleo AI(차량 음성 비서)가 보내는 액션 (설계 §8.2, schema.json component "service" → sendBroadcast).
 * 처리가 끝나면 응답 브로드캐스트를 보낸다. 녹음은 저장까지 약 6초 → goAsync 로 기다린다.
 * [확인 필요] 응답을 받을 Gleo 패키지 (지금은 대상 지정 없이 보냄), 발신자를 Gleo 로 제한하는 방법.
 */
class GleoActionReceiver : BroadcastReceiver() {

    /** 이 모듈은 Hilt Gradle 플러그인을 쓰지 않아 @AndroidEntryPoint 대신 EntryPoint 로 꺼낸다. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun voiceCommandHandler(): VoiceCommandHandler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val command = GleoActions.toCommand(action) { intent.getStringExtra(it) } ?: return
        val handler = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
            .voiceCommandHandler()
        val pending = goAsync()
        handler.submit(command) { result ->
            val response = Intent(GleoActions.RESPONSE_ACTION)
                .putExtra("packageName", context.packageName)
                .putExtra("response", GleoActions.response(action, result, context.getString(messageOf(result))))
            context.sendBroadcast(response)
            pending.finish()
        }
    }

    private fun messageOf(result: VoiceResult): Int = when (result) {
        VoiceResult.PLAYING -> R.string.gleo_playing
        VoiceResult.PAUSED -> R.string.gleo_paused
        VoiceResult.NO_SONG -> R.string.gleo_no_song
        VoiceResult.SAVED -> R.string.gleo_saved
        VoiceResult.WHO, VoiceResult.UNKNOWN_MEMBER -> R.string.gleo_who
        VoiceResult.BUSY -> R.string.gleo_busy
        else -> R.string.gleo_failed
    }
}
