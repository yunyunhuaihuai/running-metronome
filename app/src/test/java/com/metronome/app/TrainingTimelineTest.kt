package com.metronome.app

import com.metronome.app.core.LoopSpec
import com.metronome.app.core.TrainingConfig
import com.metronome.app.core.TrainingSegment
import com.metronome.app.core.TrainingTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 训练时间线回归。固定计划：热身 120s → (训练 60s + 恢复 30s) × 3 轮 → 冷身 120s，
 * 自然总时长 510s（用户确认的验收算例）。
 *
 * 覆盖：定时/分段四组合、准备不计训练、循环只重复指定连续段、
 * 总时长硬上限截断、上限与段边界重合只结束一次、跨多段跳变定位正确。
 */
class TrainingTimelineTest {

    private val plan = TrainingConfig(
        segments = listOf(
            TrainingSegment("热身", 120, 160),
            TrainingSegment("训练", 60, 180),
            TrainingSegment("恢复", 30, 170),
            TrainingSegment("冷身", 120, 150),
        ),
        loop = LoopSpec(startIndex = 1, endIndexInclusive = 2, rounds = 3),
    )

    @Test
    fun `example plan natural total is 510 seconds`() {
        assertEquals(510_000, plan.naturalTotalMs())
        assertEquals(510_000, TrainingTimeline(plan).totalNaturalMs())
    }

    @Test
    fun `loop repeats only the specified group`() {
        val order = plan.executionOrder()
        assertEquals(listOf(0, 1, 2, 1, 2, 1, 2, 3), order.map { it.first })
        assertEquals(listOf(1, 1, 1, 2, 2, 3, 3, 1), order.map { it.second })
    }

    @Test
    fun `locate within first segment`() {
        val tl = TrainingTimeline(plan)
        val p = tl.locate(5_000)
        assertEquals(0, p.segmentIndex)
        assertEquals(5_000, p.segmentElapsedMs)
        assertEquals(115_000, p.segmentRemainingMs)
        assertEquals(160, p.segmentTargetSpm)
        assertEquals(1, p.round)
        assertFalse(p.finished)
    }

    @Test
    fun `locate round boundaries`() {
        val tl = TrainingTimeline(plan)
        // 热身 120s + 第 1 轮训练 60s = 180s
        val p = tl.locate(180_000)
        assertEquals(2, p.segmentIndex)   // 恢复段
        assertEquals(1, p.round)
        // 第 1 轮结束（120+90=210s）→ 第 2 轮训练开始
        val p2 = tl.locate(210_000)
        assertEquals(1, p2.segmentIndex)
        assertEquals(2, p2.round)
        // 最后一轮结束（120+270=390s）→ 冷身
        val p3 = tl.locate(390_000)
        assertEquals(3, p3.segmentIndex)
        assertEquals(1, p3.round)
    }

    @Test
    fun `natural finish at 510`() {
        val tl = TrainingTimeline(plan)
        val p = tl.locate(510_000)
        assertTrue(p.finished)
        assertFalse(p.finishedByCap)
        val pBefore = tl.locate(509_999)
        assertFalse(pBefore.finished)
        assertEquals(3, pBefore.segmentIndex)
    }

    @Test
    fun `cap 400 ends 10s into cooldown`() {
        val capped = plan.copy(timerEnabled = true, totalDurationSec = 400)
        val tl = TrainingTimeline(capped)
        val p = tl.locate(400_000)
        assertTrue(p.finished)
        assertTrue(p.finishedByCap)
        // 400s = 热身 120 + 三轮 270 + 冷身 10s
        val pJustBefore = tl.locate(399_999)
        assertEquals(3, pJustBefore.segmentIndex)
        assertEquals(9_999, pJustBefore.segmentElapsedMs)
    }

