package com.drivesong.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.drivesong.core.model.ClipSlot
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {
    @Query("SELECT * FROM members ORDER BY rowid")
    fun members(): Flow<List<MemberEntity>>

    @Insert
    suspend fun insertMember(member: MemberEntity)

    @Update
    suspend fun updateMember(member: MemberEntity)

    /** 클립 행은 외래 키 CASCADE 로 함께 지워진다. */
    @Query("DELETE FROM members WHERE id = :id")
    suspend fun deleteMember(id: String)

    @Query("SELECT * FROM clips ORDER BY localId")
    fun clips(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE memberId = :memberId")
    suspend fun clipsOf(memberId: String): List<ClipEntity>

    @Query("SELECT COUNT(*) FROM clips WHERE memberId = :memberId AND slot = :slot")
    suspend fun countClips(memberId: String, slot: ClipSlot): Int

    /** 그 구성원·슬롯에서 가장 먼저 녹음한 클립. */
    @Query("SELECT * FROM clips WHERE memberId = :memberId AND slot = :slot ORDER BY localId LIMIT 1")
    suspend fun oldestClip(memberId: String, slot: ClipSlot): ClipEntity?

    @Insert
    suspend fun insertClip(clip: ClipEntity): Long

    @Update
    suspend fun updateClip(clip: ClipEntity)

    @Query("DELETE FROM clips WHERE localId = :localId")
    suspend fun deleteClip(localId: Long)

    @Query("SELECT * FROM clips WHERE uploadState = 'UPLOADED' AND memberId IN (:memberIds)")
    suspend fun uploadedClipsOf(memberIds: Collection<String>): List<ClipEntity>

    @Query("SELECT COALESCE(MAX(lastUsedSeq), 0) FROM clips")
    suspend fun maxUsedSeq(): Long

    @Query("UPDATE clips SET lastUsedSeq = :seq WHERE serverId IN (:serverIds)")
    suspend fun markUsed(serverIds: Collection<String>, seq: Long)
}
