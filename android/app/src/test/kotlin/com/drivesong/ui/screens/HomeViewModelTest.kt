package com.drivesong.ui.screens

import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.player.NowPlaying
import com.drivesong.feature.song.SongOrchestrator
import com.drivesong.feature.song.SongPhase
import com.drivesong.feature.song.SongState
import com.drivesong.feature.trip.TripEvent
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripState
import com.drivesong.feature.voice.command.VoiceCommandHandler
import com.drivesong.feature.voice.command.VoiceStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val songs = MutableStateFlow(listOf(song("song_2", 2_000), song("song_1", 1_000)))
    private val repo = mockk<SongRepository> { every { songs } returns this@HomeViewModelTest.songs }
    private val songState = MutableStateFlow(SongState())
    private val orchestrator = mockk<SongOrchestrator> { every { state } returns songState }
    private val trip = object : TripEvents {
        override val state = MutableStateFlow<TripState>(TripState.Idle)
        override val events = MutableSharedFlow<TripEvent>()
        override suspend fun currentSummary() = null
    }
    private val player = FakeSongPlayer()
    private val voiceCommands = mockk<VoiceCommandHandler>(relaxed = true) {
        every { status } returns MutableStateFlow(VoiceStatus.Idle)
    }
    private lateinit var vm: HomeViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = HomeViewModel(trip, orchestrator, repo, player, voiceCommands)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend () -> Unit) = runTest(dispatcher) {
        val collector = launch { vm.recentSong.collect {} }
        val playing = launch { vm.isPlaying.collect {} }
        block()
        collector.cancel()
        playing.cancel()
    }

    @Test
    fun `progress card follows trip and song state`() = test {
        assertSame(trip.state, vm.tripState)
        songState.value = SongState(SongPhase.WAITING_FOR_SONG, "trip_1", arrived = true)

        assertTrue(vm.songState.value.almostReady) // "노래가 곧 완성돼요"
    }

    @Test
    fun `recent song is the newest song`() = test {
        assertEquals("song_2", vm.recentSong.value?.id)

        songs.value = listOf(song("song_3", 3_000)) + songs.value
        assertEquals("song_3", vm.recentSong.value?.id)

        songs.value = emptyList()
        assertNull(vm.recentSong.value)
    }

    @Test
    fun `play recent queues the library from the newest song`() = test {
        vm.playRecent()

        assertEquals(listOf(listOf("song_2", "song_1") to 0), player.played)
    }

    @Test
    fun `play recent without songs does nothing`() = test {
        songs.value = emptyList()

        vm.playRecent()

        assertEquals(emptyList<Any>(), player.played)
    }

    @Test
    fun `recent song button follows playback and pauses while playing`() = test {
        assertEquals(listOf("connect"), player.calls)
        assertFalse(vm.isPlaying.value)
        vm.pause()
        assertEquals(listOf("connect"), player.calls) // 멈춰 있으면 아무것도 안 한다

        player.nowPlaying.value = NowPlaying("song_2", isPlaying = true)
        assertTrue(vm.isPlaying.value)
        vm.pause()
        assertEquals(listOf("connect", "toggle"), player.calls)

        player.nowPlaying.value = NowPlaying("song_2", isPlaying = false)
        assertFalse(vm.isPlaying.value)
    }
}
