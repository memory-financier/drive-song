package com.drivesong.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drivesong.core.model.ClipSlot

// 가족 구성원과 목소리 클립 (설계 §8). 구성원은 서버 등록(동의 포함)이 끝난 뒤에만 저장한다.

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey val id: String, // 서버 memberId ("mem_<hex>")
    val nickname: String,
    val isChild: Boolean,
    val voiceConsent: Boolean,
    val guardianConsent: Boolean,
    // v0.4 선택 동의 (설계 §8.3). DB v6 에서 추가 (기존 행은 false)
    @ColumnInfo(defaultValue = "0") val conversationConsent: Boolean = false,
    @ColumnInfo(defaultValue = "0") val voiceConversionConsent: Boolean = false,
)

enum class UploadState { PENDING, UPLOADED, FAILED }

/** 녹음 클립. 먼저 로컬에 저장(PENDING)한 뒤 업로드한다. 구성원 행이 지워지면 함께 지워진다. */
@Entity(
    tableName = "clips",
    foreignKeys = [ForeignKey(MemberEntity::class, ["id"], ["memberId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("memberId")],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0, // 녹음(저장) 순서
    val memberId: String,
    val slot: ClipSlot,
    val localPath: String,
    val serverId: String? = null, // 서버 clipId ("clip_<hex>"), 업로드 후 채워짐
    val uploadState: UploadState = UploadState.PENDING,
    val lastUsedSeq: Long = 0, // 마지막으로 쓰인 clipPlan 순번 (0 = 안 쓰임). 순서대로 고르기용
    val recordedAtMs: Long = 0, // 저장 시각 (주입 Clock, 데모 중에는 시나리오 시각). 이번 주행 녹음 우선용 (설계 §8.2)
)
