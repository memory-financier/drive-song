package com.drivesong.ui.screens

import com.drivesong.core.data.db.SongEntity
import com.drivesong.feature.player.NowPlaying
import com.drivesong.feature.player.SongPlayer
import kotlinx.coroutines.flow.MutableStateFlow

fun song(id: String, createdAtMs: Long) =
    SongEntity(id, "trip_$id", "제목 $id", "[verse]\n가사 $id", createdAtMs, "/songs/$id.m4a", emptyList())

/** 호출만 기록한다. [nowPlaying] 은 테스트가 직접 바꾼다. */
class FakeSongPlayer : SongPlayer {
    override val nowPlaying = MutableStateFlow(NowPlaying())
    val calls = mutableListOf<String>()
    val played = mutableListOf<Pair<List<String>, Int>>()

    override fun connect() {
        calls += "connect"
    }

    override fun play(songs: List<SongEntity>, startIndex: Int) {
        calls += "play"
        played += songs.map { it.id } to startIndex
    }

    override fun togglePlayPause() {
        calls += "toggle"
    }

    override fun next() {
        calls += "next"
    }

    override fun stop() {
        calls += "stop"
    }
}
