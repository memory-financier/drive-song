package com.drivesong.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VoiceCommandParserTest {

    private val family = listOf("엄마", "아빠", "첫째", "둘째", "할머니")

    private fun parse(text: String) = VoiceCommandParser.parse(text, family)

    @Test
    fun `play phrases`() {
        assertEquals(VoiceCommand.PlaySong, parse("드라이브 송 틀어줘"))
        assertEquals(VoiceCommand.PlaySong, parse("노래 재생해 줘"))
        assertEquals(VoiceCommand.PlaySong, parse("오늘 노래 들려줘"))
    }

    @Test
    fun `pause phrases`() {
        assertEquals(VoiceCommand.PauseSong, parse("노래 멈춰"))
        assertEquals(VoiceCommand.PauseSong, parse("재생 정지"))
        assertEquals(VoiceCommand.PauseSong, parse("그만 틀어"))
    }

    @Test
    fun `record with nickname and slot`() {
        assertEquals(VoiceCommand.RecordClip("둘째", ClipSlot.CALL), parse("둘째 응원 한마디 녹음해줘"))
        assertEquals(VoiceCommand.RecordClip("엄마", ClipSlot.INTRO), parse("엄마 출발 인사 녹음"))
        assertEquals(VoiceCommand.RecordClip("첫째", ClipSlot.OUTRO), parse("첫째 도착 인사 녹음할래"))
    }

    @Test
    fun `record without nickname or slot leaves them null`() {
        assertEquals(VoiceCommand.RecordClip(null, null), parse("녹음해 줘"))
        assertEquals(VoiceCommand.RecordClip("아빠", null), parse("아빠 녹음"))
    }

    @Test
    fun `spacing in the recognized text or the nickname does not matter`() {
        assertEquals(VoiceCommand.RecordClip("둘째", ClipSlot.CALL), parse("둘 째응원녹음"))
        assertEquals(
            VoiceCommand.RecordClip("우리 막내", null),
            VoiceCommandParser.parse("우리막내 녹음", listOf("우리 막내")),
        )
    }

    @Test
    fun `the longest nickname contained in the text wins`() {
        assertEquals(
            VoiceCommand.RecordClip("큰엄마", null),
            VoiceCommandParser.parse("큰엄마 녹음", listOf("엄마", "큰엄마")),
        )
    }

    @Test
    fun `record wins over play words in the same sentence`() {
        assertEquals(VoiceCommand.RecordClip("할머니", null), parse("할머니 목소리 녹음 재생"))
    }

    @Test
    fun `unknown or empty text is null`() {
        assertNull(parse("오늘 날씨 어때"))
        assertNull(parse("   "))
    }
}
