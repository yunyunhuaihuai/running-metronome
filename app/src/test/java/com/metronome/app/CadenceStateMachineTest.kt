package com.metronome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 步频报警状态机测试。核心回归点：
 *  - 无 step 数据时绝不因 cadence=0 在 5 秒后误报警（WARMING_UP）；
 *  - 报警按真实单调时间触发，评估调度稀疏/延迟不会提前也不会大幅滞后；
 *  - STALE（曾有效后停止）与"从未有效"行为区分；
 *  - 死区冻结、恢复条件、BPM=0 清零、deadline 供给正确。
 */
class CadenceStateMachineTest {

    private var now = 0L
    private lateinit var machine: CadenceStateMachine

    private val target = 120

    @Before
    fun setUp() {
        now = 0L
        machine = CadenceStateMachine { now }
    }

    /** 100ms 步长推进模拟时钟；spm != null 时按该步频喂步点（onStep 内部会评估） */
    private fun advance(durationMs: Long, spm: Int?, evalEveryMs: Long = 100) {
        val end = now + durationMs
        var nextStepAt = if (spm != null) now + (60_000.0 / spm).toLong() else Long.MAX_VALUE
        var nextEvalAt = now + evalEveryMs
        while (now < end) {
            now += 100
            if (spm != null && now >= nextStepAt) {
                machine.onStep(now, target, true)
                nextStepAt += (60_000.0 / spm).toLong()
            }
            if (now >= nextEvalAt) {
                machine.evaluate(target, true)
                nextEvalAt += evalEveryMs
            }
        }
    }

    /** 快跑 3s 后切慢跑，逐 100ms 重放，返回 (慢速起始时刻, 报警时刻) */
    private fun replaySlowAlarm(evalEveryMs: Long): Pair<Long, Long> {
        now = 0L
        machine.reset()
        advance(3_000, spm = 180)
        var slowBegin = -1L
        var alarmAt = -1L
        // 5s 滑窗从 180 过渡到 100 SPM 约需 4.2s 才读到 <112，报警点在其后 5s，
        // 因此模拟窗口需覆盖到快跑结束 +12s
        val end = 3_000L + 12_000L
        var nextStepAt = 3_000L + 600L
        var nextEvalAt = evalEveryMs
        while (now < end) {
            now += 100
            if (now >= nextStepAt) {
                machine.onStep(now, target, true)
                nextStepAt += 600L
            }
            if (now >= nextEvalAt) {
                machine.evaluate(target, true)
                nextEvalAt += evalEveryMs
            }
            if (slowBegin < 0 && machine.state == CadenceStateMachine.State.VALID &&
                machine.cadenceSpm in 1..(target - 9)
            ) slowBegin = now
            if (alarmAt < 0 && machine.slowAlarm) alarmAt = now
        }
        return slowBegin to alarmAt
    }

    // ------------------------------------------------ 无数据不误报

    @Test
    fun `no steps at all never alarms even after long time`() {
        advance(15_000, spm = null)
        assertEquals(CadenceStateMachine.State.WARMING_UP, machine.state)
        assertFalse(machine.slowAlarm)
        assertEquals(0, machine.slowProgressSec)
    }

    @Test
    fun `two steps only never validates and never alarms`() {
        now = 500; machine.onStep(500, target, true)
        now = 900; machine.onStep(900, target, true)
        advance(20_000, spm = null)
        assertEquals(CadenceStateMachine.State.WARMING_UP, machine.state)
        assertFalse(machine.slowAlarm)
    }

    @Test
    fun `jitter steps closer than 220ms are ignored`() {
        now = 1_000; machine.onStep(1_000, target, true)
        now = 1_100; machine.onStep(1_100, target, true)
        now = 1_150; machine.onStep(1_150, target, true)
        assertEquals(1, machine.stepCount)
    }

    // ------------------------------------------------ 正常慢速报警时序

    @Test
    fun `slow cadence alarms after real five seconds`() {
        val (slowBegin, alarmAt) = replaySlowAlarm(evalEveryMs = 100)
        assertTrue("应当进入慢速并报警", slowBegin > 0 && alarmAt > 0)
        val elapsed = alarmAt - slowBegin
        assertTrue("报警应发生在真实慢速 5s（实际 ${elapsed}ms）", elapsed in 4_800..5_400)
    }

