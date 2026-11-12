package com.drivesong.feature.voice.gleo

import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.VoiceCommand
import com.drivesong.feature.voice.command.VoiceResult
import org.json.JSONObject

/**
 * Gleo Interface 액션 ↔ [VoiceCommand] (설계 §8.2). function 이름 = Intent action (assets/schema.json).
 * 파라미터는 extra 키로 오거나, `ACTION_PARAM` extra 의 JSON 문자열로 온다 → 둘 다 읽는다 [확인 필요].
 */
object GleoActions {
    const val PLAY_DRIVE_SONG = "PLAY_DRIVE_SONG"
    const val RECORD_FAMILY_VOICE = "RECORD_FAMILY_VOICE"
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    const val ACTION_PARAM = "ACTION_PARAM"
    const val PARAM_NICKNAME = "nickname"
    const val PARAM_SLOT = "slot"

    /** @param extra extra 이름 → 문자열 값 (없으면 null). 모르는 액션이면 null. */
    fun toCommand(action: String?, extra: (String) -> String?): VoiceCommand? = when (action) {
        PLAY_DRIVE_SONG -> VoiceCommand.PlaySong
        RECORD_FAMILY_VOICE -> {
            val json = extra(ACTION_PARAM)?.let { runCatching { JSONObject(it) }.getOrNull() }
            fun param(name: String) = (extra(name) ?: json?.optString(name)?.takeIf { it.isNotEmpty() })
                ?.trim()?.takeIf { it.isNotEmpty() }
            VoiceCommand.RecordClip(
                nickname = param(PARAM_NICKNAME),
                slot = param(PARAM_SLOT)?.let { value -> ClipSlot.entries.find { it.name.equals(value, ignoreCase = true) } },
            )
        }
        else -> null
    }

    /** Gleo 로 돌려주는 응답 JSON: {functionName, status, message, data}. */
    fun response(functionName: String, result: VoiceResult, message: String): String = JSONObject()
        .put("functionName", functionName)
        .put("status", if (result.success) "success" else "fail")
        .put("message", message)
        .put("data", JSONObject().put("result", result.name))
        .toString()
}
