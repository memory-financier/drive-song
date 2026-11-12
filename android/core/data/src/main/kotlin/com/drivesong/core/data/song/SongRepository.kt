package com.drivesong.core.data.song

import android.content.Context
import com.drivesong.core.data.db.SongDao
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.network.DriveSongApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** 완성된 노래 (설계 §7.1 READY, §10 보관함). 오디오 파일은 `files/songs/<songId>.m4a`. */
@Singleton
class SongRepository @Inject constructor(
    private val dao: SongDao,
    private val api: DriveSongApi,
    @ApplicationContext context: Context,
) {
    private val songDir = File(context.filesDir, "songs")

    /** 최근 노래부터. */
    val songs: Flow<List<SongEntity>> = dao.songs()

    suspend fun song(id: String): SongEntity? = dao.song(id)

    /** 노래 행과 내려받은 오디오 파일을 모두 지운다 (설정 > 데이터 삭제). */
    suspend fun deleteAll() {
        dao.deleteAll()
        withContext(Dispatchers.IO) { songDir.deleteRecursively() }
    }

    /**
     * GET /v1/songs/{id}/audio 를 내려받아 저장하고 노래를 Room 에 기록한다.
     * 파일 쓰기가 블로킹이므로 IO 디스패처에서 부른다. 실패하면 받던 파일을 지우고 예외.
     */
    suspend fun download(
        songId: String,
        tripId: String,
        title: String,
        lyrics: String,
        createdAt: Instant,
        waypoints: List<GeoPoint>,
        singers: List<String> = emptyList(),
    ): SongEntity {
        songDir.mkdirs()
        val file = File(songDir, "$songId.m4a")
        try {
            api.downloadSongAudio(songId).use { body -> file.outputStream().use { body.byteStream().copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        val song = SongEntity(songId, tripId, title, lyrics, createdAt.toEpochMilli(), file.absolutePath, waypoints, singers)
        dao.upsert(song)
        return song
    }
}
