package com.metronome.app

import com.metronome.app.core.CadenceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 步频估计器回归（报警路径响应是本专项的验收重点）：
 *  - 160↔190 SPM 稳定阶跃：3 个新间隔内明显跟随，5 个新间隔内进入 ±3 SPM；
 *  - 稳定段不来回跳；单个漏步/异常间隔被中位数吸收；
 *  - 报警路径（medianSpm）与显示路径（displaySpm）都要达标。
 */
class CadenceEstimatorTest {

    private lateinit var est: CadenceEstimator

    @Before
    fun setUp() {
        est = CadenceEstimator()
    }

    private fun feed(spm: Double, intervals: Int, startMs: Long = 0) {
        var t = startMs
        est.onStep(t)
        repeat(intervals) {
            t += (60_000.0 / spm).toLong()
            est.onStep(t)
        }
    }

    @Test
    fun `median follows step change within three intervals`() {
        feed(160.0, 6)           // 稳定段
        assertEquals(160f, est.medianSpm, 1.0f)
        feed(190.0, 3, startMs = 3_000)   // 阶跃后 3 个新间隔
        assertTrue("3 个新间隔后应明显跟随（实际 %.1f）".format(est.medianSpm), est.medianSpm > 182f)
    }

    @Test
    fun `median within three spm after five intervals`() {
        feed(160.0, 6)
        feed(190.0, 5, startMs = 3_000)
        assertTrue(
            "5 个新间隔后应进入 ±3（实际 %.1f）".format(est.medianSpm),
            kotlin.math.abs(est.medianSpm - 190f) <= 3f
        )
    }

    @Test
    fun `display estimate also converges within five intervals`() {
        feed(160.0, 6)
        feed(190.0, 5, startMs = 3_000)
        assertTrue(
            "显示估计 5 个新间隔后应进入 ±3（实际 %.1f）".format(est.displaySpm),
            kotlin.math.abs(est.displaySpm - 190f) <= 3f
        )
    }

    @Test
    fun `reverse step change also fast`() {
        feed(190.0, 6)
        feed(160.0, 5, startMs = 3_000)
        assertTrue(kotlin.math.abs(est.medianSpm - 160f) <= 3f)
        assertTrue(kotlin.math.abs(est.displaySpm - 160f) <= 3f)
    }

    @Test
    fun `stable cadence stays stable`() {
        feed(175.0, 30)
        val v = est.medianSpm
        repeat(20) {
            var t = 0L
            est.onStep(t)
            for (i in 0 until 4) {
                t += 343L
                est.onStep(t)
            }
            assertTrue("稳定段估计不应来回跳（%.1f → %.1f）".format(v, est.medianSpm), kotlin.math.abs(est.medianSpm - v) < 6f)
        }
    }

    @Test
    fun `single missed step does not wreck the estimate`() {
        feed(180.0, 6)   // 间隔 333ms
        // 漏一步：出现一个 ~667ms 的双倍间隔
        est.onStep(2_333)
        est.onStep(3_000)
        est.onStep(3_333)
        // 中位数仍是正常间隔 → 估计接近 180
        assertTrue("漏步应被中位数吸收（实际 %.1f）".format(est.medianSpm), est.medianSpm > 170f)
    }

    @Test
    fun `float math no systematic downward bias`() {
        // 178.5 SPM：旧 .toInt() 截断会稳定显示 178；float 估计应保持小数
        feed(178.5, 10)
        assertTrue(est.medianSpm in 178f..179f)
    }

    @Test
    fun `reset clears everything`() {
        feed(180.0, 6)
        est.reset()
        assertEquals(0f, est.medianSpm, 0f)
        assertEquals(0f, est.displaySpm, 0f)
        est.onStep(1_000)
        est.onStep(1_333)
        assertTrue(est.medianSpm > 0f)
    }
}
