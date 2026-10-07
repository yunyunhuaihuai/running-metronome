package com.metronome.app

import com.metronome.app.core.DetectorCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 检测+报警状态机回归（假时钟）。核心点：
 *  - 无数据绝不误报（WARMING_UP 与 STALE 区分）；
 *  - 报警按真实单调时间触发/解除；恢复有独立 deadline，不等下一步点；
 *  - 新鲜度 deadline 与报警开关/报警状态解耦（旧版 latched 后无 deadline 的
 *    错误前提已废除）；
 *  - 事件时间与处理时间分离：批量迟到事件按到达时间评估，过期数据不当
 *    当前数据；
 *  - 目标变化/暂停恢复重置累计 + 适应期；
 *  - fresh 边界统一（deadline 恰好 = last + timeout），无零延迟重复调度。
 */
class DetectorCoreTest {

    private var now = 0L
    private lateinit var core: DetectorCore

    private val target = 120

    @Before
    fun setUp() {
        now = 0L
        core = DetectorCore(
            { now },
            DetectorCore.Config(alarmEnabled = true, targetSpm = target)
        )
    }

    /** 按固定步频喂步点（事件时间与处理时间相同）；返回最后一个事件时刻 */
    private fun feed(spm: Int, durationMs: Long, startAt: Long = now): Long {
        val interval = 60_000.0 / spm
        var next = startAt + interval
        val end = startAt + durationMs
        while (next <= end) {
            now = next.toLong()
            core.onStep(now, now)
            next += interval
        }
        now = end
        return now
    }

    /** 只推进时钟并按间隔 evaluate（模拟 deadline/进度心跳） */
    private fun advanceEval(durationMs: Long, everyMs: Long = 100) {
        val end = now + durationMs
        var nextEval = now + everyMs
        while (now < end) {
            now += minOf(100, end - now)
            if (now >= nextEval) {
                core.evaluate(now)
                nextEval += everyMs
            }
        }
    }

    // ------------------------------------------------ 无数据不误报

    @Test
    fun `no steps never alarms even after long time`() {
        advanceEval(15_000)
        assertEquals(DetectorCore.State.WARMING_UP, core.state)
        assertFalse(core.slowAlarm)
        assertEquals(0L, core.slowProgressMs)
    }

    @Test
    fun `two steps only never validates and never alarms`() {
        now = 500; core.onStep(500, 500)
        now = 900; core.onStep(900, 900)
        advanceEval(20_000)
        assertEquals(DetectorCore.State.WARMING_UP, core.state)
        assertFalse(core.slowAlarm)
    }

    @Test
    fun `jitter steps closer than 220ms are ignored`() {
        now = 1_000; core.onStep(1_000, 1_000)
        now = 1_100; core.onStep(1_100, 1_100)
        now = 1_150; core.onStep(1_150, 1_150)
        assertEquals(1, core.stepCount)
    }

    // ------------------------------------------------ 报警触发（累计偏慢模型）

    @Test
    fun `slow cadence alarms about five seconds after slow condition met`() {
        feed(180, 3_000)              // 热身 + 稳定快跑
        val switchAt = now
        feed(100, 12_000)             // 切到慢跑
        assertTrue("应触发报警", core.slowAlarm)
        // 报警时刻由状态机诊断字段记录（输入转慢 → 新估计满足 → 累计 5s）
        val elapsed = core.alarmEnteredAtMs - switchAt
        assertTrue("报警点应在新估计 + 5s 附近（实际 ${elapsed}ms）", elapsed in 4_500..10_000)
    }

    @Test
    fun `stale after valid alarms about seven and a half seconds after last step`() {
        feed(180, 3_000)
        val lastStepAt = now
        advanceEval(9_000)
        assertEquals(DetectorCore.State.STALE, core.state)
        assertTrue(core.slowAlarm)
        // 报警时刻 = 2.5s 超时 + 5s 累计 ≈ 最后一步后 7.5s
        val since = core.alarmEnteredAtMs - lastStepAt
        assertTrue("2.5s 超时 + 5s 累计 ≈ 7.5s（实际 ${since}ms）", since in 6_900..8_500)
    }

