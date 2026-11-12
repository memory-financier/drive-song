package com.drivesong.core.vehicle.demo

import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.model.haversineKm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 데모 주행 시나리오 (assets/scenarios 의 JSON).
 * 기준점 사이는 시간에 따라 선형 보간한다. 각 기준점의 [ScenarioPoint.t] 는 구간 거리 ÷ 양 끝 속도 평균으로
 * 정해 두었으므로 위치 이동과 속도가 서로 맞는다. 마지막 점(P단, 목적지)에 도착한 뒤 [parkHoldSec] 동안 정차한다.
 */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val destinationName: String,
    /** 출발 현지 시각 "HH:mm". 재생하는 날짜 + 기기 시간대로 시나리오 시각을 만든다. */
    val startLocalTime: String,
    val parkHoldSec: Long,
    val points: List<ScenarioPoint>,
) {
    init {
        require(points.size >= 2) { "scenario needs at least 2 points" }
        require(points.zipWithNext().all { (a, b) -> b.t > a.t }) { "point times must increase" }
    }

    /** 목적지 도착(마지막 점) 시각, 출발 후 초. */
    val arrivalSec: Long get() = points.last().t

    /** 도착 후 정차까지 포함한 전체 길이(초). */
    val totalSec: Long get() = arrivalSec + parkHoldSec

    val destination: GeoPoint get() = points.last().point

    /** 출발점부터 각 기준점까지의 누적 거리 (km). */
    private val cumulativeKm: List<Double> by lazy {
        points.zipWithNext { a, b -> haversineKm(a.point, b.point) }.runningFold(0.0, Double::plus)
    }

    val routeLengthKm: Double get() = cumulativeKm.last()

    fun startInstant(date: LocalDate, zone: ZoneId): Instant =
        LocalTime.parse(startLocalTime).atDate(date).atZone(zone).toInstant()

    /** 출발 후 [elapsedSec] 초 시점의 상태. 경로 안내는 도착하면 끝난다(null). */
    fun frameAt(elapsedSec: Double, startAt: Instant): ScenarioFrame {
        val sec = elapsedSec.coerceIn(0.0, totalSec.toDouble())
        val i = points.indexOfLast { it.t <= sec }
        val a = points[i]
        val b = points.getOrNull(i + 1)
        val f = if (b == null) 0.0 else (sec - a.t) / (b.t - a.t)
        val location = if (b == null) a.point else GeoPoint(lerp(a.lat, b.lat, f), lerp(a.lng, b.lng, f))
        val route = b?.let {
            val travelledKm = lerp(cumulativeKm[i], cumulativeKm[i + 1], f)
            RouteInfo(
                destination = destination,
                destinationName = destinationName,
                etaAt = startAt.plusSeconds(arrivalSec),
                remainingDistanceKm = routeLengthKm - travelledKm,
            )
        }
        return ScenarioFrame(
            at = startAt.plusMillis((sec * 1000).toLong()),
            elapsedSec = sec,
            location = location,
            speedKmh = if (b == null) a.speedKmh else lerp(a.speedKmh.toDouble(), b.speedKmh.toDouble(), f).toFloat(),
            gear = a.gear,
            route = route,
        )
    }

    companion object {
        fun parse(text: String): Scenario = Json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ScenarioPoint(
    val t: Long, // 출발 후 초
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val gear: Gear, // 다음 기준점까지 유지
    val label: String? = null, // 사람이 읽기 위한 지명
) {
    val point: GeoPoint get() = GeoPoint(lat, lng)
}

/** 재생 중 한 시점의 차량 상태. [at] 은 시나리오 시각 (가상 시계). */
data class ScenarioFrame(
    val at: Instant,
    val elapsedSec: Double,
    val location: GeoPoint,
    val speedKmh: Float,
    val gear: Gear,
    val route: RouteInfo?,
)

private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
