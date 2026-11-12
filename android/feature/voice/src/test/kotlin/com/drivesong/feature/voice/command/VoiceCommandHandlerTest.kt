package com.drivesong.feature.voice.command

import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.VoiceCommand
import com.drivesong.feature.player.NowPlaying
import com.drivesong.feature.player.SongPlayer
import com.drivesong.feature.voice.FakeApi
import com.drivesong.feature.voice.FakeRecorder
import com.drivesong.feature.voice.FakeSettings
import com.drivesong.feature.voice.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceCommandHandlerTest {

    private val dispatcher = UnconfinedTestDispatcher().also { Dispatchers.setMain(it) }
    private val db = inMemoryDatabase()
    private val api = FakeApi()
    private val settings = FakeSettings()
    private val recorder = FakeRecorder { dispatcher.scheduler.currentTime }
    private val player = RecordingSongPlayer()
    private val cue = CountingCue()
    private val speech = QueuedSpeech()
    private val voice = VoiceRepository(db.voiceDao(), api, settings, ApplicationProvider.getApplicationContext())
    private val songs = SongRepository(db.songDao(), api, ApplicationProvider.getApplicationContext())
    private val handler = VoiceCommandHandler(voice, songs, player, settings, recorder, cue, speech)

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun member(nickname: String): String =
        voice.addMember(nickname, isChild = false, voiceConsent = true, guardianConsent = false).id

    @Test
    fun `spoken record command records five seconds after the cue, saves and uploads`() = test {
        val kid = member("둘째")
        member("엄마")
        speech.results += SpeechResult.Text("둘째 응원 한마디 녹음해줘")

        val result = handler.listenAndRun() // 업로드도 즉시 실행된다 (Unconfined)

        assertEquals(VoiceResult.SAVED, result)
        assertEquals(1, cue.count)
        assertEquals(listOf(VoiceCommandHandler.MAX_CLIP_MS), recorder.stoppedAt)
        val clip = voice.clips.first().single()
        assertEquals(kid to ClipSlot.CALL, clip.memberId to clip.slot)
        assertEquals(listOf(kid to ClipSlot.CALL), api.uploads)
        assertEquals(VoiceStatus.Finished(VoiceResult.SAVED, "둘째", ClipSlot.CALL), handler.status.value)
    }

    @Test
    fun `without a nickname the single passenger on board is recorded`() = test {
        member("엄마")
        val kid = member("첫째")
        settings.selectedPassengerIds.value = setOf(kid)

        assertEquals(VoiceResult.SAVED, handler.run(VoiceCommand.RecordClip(null, ClipSlot.INTRO)))
        assertEquals(kid, voice.clips.first().single().memberId)
    }

    @Test
    fun `without a nickname and several on board it asks who`() = test {
        settings.selectedPassengerIds.value = setOf(member("엄마"), member("첫째"))

        assertEquals(VoiceResult.WHO, handler.run(VoiceCommand.RecordClip(null, null)))
        assertEquals(0, recorder.starts)
    }

    @Test
    fun `an unregistered nickname is refused before recording`() = test {
        member("엄마")

        assertEquals(VoiceResult.UNKNOWN_MEMBER, handler.run(VoiceCommand.RecordClip("삼촌", null)))
        assertEquals(VoiceStatus.Finished(VoiceResult.UNKNOWN_MEMBER, "삼촌"), handler.status.value)
        assertEquals(0, recorder.starts)
    }

    @Test
    fun `a full slot drops its oldest clip`() = test {
        member("아빠")
        repeat(3) { handler.run(VoiceCommand.RecordClip("아빠", ClipSlot.OUTRO)) }
        advanceUntilIdle()
        val oldest = voice.clips.first().first()

        assertEquals(VoiceResult.SAVED, handler.run(VoiceCommand.RecordClip("아 빠", ClipSlot.OUTRO)))
        advanceUntilIdle()

        assertEquals(listOf(oldest.serverId), api.deletedClips)
        assertEquals(3, voice.clips.first().size)
        assertFalse(File(oldest.localPath).exists())
    }

    @Test
    fun `music is paused before recording so it does not leak into the clip`() = test {
        member("엄마")
        player.nowPlaying.value = NowPlaying("song_1", isPlaying = true)

        handler.run(VoiceCommand.RecordClip("엄마", null))

        assertEquals(listOf("toggle"), player.calls)
    }

    @Test
    fun `microphone failure and too short recordings save nothing`() = test {
        member("엄마")
        recorder.failStart = true
        assertEquals(VoiceResult.MIC_ERROR, handler.run(VoiceCommand.RecordClip("엄마", null)))

        recorder.failStart = false
        recorder.stopResult = false
        assertEquals(VoiceResult.TOO_SHORT, handler.run(VoiceCommand.RecordClip("엄마", null)))

        assertTrue(voice.clips.first().isEmpty())
        assertTrue(voice.newRecordingFile().parentFile!!.listFiles()!!.isEmpty())
    }

    @Test
    fun `play starts from the newest song, and says so when there is none`() = test {
        assertEquals(VoiceResult.NO_SONG, handler.run(VoiceCommand.PlaySong))
        db.songDao().upsert(song("old", 1_000))
        db.songDao().upsert(song("new", 2_000))

        assertEquals(VoiceResult.PLAYING, handler.run(VoiceCommand.PlaySong))
        assertEquals(listOf(listOf("new", "old") to 0), player.played)
    }

    @Test
    fun `pause only toggles while playing`() = test {
        assertEquals(VoiceResult.PAUSED, handler.run(VoiceCommand.PauseSong))
        player.nowPlaying.value = NowPlaying("song_1", isPlaying = true)
        handler.run(VoiceCommand.PauseSong)
        assertEquals(listOf("toggle"), player.calls)
    }

    @Test
    fun `speech that is missing or not a command is reported`() = test {
        speech.results += SpeechResult.Unavailable
        speech.results += SpeechResult.NoSpeech
        speech.results += SpeechResult.Text("오늘 날씨 어때")

        assertEquals(VoiceResult.STT_UNAVAILABLE, handler.listenAndRun())
        assertEquals(VoiceResult.NOT_UNDERSTOOD, handler.listenAndRun())
        assertEquals(VoiceResult.NOT_UNDERSTOOD, handler.listenAndRun())
    }

    @Test
    fun `a command during a recording is busy`() = test {
        member("엄마")
        val recording = async { handler.run(VoiceCommand.RecordClip("엄마", null)) }
        assertEquals(VoiceStatus.Recording("엄마", ClipSlot.CALL), handler.status.value)

        assertEquals(VoiceResult.BUSY, handler.run(VoiceCommand.PlaySong))
        assertEquals(VoiceResult.SAVED, recording.await())
    }

    @Test
    fun `the result line clears after a while`() = test {
        handler.run(VoiceCommand.PauseSong)
        assertTrue(handler.status.value is VoiceStatus.Finished)
        advanceUntilIdle()
        assertEquals(VoiceStatus.Idle, handler.status.value)
    }

    // --- fakes ---

    private fun song(id: String, createdAtMs: Long) =
        SongEntity(id, "trip_$id", "제목 $id", "가사", createdAtMs, "/songs/$id.m4a", emptyList())

    private class RecordingSongPlayer : SongPlayer {
        override val nowPlaying = MutableStateFlow(NowPlaying())
        val calls = mutableListOf<String>()
        val played = mutableListOf<Pair<List<String>, Int>>()
        override fun connect() = Unit
        override fun play(songs: List<SongEntity>, startIndex: Int) {
            played += songs.map { it.id } to startIndex
        }
        override fun togglePlayPause() {
            calls += "toggle"
        }
        override fun next() = Unit
        override fun stop() = Unit
    }

    private class CountingCue : RecordingCue {
        var count = 0
        override suspend fun beforeRecording() {
            count++
        }
    }

    private class QueuedSpeech : SpeechInput {
        val results = ArrayDeque<SpeechResult>()
        override suspend fun listen(): SpeechResult = results.removeFirst()
    }
}