    @Test
    fun `sparse evaluations still alarm by real time`() {
        // 只有步点驱动评估（每 600ms），不再有固定 ticker —— 真实时间语义
        feed(180, 3_000)
        val switchAt = now
        var next = now + 600.0
        val end = now + 15_000
        var alarmAt = -1L
        while (now < end) {
            now += 100
            if (now >= next) {
                core.onStep(now, now)   // 慢跑 100 SPM = 600ms 间隔
                next += 600.0
            }
            if (alarmAt < 0 && core.slowAlarm) alarmAt = now
        }
        assertTrue(alarmAt > 0)
        assertTrue(alarmAt - switchAt in 4_500..10_500)
    }

    // ------------------------------------------------ 报警解除（独立恢复 deadline）

    @Test
    fun `recovery releases alarm by deadline without waiting for next step`() {
        feed(100, 12_000)   // 慢跑触发报警
        assertTrue(core.slowAlarm)
        // 切回快跑：恢复方向开始（fastSince 设置）
        feed(180, 1_200)
        val dl = core.nextDeadlineMs()
        assertNotNull("恢复确认应有 deadline", dl)
        // 不再喂步点，直接推进到 deadline 之后 evaluate —— 必须解除
        now = maxOf(now, dl!!) + 1
        core.evaluate(now)
        assertFalse("恢复确认到点即解除，不等下一轮提示周期", core.slowAlarm)
        assertEquals(0L, core.slowProgressMs)
    }

    @Test
    fun `brief fast burst does not release alarm`() {
        feed(100, 12_000)
        assertTrue(core.slowAlarm)
        feed(180, 400)     // < 恢复确认 600ms
        assertTrue("短暂加速不应解除报警", core.slowAlarm)
        feed(100, 2_000)
        assertTrue(core.slowAlarm)
    }

    @Test
    fun `dead band freezes accumulation without resetting it`() {
        feed(100, 3_000)              // 累计 ~1.2s（热身后）
        val p1 = core.slowProgressMs
        assertTrue(p1 > 500)
        feed(115, 4_000)              // 死区（112..117)：冻结
        val frozen = core.slowProgressMs
        assertTrue("死区冻结不清零", frozen >= p1)
        feed(115, 4_000)
        assertTrue("死区内不继续累计", core.slowProgressMs == frozen)
        assertFalse(core.slowAlarm)
    }

    // ------------------------------------------------ 新鲜度与报警解耦（关键回归）

    @Test
    fun `freshness deadline exists even when alarm disabled`() {
        core.config = core.config.copy(alarmEnabled = false)
        feed(120, 2_000)
        val dl = core.nextDeadlineMs()
        assertNotNull("检测运行就应有新鲜度 deadline（与报警开关无关）", dl)
        // 推过超时 → STALE（但从不报警）
        now = dl!! + 1
        core.evaluate(now)
        assertEquals(DetectorCore.State.STALE, core.state)
        assertFalse(core.slowAlarm)
    }

    @Test
    fun `freshness deadline exists while alarm latched`() {
        feed(100, 12_000)
        assertTrue(core.slowAlarm)
        val dl = core.nextDeadlineMs()
        assertNotNull("报警已触发也必须有 deadline（旧版错误前提已废除）", dl)
        // 停步推过 deadline → 状态正确转为 STALE，报警保持
        now = dl!! + 1
        core.evaluate(now)
        assertEquals(DetectorCore.State.STALE, core.state)
        assertTrue(core.slowAlarm)
    }

    @Test
    fun `alarm disabled clears accumulation but keeps cadence display`() {
        feed(100, 3_000)
        assertTrue(core.slowProgressMs > 0)
        core.config = core.config.copy(alarmEnabled = false)
        core.evaluate(now)
        assertFalse(core.slowAlarm)
        assertEquals(0L, core.slowProgressMs)
        assertTrue("检测数据仍用于显示", core.displaySpm > 0f)
    }

    // ------------------------------------------------ 事件时间与处理时间分离

    @Test
    fun `late batch events evaluate at arrival not at historical event time`() {
        feed(180, 3_000)
        // 模拟批量迟到：三步的事件时间在过去，现在才到达
        now += 1_400
        val arrival = now
        core.onStep(arrival - 1_000, arrival)
        core.onStep(arrival - 700, arrival)
        core.onStep(arrival - 400, arrival)
        // 评估用的"当前时间"不回退：stale 判定基于 arrival 而非事件时间
        assertTrue("迟到数据仍新鲜（2.5s 内）", core.state != DetectorCore.State.STALE)
        // 但 deadline 存在且在未来（不因迟到事件产生过去的/零延迟的调度）
        val dl = core.nextDeadlineMs()
        assertNotNull(dl)
        assertTrue("deadline 应在未来（实际 dl=$dl arrival=$arrival）", dl!! > arrival)
        // 新鲜度 deadline 恰为 lastEvent+timeout（若它是最早的 deadline）
        assertTrue(dl <= arrival - 400 + 2_500)
    }

