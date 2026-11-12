package com.drivesong.core.network.dto

import com.drivesong.core.model.ClipSlot
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.InstantIsoSerializer
import com.drivesong.core.model.TimeOfDay
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.Instant

// JSON 필드는 camelCase (설계 §9). devices/members/clips 형식은 server/app/api/*.py 기준.
// createdAt 은 서버 datetime ISO 문자열 그대로 둔다 (도메인 변환은 사용하는 쪽에서).

@Serializable
data class DeviceDto(val deviceId: String, val token: String)

@Serializable
data class MemberRequest(
    val nickname: String,
    val isChild: Boolean,
    val voiceConsent: Boolean,
    val guardianConsent: Boolean,
    // v0.4 선택 동의 (설계 §8.3)
    val conversationConsent: Boolean = false,
    val voiceConversionConsent: Boolean = false,
)

@Serializable
data class MemberDto(
    val memberId: String,
    val nickname: String,
    val isChild: Boolean,
    val voiceConsent: Boolean,
    val guardianConsent: Boolean,
    val createdAt: String,
    val conversationConsent: Boolean = false,
    val voiceConversionConsent: Boolean = false,
)

/** PATCH /v1/members/{id} (설계 §9.1). 변환 동의를 끄면 서버가 참고 목소리를 바로 지운다. */
@Serializable
data class MemberConsentUpdate(val conversationConsent: Boolean, val voiceConversionConsent: Boolean)

/** POST /v1/trips/{tripId}/conversation 응답. */
@Serializable
data class ConversationChunkDto(val tripId: String, val seq: Int, val durationSec: Double)

@Serializable
data class ClipDto(
    val clipId: String,
    val memberId: String,
    val slot: ClipSlot,
    val durationSec: Double,
    val createdAt: String,
)

/**
 * POST /v1/trips/enrich 응답 = TripSummary 의 모든 필드 + 지명·날씨 (server/app/api/trips.py EnrichedTrip).
 * POST /v1/lyrics 에 그대로 다시 보내므로 TripSummary 필드를 모두 가진다.
 */
@Serializable
data class EnrichedTripDto(
    val tripId: String,
    @Serializable(with = InstantIsoSerializer::class) val startedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class) val endedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val etaAt: Instant? = null,
    val origin: GeoPoint,
    val destination: GeoPoint? = null,
    val destinationName: String? = null, // 요청 값, 없으면 서버 역지오코딩 결과
    val waypoints: List<GeoPoint> = emptyList(),
    val distanceKm: Double,
    val durationMin: Int,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val timeOfDay: TimeOfDay,
    val passengers: List<String> = emptyList(),
    val originName: String? = null,
    val placeNames: List<String> = emptyList(), // 경로 순서, 최대 5개
    val weather: WeatherDto? = null, // 도착 지점·도착 시각 기준
)

@Serializable
data class WeatherDto(val description: String, val temperatureC: Double)

/** POST /v1/lyrics 요청 (server/app/api/lyrics.py LyricsIn). style = [com.drivesong.core.model.SongStyle.wire]. */
@Serializable
data class LyricsRequest(
    val enrichedTrip: EnrichedTripDto,
    val familyNicknames: List<String>,
    val style: String,
)

/** POST /v1/lyrics 응답. title = 도착지가 있으면 "<도착지> 가는 길". */
@Serializable
data class LyricsDto(val lyrics: String, val title: String? = null)

/**
 * POST /v1/song-jobs 요청 (설계 §9 예시). style = [com.drivesong.core.model.SongStyle.wire], durationSec 60~90.
 * title 은 서버 확장 필드 (없으면 서버 기본 제목). null 이면 보내지 않는다.
 * v0.4 대화 잡 (설계 §9.1): conversation = true, enrichedTrip 필수, lyrics 는 비우면 서버가 대화로 쓴다.
 */
@OptIn(ExperimentalSerializationApi::class) // @EncodeDefault
@Serializable
data class SongJobRequest(
    val tripId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lyrics: String? = null,
    val style: String,
    val durationSec: Int,
    val clipPlan: List<ClipPlanItem>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: String? = null,
    val conversation: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val enrichedTrip: EnrichedTripDto? = null,
)

@Serializable
data class ClipPlanItem(val slot: ClipSlot, val clipId: String)

/** GET /v1/song-jobs/{id} 응답 (설계 §9 예시). audioUrl 은 서버 기준 상대 경로. */
@Serializable
data class SongJobDto(
    val jobId: String,
    val status: SongJobStatus,
    val songId: String? = null,
    val audioUrl: String? = null,
    val title: String? = null,
    val error: String? = null,
    /** 대화 잡은 서버가 쓴 가사 (분석 뒤에 채워짐). */
    val lyrics: String? = null,
    /** 노래를 부른 구성원 id (READY 일 때만, 비면 AI 가수). */
    val singers: List<String> = emptyList(),
)

/** ANALYZING(받아 적기·화자·가사)·CONVERTING(가족 목소리 변환)은 대화 잡에만 (설계 §9.1). */
@Serializable
enum class SongJobStatus { QUEUED, ANALYZING, GENERATING, CONVERTING, MIXING, READY, FAILED }
