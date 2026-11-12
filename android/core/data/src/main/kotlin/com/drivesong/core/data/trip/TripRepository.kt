package com.drivesong.core.data.trip

import com.drivesong.core.data.db.SpeedMinuteEntity
import com.drivesong.core.data.db.TripDao
import com.drivesong.core.data.db.TripEntity
import com.drivesong.core.data.db.TripPointEntity
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.TripEndReason
import com.drivesong.core.model.TripSummary
import com.drivesong.core.model.TripSummaryBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 주행 기록 저장소 (설계 §3 TripRepository, §6.2). TripRecorder 가 쓰고 SongOrchestrator 가 요약을 읽는다. */
@Singleton
class TripRepository @Inject constructor(private val dao: TripDao) {

    /** 기록 중인 주행 id. 삭제가 기록 중인 주행을 건드리지 않게 [mutex] 로 시작·삭제를 묶는다. */
    private val mutex = Mutex()
    private var recordingTripId: String? = null

    suspend fun startTrip(id: String, startedAt: Instant, passengers: List<String>) = mutex.withLock {
        dao.insertTrip(TripEntity(id = id, startedAtMs = startedAt.toEpochMilli(), passengers = passengers.joinToString(",")))
        recordingTripId = id
    }

    suspend fun addPoint(tripId: String, recordedAt: Instant, point: GeoPoint) {
        dao.insertPoint(TripPointEntity(tripId = tripId, recordedAtMs = recordedAt.toEpochMilli(), lat = point.lat, lng = point.lng))
    }

    /** 같은 분([minuteStart])을 다시 저장하면 덮어쓴다. */
    suspend fun saveSpeedMinute(tripId: String, minuteStart: Instant, maxKmh: Double, avgKmh: Double, samples: Int) {
        dao.upsertSpeedMinute(SpeedMinuteEntity(tripId, minuteStart.toEpochMilli(), maxKmh, avgKmh, samples))
    }

    suspend fun updateRoute(tripId: String, route: RouteInfo) {
        dao.updateRoute(tripId, route.destination.lat, route.destination.lng, route.destinationName, route.etaAt?.toEpochMilli())
    }

    /**
     * 기록된 데이터로 요약을 만든다. [endedAt] == null 이면 진행 중 요약 (ETA 트리거용, 설계 §6.3).
     * 위치가 하나도 없으면(GPS 미수신) null.
     */
    suspend fun buildSummary(tripId: String, endedAt: Instant?, now: Instant, zone: ZoneId): TripSummary? {
        val trip = dao.trip(tripId) ?: return null
        val track = dao.points(tripId).map { GeoPoint(it.lat, it.lng) }
        if (track.isEmpty()) return null
        return TripSummaryBuilder.build(
            tripId = tripId,
            startedAt = Instant.ofEpochMilli(trip.startedAtMs),
            endedAt = endedAt,
            now = now,
            track = track,
            speedSamplesKmh = dao.speedMinutes(tripId).map { it.maxKmh },
            etaAt = trip.etaAtMs?.let(Instant::ofEpochMilli),
            destination = trip.destinationLat?.let { lat -> trip.destinationLng?.let { GeoPoint(lat, it) } },
            destinationName = trip.destinationName,
            passengers = trip.passengers.split(',').filter { it.isNotEmpty() },
            zone = zone,
        )
    }

    /** 주행을 끝내고 요약을 만들어 저장한다. 요약을 만들 수 없으면(위치 없음) 종료만 기록하고 null. */
    suspend fun finishTrip(tripId: String, endedAt: Instant, reason: TripEndReason, now: Instant, zone: ZoneId): TripSummary? {
        val summary = buildSummary(tripId, endedAt, now, zone)
        dao.finish(tripId, endedAt.toEpochMilli(), reason.name, summary?.let { Json.encodeToString(TripSummary.serializer(), it) })
        mutex.withLock { if (recordingTripId == tripId) recordingTripId = null }
        return summary
    }

    /**
     * 주행 기록을 모두 지운다 (설정 > 데이터 삭제). 기록 중인 주행은 TripRecorder 가 계속 쓰므로 남긴다.
     * 프로세스가 죽어 끝나지 못한 주행(endedAt 없음)도 지운다.
     */
    suspend fun deleteTripsExceptRecording() = mutex.withLock { dao.deleteAllExcept(recordingTripId) }

    /** [before] 전에 끝난 주행의 원본 위치를 지운다 (설계 §8.1 30일 보관). 기록 중인 주행은 건드리지 않는다. */
    suspend fun purgeCoordinates(before: Instant) = mutex.withLock {
        dao.deletePointsBefore(before.toEpochMilli(), recordingTripId)
    }

    /** 종료된 주행의 저장된 요약. */
    suspend fun summary(tripId: String): TripSummary? =
        dao.trip(tripId)?.summaryJson?.let { Json.decodeFromString(TripSummary.serializer(), it) }
}