    @Test
    fun `stale events arriving later are not treated as current`() {
        feed(180, 3_000)
        val lastEvent = now
        now += 3_000   // 3 秒没有步点（> 2.5s 超时）
        // 一个迟到 3s 的事件此刻才到达
        core.onStep(lastEvent + 400, now)
        assertEquals("过期事件不能把状态拉回 VALID", DetectorCore.State.STALE, core.state)
    }

    @Test
    fun `future timestamps clamp to arrival`() {
        now = 1_000
        core.onStep(5_000, 1_000)   // 未来时间戳 → 钳到当前
        now = 1_300
        core.onStep(1_300, 1_300)
        assertTrue(core.stepCount >= 1)
    }

    // ------------------------------------------------ 目标变化与适应期

    @Test
    fun `target change clears accrual and grants grace`() {
        feed(100, 3_000)
        assertTrue(core.slowProgressMs > 0)
        core.config = core.config.copy(targetSpm = 150)
        core.onTargetChanged(now)
        assertEquals(0L, core.slowProgressMs)
        // 适应期内慢跑不累计
        feed(100, 1_000)
        assertEquals("适应期内不应累计", 0L, core.slowProgressMs)
        assertFalse(core.slowAlarm)
        // 适应期后恢复累计
        feed(100, 7_000)
        assertTrue(core.slowProgressMs > 0)
    }

    @Test
    fun `resume from pause gets grace via onTargetChanged`() {
        feed(100, 3_000)
        assertTrue(core.slowProgressMs > 0)
        core.onTargetChanged(now)   // 暂停→继续时服务层调用
        assertEquals(0L, core.slowProgressMs)
    }

    // ------------------------------------------------ deadline 供给

    @Test
    fun `no deadline when never any step`() {
        assertNull(core.nextDeadlineMs())
        advanceEval(3_000)
        assertNull(core.nextDeadlineMs())
    }

    @Test
    fun `deadline while valid fast is stale timeout`() {
        // 用死区步频（114 ∈ [112,117)）：不累计、不进入恢复确认 → 唯一 deadline 是步伐超时
        feed(114, 3_000)
        core.evaluate(now)   // 收尾评估，稳定 lastEval
        val ahead = core.nextDeadlineMs()!! - now
        assertTrue("VALID 时 deadline 应为步伐超时（剩 ${ahead}ms）", ahead in 1_800..2_600)
    }

    @Test
    fun `deadline while accruing within remaining alarm time`() {
        feed(100, 4_000)
        val ahead = core.nextDeadlineMs()!! - now
        assertTrue("报警 deadline 应在剩余累计时间内（剩 ${ahead}ms）", ahead in 0..4_000)
    }

    @Test
    fun `fresh boundary has no zero delay reschedule`() {
        feed(120, 2_000)
        core.evaluate(now)   // 收尾评估，稳定 lastEval（进度心跳被钳制到未来）
        // 恰好在 lastStep + timeout 时刻：fresh 用严格 <，应转 STALE 而非紧密重调度
        now += 2_500
        core.evaluate(now)
        assertEquals(DetectorCore.State.STALE, core.state)
        // STALE 且报警关闭后无任何 deadline（无零延迟重复调度）
        core.config = core.config.copy(alarmEnabled = false)
        core.evaluate(now)
        assertNull(core.nextDeadlineMs())
    }

    // ------------------------------------------------ 恢复跑步重新热身

    @Test
    fun `resume after stale rewarms then recovers alarm`() {
        feed(180, 3_000)
        advanceEval(8_000)   // 停止 8s → STALE 且报警
        assertTrue(core.slowAlarm)
        assertEquals(DetectorCore.State.STALE, core.state)
        // 恢复快跑：重新热身后 VALID，恢复条件达成即解除
        feed(180, 2_500)
        assertEquals(DetectorCore.State.VALID, core.state)
        assertFalse("恢复快跑后应解除报警", core.slowAlarm)
        assertEquals(0L, core.slowProgressMs)
    }
}
