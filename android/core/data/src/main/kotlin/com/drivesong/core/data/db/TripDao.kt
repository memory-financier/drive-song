package com.drivesong.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun trip(id: String): TripEntity?

    @Query(
        "UPDATE trips SET destinationLat = :lat, destinationLng = :lng, destinationName = :name, etaAtMs = :etaAtMs " +
            "WHERE id = :id",
    )
    suspend fun updateRoute(id: String, lat: Double, lng: Double, name: String?, etaAtMs: Long?)

    @Query("UPDATE trips SET endedAtMs = :endedAtMs, endReason = :reason, summaryJson = :summaryJson WHERE id = :id")
    suspend fun finish(id: String, endedAtMs: Long, reason: String, summaryJson: String?)

    @Insert
    suspend fun insertPoint(point: TripPointEntity)

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY id")
    suspend fun points(tripId: String): List<TripPointEntity>

    /** 같은 분의 집계는 덮어쓴다 (진행 중인 분을 미리 저장했다가 나중에 갱신). */
    @Upsert
    suspend fun upsertSpeedMinute(minute: SpeedMinuteEntity)

    @Query("SELECT * FROM trip_speed_minutes WHERE tripId = :tripId ORDER BY minuteStartMs")
    suspend fun speedMinutes(tripId: String): List<SpeedMinuteEntity>

    /**
     * [keepId](기록 중인 주행) 외의 주행을 모두 지운다. 프로세스가 죽어 끝나지 못한 주행도 지운다.
     * 위치·속도 집계는 외래 키 CASCADE 로 함께 지워진다.
     */
    @Query("DELETE FROM trips WHERE :keepId IS NULL OR id != :keepId")
    suspend fun deleteAllExcept(keepId: String?)

    /**
     * [cutoffMs] 전에 끝난 주행(끝나지 못한 주행은 시작 시각 기준)의 원본 위치를 지운다 (설계 §8.1).
     * 다운샘플링된 요약(summaryJson)과 노래 커버의 경유점은 남는다.
     */
    @Query(
        "DELETE FROM trip_points WHERE tripId IN (SELECT id FROM trips " +
            "WHERE COALESCE(endedAtMs, startedAtMs) < :cutoffMs AND (:keepId IS NULL OR id != :keepId))",
    )
    suspend fun deletePointsBefore(cutoffMs: Long, keepId: String?)
}
