package com.metronome.app.core

/**
 * 步频估计器（纯 Kotlin）。
 *
 * 设计目标（针对偏慢报警的触发/解除响应）：
 *  - 报警路径使用 [medianSpm]：最近 3 个步间隔的中位数，浮点运算不做截断；
 *    阶跃改变步频后 2~3 个新间隔内即可跟随新值（比原"最近 10 步平均窗口"
 *    的 9 个间隔快一个量级），单个异常间隔（漏步/双计/抖动）被中位数吸收；
 *  - 显示路径使用 [displaySpm]：对 medianSpm 做轻度 EMA（α=0.6），
 *    稳定段数字不跳动，收敛速度仍满足"5 个新间隔内进入新值 ±3 SPM"；
 *  - 全程 float，只在 UI 显示时取整，消除原 .toInt() 提前截断的低估偏差。
 *
 * 输入侧只接收已通过抖动过滤的步点事件时间（单调毫秒）。
 */
class CadenceEstimator {

    private val events = ArrayDeque<Long>(8)

    /** 报警/逻辑用：快速中位数估计 */
    var medianSpm = 0f; private set

    /** 显示用：轻度平滑估计 */
    var displaySpm = 0f; private set

    /** 当前窗口内可用间隔数（诊断用） */
    var intervalCount = 0; private set

    fun onStep(eventMs: Long) {
        events.addLast(eventMs)
        while (events.size > ESTIMATOR_WINDOW) events.removeFirst()
        val n = events.size - 1
        intervalCount = n
        if (n < 1) return
        // 取最近 min(3, n) 个间隔的中位数
        val k = minOf(3, n)
        val ints = FloatArray(k)
        var i = events.size - 1
        for (j in k - 1 downTo 0) {
            ints[j] = (events[i] - events[i - 1]).toFloat()
            i--
        }
        ints.sort()
        val median = if (k % 2 == 1) ints[k / 2] else (ints[k / 2 - 1] + ints[k / 2]) / 2f
        if (median <= 0f) return
        val spm = (60_000f / median).coerceIn(MIN_SPM_F, MAX_SPM_F)
        medianSpm = spm
        displaySpm = if (displaySpm <= 0f) spm else displaySpm * (1f - DISPLAY_ALPHA) + spm * DISPLAY_ALPHA
    }

    fun reset() {
        events.clear()
        medianSpm = 0f
        displaySpm = 0f
        intervalCount = 0
    }

    companion object {
        private const val ESTIMATOR_WINDOW = 5   // 保留 5 个步点 = 4 个间隔供选择
        private const val DISPLAY_ALPHA = 0.6f
        private const val MIN_SPM_F = 30f
        private const val MAX_SPM_F = 240f
    }
}

/**
 * 检测 + 报警状态机（纯 Kotlin，注入单调时钟）。
 *
 * 与旧版 [CadenceStateMachine] 相比的关键修正：
 *  1. 事件时间与处理时间分离：步间隔估计用传感器事件时间；
 *     新鲜度、偏慢累计、deadline 全部用处理时刻（到达时间），迟到/批量
 *     事件不会把评估拉回历史时间，也不会把过期事件当成当前有效数据；
 *  2. 新鲜度 deadline 与报警开关/报警状态解耦：只要检测在跑、有过步点，
 *     就会在 lastStep+超时 时刻被唤醒标记 STALE——报警已触发、报警关闭
 *     时同样生效（旧版 alarm latched 后不再安排任何 deadline）；
 *  3. 报警恢复有独立 deadline：恢复条件（≥ target-fastMargin）开始后
 *     recoverMs 到点即解除，不等下一个步点，更不等一轮触发持续时间；
 *  4. 目标变化/新段/暂停恢复时调用 [onTargetChanged]：清空旧累计并进入
 *     短暂适应期（grace），不沿用旧目标的累计立刻误报；
 *  5. 偏慢累计模型明确为"累计偏慢"：慢速期间累计，死区冻结不清零，
 *     恢复确认（持续 ≥ target-fastMargin 达 recoverMs）才清零；
 *     文案与测试按"累计"描述，不再声称"严格连续"。
 *
 * 边界统一：fresh 判定用严格小于（now - last < timeout），
 * deadline 恰为 last + timeout，到点即转 STALE，无零延迟重复调度。
 */