    @Test
    fun `sparse evaluations keep real time accuracy`() {
        // 只靠步点（每 600ms）驱动评估，相当于调度非常稀疏；
        // 老的"每 tick += 200ms"计数模型此时会把 5 秒拖成 15 秒。
        // 上界放宽到 7s：滑窗过渡中步频可能在阈值附近短暂反弹进死区，
        // 累计会短暂冻结后继续（真实时间语义不变）。
        val (slowBegin, alarmAt) = replaySlowAlarm(evalEveryMs = 1_000_000)
        assertTrue(slowBegin > 0 && alarmAt > 0)
        val elapsed = alarmAt - slowBegin
        assertTrue("稀疏评估下仍应按真实时间报警（实际 ${elapsed}ms）", elapsed in 4_900..7_000)
    }

    // ------------------------------------------------ STALE / 停止

    @Test
    fun `stop after valid alarms about seven and a half seconds after last step`() {
        advance(3_000, spm = 180)
        val lastStepAt = now // 最后一步在阶段末尾附近
        var alarmAt = -1L
        val end = now + 9_000L
        var nextEvalAt = 100L
        while (now < end) {
            now += 100
            if (now >= nextEvalAt) {
                machine.evaluate(target, true)
                nextEvalAt += 100L
            }
            if (alarmAt < 0 && machine.slowAlarm) alarmAt = now
        }
        assertEquals(CadenceStateMachine.State.STALE, machine.state)
        assertTrue(alarmAt > 0)
        val sinceLast = alarmAt - lastStepAt
        // 2.5s 停止超时 + 5s 累计 ≈ 7.5s
        assertTrue("停止后报警应在 7.5s 左右（实际 ${sinceLast}ms）", sinceLast in 6_900..8_300)
    }

    @Test
    fun `stop without ever being valid never alarms`() {
        // 100SPM 步间隔 600ms，只跑 1.4s → 仅 2 步，从未形成可信步频
        advance(1_400, spm = 100)
        advance(20_000, spm = null)
        assertEquals(CadenceStateMachine.State.WARMING_UP, machine.state)
        assertFalse(machine.slowAlarm)
    }

    @Test
    fun `resume after stale rewarms then recovers`() {
        advance(3_000, spm = 180)
        advance(8_000, spm = null) // 停止 8s → STALE（约 5.5s 时已报警）
        assertTrue(machine.slowAlarm)
        assertEquals(CadenceStateMachine.State.STALE, machine.state)

        // 恢复快跑：重新热身期间不继续累计也不误报，VALID 后 600ms 内解除
        advance(2_000, spm = 180)
        assertEquals(CadenceStateMachine.State.VALID, machine.state)
        assertFalse("恢复快跑后应解除报警", machine.slowAlarm)
        assertEquals(0, machine.slowProgressSec)
    }

    // ------------------------------------------------ 死区与恢复

    @Test
    fun `dead band cadence does not accrue and never alarms`() {
        // 115 SPM ∈ [target-8, target-3) = [112, 117) → 死区
        advance(15_000, spm = 115)
        assertFalse(machine.slowAlarm)
        assertEquals(0, machine.slowProgressSec)
    }

    @Test
    fun `dead band freezes accumulation without resetting it`() {
        // A: 慢跑积累约 1.2s
        advance(3_000, spm = 100)
        assertEquals(1, machine.slowProgressSec)
        // B: 停步 3s：前 2.5s 仍 VALID（滑窗步频保持慢，正确地继续累计），
        //    2.5s 后进入 STALE，累计约 4.2s
        advance(3_000, spm = null)
        assertEquals(4, machine.slowProgressSec)
        assertFalse("累计未满 5s 不应报警", machine.slowAlarm)
        // C: 恢复 115SPM：STALE 后重新热身（WARMING_UP）期间累计必须冻结——
        //    既不清零（恢复重新热身的几十毫秒不应重置报警进度），也不再增长
        var frozeAtWarmup = -1
        var nextStepAt = now + 521L
        val end = now + 6_000L
        while (now < end) {
            now += 100
            if (now >= nextStepAt) {
                machine.onStep(now, target, true)
                nextStepAt += 521L
            }
            if (frozeAtWarmup < 0 && now >= 6_700L &&
                machine.state == CadenceStateMachine.State.WARMING_UP
            ) frozeAtWarmup = machine.slowProgressSec
        }
        assertTrue("应观察到重新热身窗口", frozeAtWarmup > 0)
        assertEquals("热身期间累计应冻结在 4s", 4, frozeAtWarmup)
        assertFalse("全程不应触发报警", machine.slowAlarm)
    }

