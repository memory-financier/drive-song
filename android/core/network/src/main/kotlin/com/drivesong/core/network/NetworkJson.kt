package com.drivesong.core.network

import kotlinx.serialization.json.Json

/** 서버와 주고받는 JSON 설정. 서버가 필드를 추가해도 깨지지 않게 모르는 키는 무시한다. */
val NetworkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