    @Test
    fun `cap 300 coincides with round 2 end - single finish no round 3`() {
        val capped = plan.copy(timerEnabled = true, totalDurationSec = 300)
        val tl = TrainingTimeline(capped)
        // 120 + 90 + 90 = 300 恰为第 2 轮结束
        val p = tl.locate(300_000)
        assertTrue(p.finished)
        assertTrue(p.finishedByCap)
        // 不存在第 3 轮的任何位置被误报为训练中
        val pMid = tl.locate(299_999)
        assertEquals(2, pMid.segmentIndex)   // 第 2 轮恢复段
        assertFalse(pMid.finished)
    }

    @Test
    fun `cap longer than plan means natural finish`() {
        val capped = plan.copy(timerEnabled = true, totalDurationSec = 600)
        val tl = TrainingTimeline(capped)
        val p = tl.locate(510_000)
        assertTrue(p.finished)
        assertFalse(p.finishedByCap)
    }

    @Test
    fun `big jump across multiple boundaries locates correctly`() {
        val tl = TrainingTimeline(plan)
        // 长时间未调度后一次跨越多段（如线程停 5 分钟）
        val p = tl.locate(120_000 + 270_000 + 65_000)   // 冷身开始后 65s（已超冷身？否，冷身 120s）
        assertEquals(3, p.segmentIndex)
        assertEquals(65_000, p.segmentElapsedMs)
        val pFinished = tl.locate(120_000 + 270_000 + 120_000 + 3_000)
        assertTrue(pFinished.finished)
        assertFalse(pFinished.finishedByCap)
    }

    // ------------------------------------------------ 组合

    @Test
    fun `timer only mode has no segments`() {
        val cfg = TrainingConfig(timerEnabled = true, totalDurationSec = 300)
        val tl = TrainingTimeline(cfg)
        val p = tl.locate(299_000)
        assertEquals(-1, p.segmentIndex)
        assertEquals(1_000L, p.totalRemainingMs)
        assertFalse(p.finished)
        assertTrue(tl.locate(300_000).finished)
    }

    @Test
    fun `plain count-up never finishes and has no cap`() {
        val cfg = TrainingConfig()
        val tl = TrainingTimeline(cfg)
        assertNull(cfg.effectiveCapMs())
        val p = tl.locate(10 * 60_000L)
        assertFalse(p.finished)
        assertNull(p.totalRemainingMs)
    }

    @Test
    fun `timer off segments on finishes naturally in order`() {
        val cfg = TrainingConfig(
            segments = listOf(
                TrainingSegment("A", 30, 120),
                TrainingSegment("B", 45, 140),
            ),
        )
        val tl = TrainingTimeline(cfg)
        assertTrue(tl.locate(75_000).finished)
        val mid = tl.locate(40_000)
        assertEquals(1, mid.segmentIndex)
        assertEquals(10_000, mid.segmentElapsedMs)
    }

    @Test
    fun `single round loop equals plain sequence`() {
        val cfg = TrainingConfig(
            segments = listOf(
                TrainingSegment("A", 30, 120),
                TrainingSegment("B", 45, 140),
            ),
            loop = LoopSpec(0, 1, 1),
        )
        assertEquals(75_000, cfg.naturalTotalMs())
    }

    @Test
    fun `validation rejects bad configs`() {
        assertTrue(
            TrainingConfig(timerEnabled = true, totalDurationSec = 0).validate().isNotEmpty()
        )
        assertTrue(
            TrainingConfig(segments = listOf(TrainingSegment("A", 0, 120))).validate().isNotEmpty()
        )
        assertTrue(
            TrainingConfig(
                segments = listOf(TrainingSegment("A", 30, 120)),
                loop = LoopSpec(0, 0, 1),
            ).validate().isEmpty()
        )
        assertTrue(
            TrainingConfig(
                segments = listOf(TrainingSegment("A", 30, 120), TrainingSegment("B", 30, 130)),
                loop = LoopSpec(0, 5, 1),
            ).validate().isNotEmpty()
        )
    }
}
