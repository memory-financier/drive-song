package com.drivesong.core.model

/** 음성 명령 (설계 §8.2). Gleo Interface 호출과 "말로 하기"(SpeechToText) 인식문이 모두 이것으로 바뀐다. */
sealed interface VoiceCommand {
    /** 보관함 최신 곡부터 재생. */
    data object PlaySong : VoiceCommand

    /** 재생 중이면 일시정지. */
    data object PauseSong : VoiceCommand

    /**
     * 가족 목소리 5초 녹음.
     * @param nickname 구성원 호칭. null = 말하지 않음 (탑승자가 1명이면 그 사람).
     * @param slot null = 말하지 않음 (CALL).
     */
    data class RecordClip(val nickname: String?, val slot: ClipSlot?) : VoiceCommand
}

/** 한국어 인식문 → [VoiceCommand] (설계 §8.2). 띄어쓰기는 무시한다. */
object VoiceCommandParser {
    private val RECORD = listOf("녹음")
    private val PAUSE = listOf("멈춰", "멈춤", "정지", "그만")
    private val PLAY = listOf("틀어", "재생", "들려")
    private val SLOT_WORDS = listOf(
        "출발" to ClipSlot.INTRO,
        "도착" to ClipSlot.OUTRO,
        "응원" to ClipSlot.CALL,
        "한마디" to ClipSlot.CALL,
    )

    /**
     * @param nicknames 등록된 구성원 호칭. 문장에 들어 있는 것 중 가장 긴 것을 고른다 ("할머니" > "엄마" 같은 겹침 대비).
     * @return 알아들을 수 없으면 null. 녹음 > 일시정지 > 재생 순으로 본다 ("녹음한 거 틀어줘" 같은 문장은 없다고 본다).
     */
    fun parse(text: String, nicknames: Collection<String>): VoiceCommand? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        return when {
            RECORD.any { it in compact } -> VoiceCommand.RecordClip(
                nickname = nicknames
                    .filter { it.isNotBlank() && it.filterNot(Char::isWhitespace) in compact }
                    .maxByOrNull { it.length },
                slot = SLOT_WORDS.firstOrNull { (word, _) -> word in compact }?.second,
            )
            PAUSE.any { it in compact } -> VoiceCommand.PauseSong
            PLAY.any { it in compact } -> VoiceCommand.PlaySong
            else -> null
        }
    }
}
