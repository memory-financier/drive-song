package com.drivesong.ui.screens

import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.player.NowPlaying
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val songs = MutableStateFlow(listOf(song("song_3", 3_000), song("song_2", 2_000), song("song_1", 1_000)))
    private val repo = mockk<SongRepository> { every { songs } returns this@PlayerViewModelTest.songs }
    private val player = FakeSongPlayer()
    private lateinit var vm: PlayerViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = PlayerViewModel(repo, player)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend () -> Unit) = runTest(dispatcher) {
        val collector = launch { vm.uiState.collect {} }
        block()
        collector.cancel()
    }

    private val state get() = vm.uiState.value

    @Test
    fun `connects to the playback service when the screen opens`() {
        assertEquals(listOf("connect"), player.calls)
    }

    @Test
    fun `with nothing loaded it shows the most recent song ready to play`() = test {
        assertEquals("song_3", state.song?.id)
        assertFalse(state.isPlaying)
        assertFalse(state.hasNext)

        vm.togglePlayPause()

        assertEquals(listOf(listOf("song_3", "song_2", "song_1") to 0), player.played)
    }

    @Test
    fun `shows the loaded song with its playing state and next availability`() = test {
        player.nowPlaying.value = NowPlaying(songId = "song_2", isPlaying = true, hasNext = true)

        assertEquals("song_2", state.song?.id)
        assertTrue(state.isPlaying)
        assertTrue(state.hasNext)
    }

    @Test
    fun `play-pause on the loaded song toggles the player instead of reloading`() = test {
        player.nowPlaying.value = NowPlaying(songId = "song_2", isPlaying = true, hasNext = true)

        vm.togglePlayPause()
        vm.next()

        assertEquals(listOf("connect", "toggle", "next"), player.calls)
    }

    @Test
    fun `a loaded song that was deleted falls back to the most recent song`() = test {
        player.nowPlaying.value = NowPlaying(songId = "gone", isPlaying = true, hasNext = true)

        assertEquals("song_3", state.song?.id)
        assertFalse(state.isPlaying)
        assertFalse(state.hasNext)

        vm.togglePlayPause()
        assertEquals(listOf(listOf("song_3", "song_2", "song_1") to 0), player.played)
    }

    @Test
    fun `without songs there is nothing to play`() = test {
        songs.value = emptyList()

        assertNull(state.song)
        vm.togglePlayPause()
        assertEquals(listOf("connect"), player.calls)
    }
}