class DetectorCore(
    private val nowMs: () -> Long,
    var config: Config = Config(),
) {

    data class Config(
        val alarmEnabled: Boolean = false,
        val targetSpm: Int = 0,
        val slowMarginSpm: Int = 8,       // 慢于 target-8 判偏慢
        val fastMarginSpm: Int = 3,       // 不低于 target-3 视为恢复方向
        val alarmAfterMs: Long = 5_000,   // 配置的触发持续（有意等待，非软件延迟）
        val recoverMs: Long = 600,        // 恢复确认时长
        val staleTimeoutMs: Long = 2_500, // 步点超时 → STALE
        val minStepsForValid: Int = 3,    // ≥3 步点（2 间隔）才认可
        val minStepIntervalMs: Long = 220,// 抖动/双计过滤
        val graceMs: Long = 1_500,        // 目标切换/恢复运行后的适应期
    )

    enum class State { WARMING_UP, VALID, STALE }

    var state = State.WARMING_UP; private set
    var cadenceSpm = 0f; private set      // 报警路径（快速中位数）
    var displaySpm = 0f; private set      // 显示路径（平滑）
    var slowAlarm = false; private set
    var slowProgressMs = 0L; private set  // 触发后冻结在 alarmAfterMs
    var everValid = false; private set

    // 诊断事件（验证报警链路各层延迟用；生产路径可忽略）
    var alarmEnteredAtMs = 0L; private set
    var alarmExitedAtMs = 0L; private set
    var slowStartAtMs = 0L; private set   // 本段偏慢条件首次满足（处理时刻）

    private val estimator = CadenceEstimator()
    private var lastStepEventMs = 0L
    private var lastArrivalMs = 0L
    private var bankedMs = 0L
    private var slowSinceMs = 0L   // 本段累计开始时刻（处理时间轴）；0 = 未累计
    private var fastSinceMs = 0L   // 本段恢复方向开始时刻；0 = 未累计
    private var graceUntilMs = 0L
    private var lastEvalMs = 0L

    val stepCount: Int get() = estimator.intervalCount + 1

    /**
     * 上报一步。
     * @param eventMs 传感器事件时间（单调毫秒；与 elapsedRealtime 同基）
     * @param arrivalMs 处理时刻，默认当前单调时间；批量迟到事件按到达时间评估
     */
    fun onStep(eventMs: Long, arrivalMs: Long = nowMs()) {
        var ts = eventMs
        if (ts <= 0L || ts > arrivalMs + FUTURE_TOLERANCE_MS) ts = arrivalMs // 异常/未来时间戳回退
        if (lastStepEventMs > 0L && ts - lastStepEventMs < config.minStepIntervalMs) return // 抖动/双计
        lastStepEventMs = ts
        lastArrivalMs = arrivalMs
        estimator.onStep(ts)
        evaluateAt(arrivalMs)
    }

    /** 定时器/外部触发的重新评估，使用处理时刻 */
    fun evaluate(now: Long = nowMs()) = evaluateAt(now)

    /**
     * 目标变化 / 新训练段 / 暂停后恢复：清空偏慢累计并给予短暂适应期。
     * 已触发的报警不清除（恢复条件按新目标判定），避免旧的"偏慢"状态凭空消失。
     */
    fun onTargetChanged(now: Long = nowMs()) {
        bankedMs = 0
        slowSinceMs = 0
        fastSinceMs = 0
        slowProgressMs = 0
        slowStartAtMs = 0
        graceUntilMs = now + config.graceMs
    }

    fun clearAlarmConditions() {
        bankedMs = 0; slowSinceMs = 0; fastSinceMs = 0
        slowAlarm = false; slowProgressMs = 0
        slowStartAtMs = 0
    }

    fun reset() {
        estimator.reset()
        lastStepEventMs = 0; lastArrivalMs = 0
        state = State.WARMING_UP
        cadenceSpm = 0f; displaySpm = 0f
        everValid = false
        bankedMs = 0; slowSinceMs = 0; fastSinceMs = 0
        slowAlarm = false; slowProgressMs = 0
        graceUntilMs = 0; lastEvalMs = 0
        alarmEnteredAtMs = 0; alarmExitedAtMs = 0; slowStartAtMs = 0
    }

    private fun evaluateAt(now: Long) {
        lastEvalMs = now
        updateState(now)

        val cfg = config
        if (!cfg.alarmEnabled || cfg.targetSpm <= 0) {
            // 检测可独立于报警运行：只更新状态/步频，不累计（保留步点用于显示）
            clearAlarmConditions()
            return
        }

        if (slowAlarm) {
            // 已触发：只处理恢复方向，不再累计
            if (state == State.VALID && cadenceSpm >= cfg.targetSpm - cfg.fastMarginSpm) {
                if (fastSinceMs == 0L) fastSinceMs = now
                if (now - fastSinceMs >= cfg.recoverMs) recover(now)
            } else {
                fastSinceMs = 0
            }
            return
        }

        if (state == State.STALE) {
            // 曾有效、现在长时间无步点：视为停止/严重掉速，继续累计
            fastSinceMs = 0
            accrue(now)
            return
        }

        if (now < graceUntilMs) {
            // 适应期：不累计也不清零（目标刚变/刚恢复，旧累计已清）
            fastSinceMs = 0
            return
        }

        if (state != State.VALID) {
            // WARMING_UP：无可信步频，不新增累计；保留已有 banked（短暂热身不重置进度）
            fastSinceMs = 0
            return
        }

        val cad = cadenceSpm
        when {
            cad < cfg.targetSpm - cfg.slowMarginSpm -> {
                fastSinceMs = 0
                accrue(now)
            }
            cad >= cfg.targetSpm - cfg.fastMarginSpm -> {
                // 恢复方向：持续 recoverMs 后清空累计（未报警时也重置，避免跨死区继承）
                if (fastSinceMs == 0L) fastSinceMs = now
                pauseAccrual(now)
                if (now - fastSinceMs >= cfg.recoverMs) clearAlarmConditions()
            }
            else -> {
                // 死区：冻结累计不清零（滞回防抖）
                fastSinceMs = 0
                pauseAccrual(now)
            }
        }
    }

    private fun updateState(now: Long) {
        val fresh = lastStepEventMs > 0L && now - lastStepEventMs < config.staleTimeoutMs
        state = when {
            fresh && estimator.intervalCount + 1 >= config.minStepsForValid -> {
                everValid = true; State.VALID
            }
            fresh -> State.WARMING_UP
            everValid -> State.STALE
            else -> State.WARMING_UP
        }
        if (state == State.STALE) {
            // 过期数据不再作为当前步频显示/判定；窗口清空，恢复跑步后重新热身
            estimator.reset()
            cadenceSpm = 0f
            displaySpm = 0f
        } else {
            cadenceSpm = estimator.medianSpm
            displaySpm = estimator.displaySpm
        }
    }

    private fun accrue(now: Long) {
        if (slowSinceMs == 0L) {
            slowSinceMs = now
            if (slowStartAtMs == 0L) slowStartAtMs = now
        }
        val total = bankedMs + (now - slowSinceMs)
        slowProgressMs = minOf(total, config.alarmAfterMs)
        if (total >= config.alarmAfterMs) {
            slowAlarm = true
            alarmEnteredAtMs = now
            // 锁存后累计段结束：清 slowSince，避免 nextDeadlineMs 产生过期限 deadline
            slowSinceMs = 0
            bankedMs = config.alarmAfterMs
        }
    }

    private fun pauseAccrual(now: Long) {
        if (slowSinceMs != 0L) {
            bankedMs += now - slowSinceMs
            slowSinceMs = 0
        }
    }

    private fun recover(now: Long) {
        clearAlarmConditions()
        alarmExitedAtMs = now
    }

    /**
     * 下一次需要定时器唤醒的单调时刻；null = 无需定时器。
     * 新鲜度检查与报警解耦：报警关闭、已触发时仍返回新鲜度 deadline。
     */
    fun nextDeadlineMs(): Long? {
        val deadlines = ArrayList<Long>(4)
        val cfg = config
        // 新鲜度：只要有过步点且未处于 STALE，就必须在超时点复查
        if (lastStepEventMs > 0L && state != State.STALE) {
            deadlines += lastStepEventMs + cfg.staleTimeoutMs
        }
        if (cfg.alarmEnabled && cfg.targetSpm > 0) {
            if (slowSinceMs > 0L) {
                deadlines += slowSinceMs + (cfg.alarmAfterMs - bankedMs)  // 报警到点
                // 进度心跳：钳制到未来，避免迟到的 lastEval 产生负延迟调度
                deadlines += maxOf(lastEvalMs + PROGRESS_TICK_MS, nowMs())
            }
            if (fastSinceMs > 0L) deadlines += fastSinceMs + cfg.recoverMs // 恢复确认到点
        }
        if (graceUntilMs > lastEvalMs) deadlines += graceUntilMs             // 适应期结束
        return deadlines.minOrNull()
    }

    companion object {
        const val PROGRESS_TICK_MS = 250L
        private const val FUTURE_TOLERANCE_MS = 50L
    }
}
