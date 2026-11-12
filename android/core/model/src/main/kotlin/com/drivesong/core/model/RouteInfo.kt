package com.drivesong.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** 진행 중인 경로 안내 (설계 §6.4 NavigationDataSource): 목적지, ETA, 남은 거리. */
@Serializable
data class RouteInfo(
    val destination: GeoPoint,
    val destinationName: String?,
    @Serializable(with = InstantIsoSerializer::class) val etaAt: Instant?, // 추정할 수 없으면 null
    val remainingDistanceKm: Double?, // 현재 위치를 아직 모르면 null
)
