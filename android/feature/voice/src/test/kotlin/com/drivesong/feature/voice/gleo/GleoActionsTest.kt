package com.drivesong.feature.voice.gleo

import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.VoiceCommand
import com.drivesong.feature.voice.command.VoiceResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** org.json 이 안드로이드 구현이라 Robolectric 으로 돈다. */
@RunWith(RobolectricTestRunner::class)
class GleoActionsTest {

    private fun command(action: String?, vararg extras: Pair<String, String>) =
        GleoActions.toCommand(action) { name -> extras.toMap()[name] }

    @Test
    fun `play action needs no parameters`() {
        assertEquals(VoiceCommand.PlaySong, command(GleoActions.PLAY_DRIVE_SONG))
    }

    @Test
    fun `record parameters come from plain extras`() {
        assertEquals(
            VoiceCommand.RecordClip("둘째", ClipSlot.CALL),
            command(GleoActions.RECORD_FAMILY_VOICE, "nickname" to " 둘째 ", "slot" to "CALL"),
        )
    }

    @Test
    fun `record parameters also come from the ACTION_PARAM json, slot in any case`() {
        assertEquals(
            VoiceCommand.RecordClip("엄마", ClipSlot.OUTRO),
            command(GleoActions.RECORD_FAMILY_VOICE, "ACTION_PARAM" to """{"nickname":"엄마","slot":"outro"}"""),
        )
    }

    @Test
    fun `missing, blank or unknown parameters become null`() {
        assertEquals(VoiceCommand.RecordClip(null, null), command(GleoActions.RECORD_FAMILY_VOICE))
        assertEquals(
            VoiceCommand.RecordClip(null, null),
            command(GleoActions.RECORD_FAMILY_VOICE, "nickname" to "  ", "slot" to "CHORUS", "ACTION_PARAM" to "not json"),
        )
    }

    @Test
    fun `unknown actions are ignored`() {
        assertNull(command("ADD_REMINDER"))
        assertNull(command(null))
    }

    @Test
    fun `response carries function name, status and message`() {
        val json = JSONObject(GleoActions.response(GleoActions.RECORD_FAMILY_VOICE, VoiceResult.SAVED, "녹음했어요"))
        assertEquals("RECORD_FAMILY_VOICE", json.getString("functionName"))
        assertEquals("success", json.getString("status"))
        assertEquals("녹음했어요", json.getString("message"))
        assertEquals("SAVED", json.getJSONObject("data").getString("result"))
        assertEquals("fail", JSONObject(GleoActions.response("X", VoiceResult.WHO, "")).getString("status"))
    }
}
