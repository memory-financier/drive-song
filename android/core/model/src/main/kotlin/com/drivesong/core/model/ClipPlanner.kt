package com.drivesong.core.model

import kotlin.random.Random

/** 주행마다 슬롯별 클립을 고르는 방식 (설계 §8: 랜덤 또는 순서대로). */
enum class ClipPlanMode { RANDOM, IN_ORDER }

/**
 * 고를 수 있는 업로드 완료 클립.
 * @param order 녹음(저장) 순서. 작을수록 먼저 녹음한 클립.
 * @param lastUsedSeq 마지막으로 쓰인 주행 순번. 0 = 아직 안 쓰임.
 * @param recordedAtMs 녹음(저장) 시각. 0 = 모름 (v0.3 이전 클립).
 */
data class ClipCandidate(
    val clipId: String,
    val memberId: String,
    val slot: ClipSlot,
    val order: Long,
    val lastUsedSeq: Long,
    val recordedAtMs: Long = 0,
)

/** 이번 주행 탑승자의 클립 중 슬롯마다 하나씩 고른다 (설계 §8, §9 clipPlan). */
object ClipPlanner {
    /**
     * @param recordedSinceMs 이번 주행 시작 시각. 이 시각 이후 녹음한 클립이 있는 슬롯은 그중 가장 최근 것을 쓴다
     *   (주행 중 음성 녹음, 설계 §8.2). null 이면 이 규칙을 쓰지 않는다.
     * @param onlyRecent true 면 [recordedSinceMs] 이후 녹음한 클립만 고른다 (없는 슬롯은 빠짐). 대화 노래용 (설계 §7.5).
     * @return 슬롯 → clipId (INTRO, CALL, OUTRO 순서). 고를 클립이 없는 슬롯은 빠진다.
     * IN_ORDER: 가장 오래 안 쓰인 클립, 같으면 먼저 녹음한 클립 → 주행마다 돌아가며 쓰인다.
     */
    fun plan(
        candidates: List<ClipCandidate>,
        passengerIds: Set<String>,
        mode: ClipPlanMode,
        random: Random,
        recordedSinceMs: Long? = null,
        onlyRecent: Boolean = false,
    ): Map<ClipSlot, String> = ClipSlot.entries.mapNotNull { slot ->
        val pool = candidates.filter { it.slot == slot && it.memberId in passengerIds }
        if (pool.isEmpty()) return@mapNotNull null
        val fresh = recordedSinceMs?.let { since -> pool.filter { it.recordedAtMs >= since } }.orEmpty()
        val chosen = when {
            fresh.isNotEmpty() -> fresh.maxWith(compareBy({ it.recordedAtMs }, { it.order }))
            onlyRecent -> return@mapNotNull null
            mode == ClipPlanMode.RANDOM -> pool.random(random)
            else -> pool.minWith(compareBy({ it.lastUsedSeq }, { it.order }))
        }
        slot to chosen.clipId
    }.toMap()
}