    @Test
    fun `sustained fast cadence recovers alarm`() {
        advance(7_000, spm = 100) // 约 6.8s 时报警
        assertTrue(machine.slowAlarm)
        // 5s 滑窗排空后步频才能读到 ≥117（约需 3.5s），再加 600ms 恢复确认
        advance(6_000, spm = 180)
        assertFalse(machine.slowAlarm)
        assertEquals(0, machine.slowProgressSec)
    }

    @Test
    fun `brief fast burst does not clear alarm`() {
        advance(7_000, spm = 100)
        assertTrue(machine.slowAlarm)
        advance(400, spm = 180) // 远不足 600ms 恢复窗口（滑窗步频此时仍 <117）
        advance(2_000, spm = 100)
        assertTrue("短暂加速不应解除报警", machine.slowAlarm)
    }

    // ------------------------------------------------ 外部条件清零

    @Test
    fun `bpm zero clears alarm conditions`() {
        advance(7_000, spm = 100)
        assertTrue(machine.slowAlarm)
        machine.evaluate(targetBpm = 0, alarmEnabled = true)
        assertFalse(machine.slowAlarm)
        assertEquals(0, machine.slowProgressSec)
    }

    @Test
    fun `alarm disabled clears accumulation`() {
        advance(3_000, spm = 100)
        assertTrue(machine.slowProgressSec > 0)
        machine.evaluate(target, alarmEnabled = false)
        assertFalse(machine.slowAlarm)
        assertEquals(0, machine.slowProgressSec)
    }

    @Test
    fun `after clear alarm needs fresh five seconds again`() {
        advance(7_000, spm = 100)
        assertTrue(machine.slowAlarm)
        machine.clearAlarmConditions()
        assertFalse(machine.slowAlarm)
        val events = mutableMapOf<Long, Boolean>()
        // 重放工具不适用（已处于慢跑中段），直接继续喂步点观察
        val end = now + 3_000L
        var nextStepAt = now + 600L
        while (now < end) {
            now += 100
            if (now >= nextStepAt) {
                machine.onStep(now, target, true)
                nextStepAt += 600L
            }
            events[now] = machine.slowAlarm
        }
        assertTrue("清零后 3 秒内不应报警", events.none { it.value })
        val end2 = now + 4_000L
        var nextStepAt2 = now + 600L
        while (now < end2) {
            now += 100
            if (now >= nextStepAt2) {
                machine.onStep(now, target, true)
                nextStepAt2 += 600L
            }
            events[now] = machine.slowAlarm
        }
        assertTrue("再累计满 5s 后应再次报警", events.any { it.value })
    }

    // ------------------------------------------------ deadline（调度器依据）

    @Test
    fun `no deadline while warming up without accrual`() {
        assertNull(machine.nextDeadlineMs(target, true))
        now = 1_000; machine.onStep(1_000, target, true)
        now = 1_500; machine.onStep(1_500, target, true) // 2 步仍 WARMING_UP
        assertNull(machine.nextDeadlineMs(target, true))
    }

    @Test
    fun `deadline while valid fast is stale timeout`() {
        advance(3_000, spm = 180)
        val ahead = machine.nextDeadlineMs(target, true)!! - now
        assertTrue("VALID 时 deadline 应为步伐超时（剩 ${ahead}ms）", ahead in 2_000..2_600)
    }

    @Test
    fun `deadline while accruing is within remaining alarm time`() {
        advance(4_000, spm = 100) // 慢跑累计约 2.2s
        val ahead = machine.nextDeadlineMs(target, true)!! - now
        // deadline 取 min(报警到点, 进度心跳 250ms)：报警到点 ≤ 剩余累计（~2.8s），
        // 进度心跳最多 250ms，因此整体必然落在 0..3s
        assertTrue("报警 deadline 应在剩余累计时间内（剩 ${ahead}ms）", ahead in 0..3_000)
    }

    @Test
    fun `no deadline needed once alarm latched`() {
        advance(7_000, spm = 100)
        assertTrue(machine.slowAlarm)
        assertNull(machine.nextDeadlineMs(target, true))
    }

    @Test
    fun `no deadline when alarm disabled or bpm zero`() {
        advance(3_000, spm = 100)
        assertNull(machine.nextDeadlineMs(0, true))
        assertNull(machine.nextDeadlineMs(target, false))
    }

    // ------------------------------------------------ 状态门槛

    @Test
    fun `valid requires minimum steps`() {
        now = 1_000; machine.onStep(1_000, target, true)
        now = 1_500; machine.onStep(1_500, target, true)
        assertEquals(CadenceStateMachine.State.WARMING_UP, machine.state)
        now = 2_000; machine.onStep(2_000, target, true)
        assertEquals(CadenceStateMachine.State.VALID, machine.state)
    }
}
