package com.drivesong.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackReporterTest {

    private val events = mutableListOf<String>()
    private val reporter = PlaybackReporter(onStarted = { events += "start $it" }, onEnded = { events += "end $it" })

    @Test
    fun `reports start once and end when the song finishes`() {
        reporter.update("song_1", isPlaying = false, ended = false) // 준비 중
        reporter.update("song_1", isPlaying = true, ended = false)
        reporter.update("song_1", isPlaying = true, ended = false)
        reporter.update("song_1", isPlaying = false, ended = true)
        reporter.update("song_1", isPlaying = false, ended = true)

        assertEquals(listOf("start song_1", "end song_1"), events)
    }

    @Test
    fun `pausing is not an end`() {
        reporter.update("song_1", isPlaying = true, ended = false)
        reporter.update("song_1", isPlaying = false, ended = false)
        reporter.update("song_1", isPlaying = true, ended = false)

        assertEquals(listOf("start song_1"), events)
    }

    @Test
    fun `moving to the next song ends the previous one and starts the next while still playing`() {
        reporter.update("song_1", isPlaying = true, ended = false)
        reporter.update("song_2", isPlaying = true, ended = false)

        assertEquals(listOf("start song_1", "end song_1", "start song_2"), events)
    }

    @Test
    fun `clearing the playlist or stopping the service ends the current song`() {
        reporter.update("song_1", isPlaying = true, ended = false)
        reporter.update(null, isPlaying = false, ended = false)
        reporter.update(null, isPlaying = false, ended = false)

        assertEquals(listOf("start song_1", "end song_1"), events)
    }

    @Test
    fun `replaying a finished song reports it again`() {
        reporter.update("song_1", isPlaying = true, ended = false)
        reporter.update("song_1", isPlaying = false, ended = true)
        reporter.update("song_1", isPlaying = true, ended = false)

        assertEquals(listOf("start song_1", "end song_1", "start song_1"), events)
    }

    @Test
    fun `a song that never played is never reported`() {
        reporter.update("song_1", isPlaying = false, ended = false)
        reporter.update("song_2", isPlaying = false, ended = false)

        assertEquals(emptyList<String>(), events)
    }
}
