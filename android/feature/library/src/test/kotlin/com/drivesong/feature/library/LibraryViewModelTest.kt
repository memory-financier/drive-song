package com.drivesong.feature.library

import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.data.song.SongRepository
import com.drivesong.feature.player.NowPlaying
import com.drivesong.feature.player.SongPlayer
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val songs = MutableStateFlow(listOf(song("song_2", 2_000), song("song_1", 1_000)))
    private val repo = mockk<SongRepository> { every { songs } returns this@LibraryViewModelTest.songs }
    private val player = FakeSongPlayer()
    private lateinit var vm: LibraryViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = LibraryViewModel(repo, player)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend () -> Unit) = runTest(dispatcher) {
        val collector = launch { vm.uiState.collect {} }
        block()
        collector.cancel()
    }

    @Test
    fun `lists songs as the repository orders them (newest first)`() = test {
        assertEquals(listOf("song_2", "song_1"), vm.uiState.value.songs.map { it.id })
    }

    @Test
    fun `lyrics open for one song at a time and close again`() = test {
        vm.toggleLyrics("song_1")
        assertEquals("song_1", vm.uiState.value.lyricsSongId)

        vm.toggleLyrics("song_2")
        assertEquals("song_2", vm.uiState.value.lyricsSongId)

        vm.toggleLyrics("song_2")
        assertNull(vm.uiState.value.lyricsSongId)
    }

    @Test
    fun `open lyrics close when the song is deleted`() = test {
        vm.toggleLyrics("song_1")
        songs.value = listOf(song("song_2", 2_000))

        assertNull(vm.uiState.value.lyricsSongId)
    }

    @Test
    fun `play queues the whole library starting at the chosen song`() = test {
        vm.play("song_1")

        assertEquals(listOf(listOf("song_2", "song_1") to 1), player.played)
    }

    @Test
    fun `play of a song that no longer exists does nothing`() = test {
        vm.play("gone")

        assertEquals(emptyList<Any>(), player.played)
    }
}

private fun song(id: String, createdAtMs: Long) =
    SongEntity(id, "trip_$id", "제목 $id", "[verse]\n가사 $id", createdAtMs, "/songs/$id.m4a", emptyList())

private class FakeSongPlayer : SongPlayer {
    override val nowPlaying = MutableStateFlow(NowPlaying())
    val played = mutableListOf<Pair<List<String>, Int>>()
    override fun connect() = Unit
    override fun play(songs: List<SongEntity>, startIndex: Int) {
        played += songs.map { it.id } to startIndex
    }
    override fun togglePlayPause() = Unit
    override fun next() = Unit
    override fun stop() = Unit
}
