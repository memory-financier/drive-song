package com.drivesong.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ClipPlannerTest {

    private fun clip(id: String, member: String, slot: ClipSlot, order: Long, used: Long = 0) =
        ClipCandidate(id, member, slot, order, used)

    private val clips = listOf(
        clip("mom_intro1", "mom", ClipSlot.INTRO, 1),
        clip("mom_intro2", "mom", ClipSlot.INTRO, 2),
        clip("kid_intro", "kid", ClipSlot.INTRO, 3),
        clip("kid_call", "kid", ClipSlot.CALL, 4),
        clip("dad_outro", "dad", ClipSlot.OUTRO, 5),
    )

    @Test
    fun `only selected passengers' clips are used and empty slots are left out`() {
        val plan = ClipPlanner.plan(clips, setOf("kid"), ClipPlanMode.IN_ORDER, Random(0))
        assertEquals(mapOf(ClipSlot.INTRO to "kid_intro", ClipSlot.CALL to "kid_call"), plan)
    }

    @Test
    fun `only recent leaves out slots without a clip recorded during this trip`() {
        // 대화 노래 (설계 §7.5): 이번 주행에 녹음한 클립만 쓰고 나머지 슬롯은 서버가 대화 속 한마디로 채운다
        val withFresh = clips + ClipCandidate("kid_call_new", "kid", ClipSlot.CALL, 6, 0, recordedAtMs = 2_000)
        val plan = ClipPlanner.plan(withFresh, setOf("mom", "kid", "dad"), ClipPlanMode.IN_ORDER, Random(0),
            recordedSinceMs = 1_000, onlyRecent = true)
        assertEquals(mapOf(ClipSlot.CALL to "kid_call_new"), plan)
    }

    @Test
    fun `no passengers gives an empty plan`() {
        assertEquals(emptyMap<ClipSlot, String>(), ClipPlanner.plan(clips, emptySet(), ClipPlanMode.RANDOM, Random(0)))
    }

    @Test
    fun `plan lists slots in INTRO, CALL, OUTRO order`() {
        val plan = ClipPlanner.plan(clips, setOf("mom", "kid", "dad"), ClipPlanMode.IN_ORDER, Random(0))
        assertEquals(listOf(ClipSlot.INTRO, ClipSlot.CALL, ClipSlot.OUTRO), plan.keys.toList())
    }

    @Test
    fun `in order rotates through clips by least recent use, then recording order`() {
        var candidates = clips
        val picked = (1L..4L).map { seq ->
            val intro = ClipPlanner.plan(candidates, setOf("mom", "kid"), ClipPlanMode.IN_ORDER, Random(0))
                .getValue(ClipSlot.INTRO)
            candidates = candidates.map { if (it.clipId == intro) it.copy(lastUsedSeq = seq) else it }
            intro
        }
        assertEquals(listOf("mom_intro1", "mom_intro2", "kid_intro", "mom_intro1"), picked)
    }

    @Test
    fun `random picks one of the slot's clips and reaches every clip`() {
        val random = Random(42)
        val seen = (1..200).map {
            ClipPlanner.plan(clips, setOf("mom", "kid"), ClipPlanMode.RANDOM, random).getValue(ClipSlot.INTRO)
        }.toSet()
        assertEquals(setOf("mom_intro1", "mom_intro2", "kid_intro"), seen)
    }

    @Test
    fun `a clip recorded during this trip wins its slot, the newest one first`() {
        val tripStart = 1_000L
        val withFresh = clips.map {
            when (it.clipId) {
                "mom_intro1" -> it.copy(recordedAtMs = 1_500) // 이번 주행 중
                "kid_intro" -> it.copy(recordedAtMs = 2_000) // 이번 주행 중, 더 최근
                else -> it.copy(recordedAtMs = 500) // 지난 녹음
            }
        }
        val plan = ClipPlanner.plan(withFresh, setOf("mom", "kid", "dad"), ClipPlanMode.RANDOM, Random(0), tripStart)
        assertEquals("kid_intro", plan.getValue(ClipSlot.INTRO))
        // 이번 주행 녹음이 없는 슬롯은 기존 규칙대로 (여기서는 후보가 하나)
        assertEquals("kid_call", plan.getValue(ClipSlot.CALL))
        assertEquals("dad_outro", plan.getValue(ClipSlot.OUTRO))
    }

    @Test
    fun `a fresh clip of someone not on board is ignored`() {
        val withFresh = clips.map { if (it.clipId == "kid_intro") it.copy(recordedAtMs = 2_000) else it }
        val plan = ClipPlanner.plan(withFresh, setOf("mom"), ClipPlanMode.IN_ORDER, Random(0), recordedSinceMs = 1_000)
        assertEquals("mom_intro1", plan.getValue(ClipSlot.INTRO))
    }

    @Test
    fun `random is reproducible with the same seed`() {
        val a = ClipPlanner.plan(clips, setOf("mom", "kid", "dad"), ClipPlanMode.RANDOM, Random(7))
        val b = ClipPlanner.plan(clips, setOf("mom", "kid", "dad"), ClipPlanMode.RANDOM, Random(7))
        assertEquals(a, b)
        assertTrue(a.getValue(ClipSlot.INTRO) in setOf("mom_intro1", "mom_intro2", "kid_intro"))
    }
}
