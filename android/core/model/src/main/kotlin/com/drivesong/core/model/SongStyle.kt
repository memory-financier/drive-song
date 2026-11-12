package com.drivesong.core.model

/** 노래 스타일 프리셋 (설계 §7.3). [wire] = 서버 API 값 (server/app/providers/music/base.py Style). */
enum class SongStyle(val wire: String) {
    KIDS("kids"), // 동요
    CITY_POP("city_pop"), // 시티팝
    ACOUSTIC("acoustic"), // 어쿠스틱
    ROAD_TRIP("road_trip"), // 신나는 로드트립
}
