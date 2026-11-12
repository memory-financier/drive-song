package com.drivesong.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    /** 최근 노래부터. */
    @Query("SELECT * FROM songs ORDER BY createdAtMs DESC")
    fun songs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun song(id: String): SongEntity?

    @Upsert
    suspend fun upsert(song: SongEntity)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}
