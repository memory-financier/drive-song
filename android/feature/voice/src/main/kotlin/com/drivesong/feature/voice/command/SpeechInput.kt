package com.drivesong.feature.voice.command

/** 한 번 듣기 결과. */
sealed interface SpeechResult {
    data class Text(val text: String) : SpeechResult

    /** 말소리가 없었거나 인식하지 못함. */
    data object NoSpeech : SpeechResult

    /** 음성 인식 서비스가 없거나 쓸 수 없음 (휴대폰 AVD 등). */
    data object Unavailable : SpeechResult
}

/** "말로 하기" 음성 인식 (설계 §8.2). */
interface SpeechInput {
    suspend fun listen(): SpeechResult
}
