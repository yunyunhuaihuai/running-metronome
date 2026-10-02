package com.metronome.app

/**
 * 步频报警状态机（纯 Kotlin、无 Android 依赖，可 JVM 单元测试）。
 *
 * 所有时间均使用单调时钟（SystemClock.elapsedRealtime 毫秒）的真实时间戳：
 * "持续偏慢 5 秒"= 进入 slow 的真实时刻与当前真实时刻之差，
 * 与评估循环被调度了多少次、调度是否延迟无关。
 *
 * 状态：
 *  WARMING_UP — 尚无可信步频（刚启动还没迈步 / STALE 后重新热身 / 步点不足）。
 *               绝不因 cadence=0 触发掉速报警。
 *  VALID      — 最近有足够的新鲜步点，cadence 可信，正常判断偏慢/恢复。
 *  STALE      — 曾经 VALID，之后持续无步伐：视为停止/严重掉速，计入慢速累计
 *               （与"从未采到有效步频"明确区分）。
 *
 * 慢速累计模型：bankedMs（冻结累计）+ slowSinceMs（本段连续 slow 的开始时刻）。
 * 死区（target-8 ≤ cadence < target-3）冻结累计不清零，避免临界抖动来回报警；
 * cadence ≥ target-3 持续 RECOVER_MS 才解除报警。
 */
class CadenceStateMachine(private val nowMs: () -> Long) {

    enum class State { WARMING_UP, VALID, STALE }

    companion object {
        const val STALE_TIMEOUT_MS = 2500L    // 超过该时长无步伐 → STALE
        const val ALARM_AFTER_MS = 5000L      // 持续偏慢该真实时长 → 报警
        const val RECOVER_MS = 600L           // 持续恢复该真实时长 → 解除报警
        const val PROGRESS_TICK_MS = 250L     // 累计期间进度刷新间隔（仅此期间有定时器）
        const val MIN_STEPS_FOR_VALID = 3     // 至少 3 个步点（2 个步间）才认可 cadence
        const val MIN_STEP_INTERVAL_MS = 220L // 步间小于此值视为抖动（>272 SPM 不可能）
        const val STEP_WINDOW_MS = 5000L      // cadence 统计窗口
        const val STEP_WINDOW_MAX = 10        // 窗口内最多步点数
        const val SLOW_BPM_MARGIN = 8         // 慢于 target-8 判为偏慢
        const val FAST_BPM_MARGIN = 3         // 不低于 target-3 且持续 → 恢复
    }

    var state = State.WARMING_UP; private set
    var cadenceSpm = 0; private set
    var slowAlarm = false; private set
    var slowProgressSec = 0; private set

    private val steps = ArrayDeque<Long>()
    private var lastStepMs = 0L
    private var everValid = false

    private var bankedMs = 0L     // 已冻结累计的慢速时长
    private var slowSinceMs = 0L  // 本段连续 slow 的开始时刻；0 = 未在累计
    private var fastSinceMs = 0L  // 本段连续 fast 的开始时刻；0 = 未在累计
    private var lastEvalMs = 0L

    val stepCount: Int get() = steps.size

    /** 上报一步（stepMs 为单调毫秒时间戳），随后立即重新评估 */
    fun onStep(stepMs: Long, targetBpm: Int, alarmEnabled: Boolean) {
        val now = nowMs()
        var ts = stepMs
        if (ts <= 0L || ts > now + 50L) ts = now // 时间戳异常时退回当前单调时间
        if (lastStepMs > 0 && ts - lastStepMs < MIN_STEP_INTERVAL_MS) return // 抖动过滤
        lastStepMs = ts
        steps.addLast(ts)
        val windowStart = ts - STEP_WINDOW_MS
        while (steps.isNotEmpty() && steps.first() < windowStart) steps.removeFirst()
        while (steps.size > STEP_WINDOW_MAX) steps.removeFirst()
        evaluateAt(ts, targetBpm, alarmEnabled)
    }

    /** 事件/定时器触发的重新评估，使用真实当前单调时间 */
    fun evaluate(targetBpm: Int, alarmEnabled: Boolean) =
        evaluateAt(nowMs(), targetBpm, alarmEnabled)

