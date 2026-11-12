package com.drivesong.core.model

import kotlinx.serialization.Serializable

/** 가족 클립 삽입 슬롯 (설계 §7.4). */
@Serializable
enum class ClipSlot { INTRO, CALL, OUTRO }
