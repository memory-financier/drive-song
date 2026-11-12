package com.drivesong.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** 서버로 보내는 주행 요약 (설계 §6.3). */
@Serializable
data class TripSummary(
    val tripId: String,
    @Serializable(with = InstantIsoSerializer::class) val startedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class) val endedAt: Instant?, // 진행 중이면 null (ETA 트리거 시)
    @Serializable(with = InstantIsoSerializer::class) val etaAt: Instant?,
    val origin: GeoPoint,
    val destination: GeoPoint?,
    val destinationName: String?, // NaviHelper 제공 시
    val waypoints: List<GeoPoint>, // 다운샘플링 (최대 50개)
    val distanceKm: Double,
    val durationMin: Int,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val timeOfDay: TimeOfDay,
    val passengers: List<String>, // 이번 주행에 탄 가족 구성원 id
)

// [확인 필요] 서버 TripSummary의 좌표 필드명 (lat/lng 가정, 서버 T5 구현과 맞출 것)
@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

@Serializable
enum class TimeOfDay { DAWN, MORNING, AFTERNOON, EVENING, NIGHT }
