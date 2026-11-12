package com.drivesong.core.model

import kotlinx.serialization.Serializable

/** 기어 상태 (설계 §6.1). 수동 1~9단은 DRIVE 로 본다. P단 전환은 도착 판정 보조에 쓴다. */
@Serializable
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