    private fun evaluateAt(now: Long, targetBpm: Int, alarmEnabled: Boolean) {
        lastEvalMs = now
        updateCadence()
        updateState(now)
        if (!alarmEnabled || targetBpm <= 0) {
            // 节拍器未运行 / BPM=0：报警条件不成立，清零累计（保留步点用于显示）
            clearAlarmConditions()
            return
        }
        when (state) {
            State.VALID -> {
                if (cadenceSpm < targetBpm - SLOW_BPM_MARGIN) {
                    fastSinceMs = 0
                    accrue(now)
                } else if (cadenceSpm >= targetBpm - FAST_BPM_MARGIN) {
                    if (fastSinceMs == 0L) fastSinceMs = now
                    pauseAccrual(now)
                    if (now - fastSinceMs >= RECOVER_MS) recover()
                } else {
                    // 死区：冻结累计、不增长也不清零
                    fastSinceMs = 0
                    pauseAccrual(now)
                }
            }
            State.WARMING_UP ->
                // 无可信 cadence：不新增累计；已有累计保留（STALE 恢复后
                // 重新热身的零点几秒不应重置报警进度，也不应继续累加）
                pauseAccrual(now)
            State.STALE -> {
                // 曾经有效、现在长时间无步伐：视为停止/严重掉速
                fastSinceMs = 0
                cadenceSpm = 0
                accrue(now)
            }
        }
    }

    private fun updateCadence() {
        if (steps.size >= 2) {
            val totalSec = (steps.last() - steps.first()) / 1000.0
            if (totalSec > 0.1) {
                cadenceSpm = ((steps.size - 1) / totalSec * 60.0).toInt().coerceIn(30, 240)
            }
        }
    }

    private fun updateState(now: Long) {
        val fresh = lastStepMs > 0 && now - lastStepMs <= STALE_TIMEOUT_MS
        state = when {
            fresh && steps.size >= MIN_STEPS_FOR_VALID -> { everValid = true; State.VALID }
            fresh -> State.WARMING_UP
            everValid -> State.STALE
            else -> State.WARMING_UP
        }
        if (state == State.STALE && steps.isNotEmpty()) {
            steps.clear()
            cadenceSpm = 0
        }
    }

    private fun accrue(now: Long) {
        if (slowSinceMs == 0L) slowSinceMs = now
        val total = bankedMs + (now - slowSinceMs)
        slowProgressSec = (total / 1000).toInt()
        if (total >= ALARM_AFTER_MS) slowAlarm = true
    }

    private fun pauseAccrual(now: Long) {
        if (slowSinceMs != 0L) {
            bankedMs += now - slowSinceMs
            slowSinceMs = 0
        }
    }

    private fun recover() {
        bankedMs = 0; slowSinceMs = 0; fastSinceMs = 0
        slowAlarm = false; slowProgressSec = 0
    }

    /** BPM=0 / 服务停止时调用：清零报警累计，保留步点窗口（UI 仍显示步频） */
    fun clearAlarmConditions() {
        bankedMs = 0; slowSinceMs = 0; fastSinceMs = 0
        slowAlarm = false; slowProgressSec = 0
    }

    fun reset() {
        steps.clear(); lastStepMs = 0; everValid = false
        bankedMs = 0; slowSinceMs = 0; fastSinceMs = 0; lastEvalMs = 0
        state = State.WARMING_UP; cadenceSpm = 0
        slowAlarm = false; slowProgressSec = 0
    }

    /**
     * 下一次需要定时器唤醒评估的单调时刻；null = 当前无需任何定时器
     * （未运行 / BPM=0 / 等待步伐数据 / 已报警仅等待恢复事件）。
     * 全部基于绝对时间戳，定时器调度延迟不会导致误判，只影响触发精度。
     */
    fun nextDeadlineMs(targetBpm: Int, alarmEnabled: Boolean): Long? {
        if (!alarmEnabled || targetBpm <= 0 || slowAlarm) return null
        val deadlines = ArrayList<Long>(3)
        if (state == State.VALID && lastStepMs > 0) {
            deadlines.add(lastStepMs + STALE_TIMEOUT_MS)  // VALID → STALE 的超时检测
        }
        if (slowSinceMs > 0) {
            deadlines.add(slowSinceMs + (ALARM_AFTER_MS - bankedMs)) // 报警到点
            deadlines.add(lastEvalMs + PROGRESS_TICK_MS)             // 进度刷新
        }
        return deadlines.minOrNull()
    }
}
