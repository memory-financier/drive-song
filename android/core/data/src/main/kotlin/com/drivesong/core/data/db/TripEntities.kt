package com.drivesong.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// 주행 기록 (설계 §6.2). 시각은 epoch ms. 원시 속도 샘플은 저장하지 않는다.

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endReason: String? = null, // TripEndReason.name
    val passengers: String, // 가족 구성원 id 를 쉼표로 이은 값 (서버 id "mem_<hex>" 에는 쉼표가 없다)
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val destinationName: String? = null,
    val etaAtMs: Long? = null, // 마지막으로 받은 ETA
    val summaryJson: String? = null, // 종료 시 만든 TripSummary (JSON)
)

/** 30초 / 500m 규칙으로 고른 위치 (설계 §6.2). */
@Entity(
    tableName = "trip_points",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val recordedAtMs: Long,
    val lat: Double,
    val lng: Double,
)

/** 1분 단위 속도 집계 (설계 §6.2). */
@Entity(
    tableName = "trip_speed_minutes",
    primaryKeys = ["tripId", "minuteStartMs"],
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class SpeedMinuteEntity(
    val tripId: String,
    val minuteStartMs: Long,
    val maxKmh: Double,
    val avgKmh: Double,
    val samples: Int,
)
