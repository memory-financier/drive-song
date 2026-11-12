package com.drivesong.core.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 기록된 주행 데이터 → [TripSummary] (설계 §6.3). 순수 함수. */
object TripSummaryBuilder {
    const val MAX_WAYPOINTS = 50

    /**
     * @param track 기록된 위치 (시간순, 첫 점 = 출발지). 거리는 다운샘플링 전 전체 경로로 계산한다.
     * @param speedSamplesKmh 최고 속도 계산용 표본 (예: 1분 단위 최고 속도).
     * @param now 진행 중(endedAt == null)일 때 소요 시간 계산 기준 시각.
     * @param zone 시간대 구분([timeOfDayOf]) 기준. 출발 시각의 현지 시각으로 정한다.
     */
    fun build(
        tripId: String,
        startedAt: Instant,
        endedAt: Instant?,
        now: Instant,
        track: List<GeoPoint>,
        speedSamplesKmh: List<Double>,
        etaAt: Instant? = null,
        destination: GeoPoint? = null,
        destinationName: String? = null,
        passengers: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): TripSummary {
        require(track.isNotEmpty()) { "track must contain at least the origin" }
        val durationSec = Duration.between(startedAt, endedAt ?: now).seconds.coerceAtLeast(0)
        val distanceKm = pathLengthKm(track)
        return TripSummary(
            tripId = tripId,
            startedAt = startedAt,
            endedAt = endedAt,
            etaAt = etaAt,
            origin = track.first(),
            destination = destination,
            destinationName = destinationName,
            waypoints = downsample(track),
            distanceKm = round1(distanceKm),
            durationMin = (durationSec / 60.0).roundToInt(),
            // 평균 속도 = 전체 거리 / 전체 소요 시간 (정차 시간 포함)
            avgSpeedKmh = if (durationSec > 0) round1(distanceKm / (durationSec / 3600.0)) else 0.0,
            maxSpeedKmh = round1(speedSamplesKmh.maxOrNull() ?: 0.0),
            timeOfDay = timeOfDayOf(startedAt.atZone(zone).hour),
            passengers = passengers,
        )
    }

    /** 첫 점과 마지막 점을 포함해 고르게 [max]개 이하로 줄인다. */
    fun downsample(track: List<GeoPoint>, max: Int = MAX_WAYPOINTS): List<GeoPoint> {
        require(max >= 2)
        if (track.size <= max) return track
        return List(max) { i -> track[(i.toLong() * (track.size - 1) / (max - 1)).toInt()] }
    }

    /**
     * 현지 시각(시) → 시간대.
     * DAWN 04:00–06:59, MORNING 07:00–11:59, AFTERNOON 12:00–16:59, EVENING 17:00–20:59, NIGHT 21:00–03:59.
     */
    fun timeOfDayOf(hour: Int): TimeOfDay = when (hour) {
        in 4..6 -> TimeOfDay.DAWN
        in 7..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..20 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }

    private fun round1(v: Double) = (v * 10).roundToLong() / 10.0
}
