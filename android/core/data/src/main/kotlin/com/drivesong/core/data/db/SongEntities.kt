package com.drivesong.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.drivesong.core.model.GeoPoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 완성된 노래 (설계 §4.1 곡 목록, §10 보관함). 오디오는 앱 내부 저장소에 내려받아 둔다. */
@Entity(tableName = "songs")
@TypeConverters(GeoPointListConverter::class, StringListConverter::class)
data class SongEntity(
    @PrimaryKey val id: String, // 서버 songId
    val tripId: String,
    val title: String,
    val lyrics: String,
    val createdAtMs: Long, // 완성 시각 (주입된 Clock 기준: 데모 재생 중에는 시나리오 시각)
    val audioPath: String, // 내려받은 m4a 절대 경로
    val waypoints: List<GeoPoint>, // 커버(지도 썸네일)용 경로, TripSummary.waypoints
    /** 노래를 부른 가족 호칭 (완성 시점, v0.4 §7.5). 비면 AI 가수. DB v6 에서 추가 */
    @ColumnInfo(defaultValue = "[]") val singers: List<String> = emptyList(),
)

internal class GeoPointListConverter {
    private val serializer = ListSerializer(GeoPoint.serializer())

    @TypeConverter
    fun toJson(points: List<GeoPoint>): String = Json.encodeToString(serializer, points)

    @TypeConverter
    fun fromJson(json: String): List<GeoPoint> = Json.decodeFromString(serializer, json)
}

internal class StringListConverter {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun toJson(values: List<String>): String = Json.encodeToString(serializer, values)

    @TypeConverter
    fun fromJson(json: String): List<String> = Json.decodeFromString(serializer, json)
}
