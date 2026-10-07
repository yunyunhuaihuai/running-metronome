package com.metronome.app.core

/**
 * 训练会话状态机（纯 Kotlin，注入单调时钟，可 JVM 单元测试）。
 *
 * 状态：
 *  IDLE      — 无会话（服务可在运行但未开始训练）。
 *  PREPARING — 准备倒计时中：节拍已出，倒计时冻结于暂停；不计入训练时长。
 *  RUNNING   — 训练计时中：段进度由 [TrainingTimeline.locate] 从有效训练时长推导。
 *  PAUSED    — 手动暂停：冻结准备倒计时、总训练时间与段进度；不销毁进度。
 *  FINISHED  — 结束（自然完成或总时长截断）：节拍与周期振动停止，结束提示一次；
 *              保留完成信息供查看。再次 start 是全新会话，不是 PAUSED 的恢复。
 *  ERROR     — 音频等基础设施故障，训练无法继续。
 *
 * 暂停与停止是不同动作：pause/resume 保留全部进度；停止（cancel）才清空会话。
 * 所有时间基于单调时钟，墙钟变化不影响段结束。
 */
class SessionStateMachine(private val nowMs: () -> Long) {

    var state = State.IDLE; private set
    var config: TrainingConfig = TrainingConfig(); private set
    private var timeline: TrainingTimeline? = null

    var prepareElapsedMs = 0L; private set
    var trainingElapsedMs = 0L; private set
    var finishedByCap = false; private set
    var finishElapsedMs = 0L; private set   // 结束瞬间的有效训练时长（供完成信息展示）

    private var anchorMs = 0L   // 当前连续运行段（PREPARING 或 RUNNING）的开始时刻
    private var pausedInPrepare = false

    enum class State { IDLE, PREPARING, RUNNING, PAUSED, FINISHED, ERROR }

    /** 开始新会话；IDLE/FINISHED/ERROR 下合法，返回是否成功 */
    fun start(config: TrainingConfig): Boolean {
        val errs = config.validate()
        if (errs.isNotEmpty()) return false
        this.config = config
        timeline = TrainingTimeline(config)
        prepareElapsedMs = 0
        trainingElapsedMs = 0
        finishedByCap = false
        finishElapsedMs = 0
        pausedInPrepare = false
        anchorMs = nowMs()
        state = if (config.prepareCountdownSec > 0) State.PREPARING else State.RUNNING
        return true
    }

    /** 手动暂停（幂等）：冻结计时，保留进度 */
    fun pause(): Boolean = when (state) {
        State.PREPARING -> { freezePrepare(nowMs()); pausedInPrepare = true; state = State.PAUSED; true }
        State.RUNNING -> { freezeTraining(nowMs()); state = State.PAUSED; true }
        else -> false
    }

    /** 继续（幂等）：恢复原进度；报警/适应期由调用方在目标侧重置 */
    fun resume(): Boolean {
        if (state != State.PAUSED) return false
        anchorMs = nowMs()
        state = if (pausedInPrepare) State.PREPARING else State.RUNNING
        return true
    }

    /** 用户主动停止：清空会话进度（与暂停不同） */
    fun cancel() {
        state = State.IDLE
        config = TrainingConfig()
        timeline = null
        prepareElapsedMs = 0; trainingElapsedMs = 0
        finishedByCap = false; finishElapsedMs = 0
    }

    /** 进入 ERROR（基础设施故障），保留已用时长供显示 */
    fun fail() { if (state != State.IDLE) state = State.ERROR }

    /**
     * 推进计时并检查结束条件（控制线程周期调用；长间隔一次调用即可跨越多段）。
     * 返回发生的状态转移（供服务层做一次性处理，如结束提示）。
     */
    fun tick(): Transition {
        val now = nowMs()
        if (state == State.PREPARING) {
            val total = config.prepareCountdownSec * 1000L
            prepareElapsedMs += now - anchorMs
            anchorMs = now
            if (prepareElapsedMs >= total) {
                prepareElapsedMs = total
                trainingElapsedMs = 0
                anchorMs = now
                state = State.RUNNING
                return Transition(State.PREPARING, State.RUNNING)
            }
            return Transition(State.PREPARING, State.PREPARING)
        }
        if (state != State.RUNNING) return Transition(state, state)

        trainingElapsedMs += now - anchorMs
        anchorMs = now
        val tl = timeline ?: return Transition(State.RUNNING, State.RUNNING)
        val pos = tl.locate(trainingElapsedMs)
        if (pos.finished) {
            finishedByCap = pos.finishedByCap
            finishElapsedMs = trainingElapsedMs
            state = State.FINISHED
            return Transition(State.RUNNING, State.FINISHED)
        }
        return Transition(State.RUNNING, State.RUNNING)
    }

    /** 当前训练时间线位置（PAUSED/FINISHED 时返回冻结值；无分段计划时段字段为 -1/0） */
    fun position(): TrainingTimeline.Position? {
        val tl = timeline ?: return null
        return when (state) {
            State.PREPARING -> {
                val total = config.prepareCountdownSec * 1000L
                val rem = (total - prepareElapsedMs - (nowMs() - anchorMs)).coerceAtLeast(0)
                TrainingTimeline.Position(
                    phase = TrainingTimeline.Phase.PREPARE,
                    segmentIndex = -1, round = 1,
                    segmentElapsedMs = 0, segmentRemainingMs = rem,
                    trainingElapsedMs = 0, totalRemainingMs = null, naturalRemainingMs = null,
                    nextSegmentIndex = config.segments.firstOrNull()?.let { 0 } ?: -1,
                    segmentTargetSpm = config.segments.firstOrNull()?.targetSpm ?: 0,
                    finished = false, finishedByCap = false,
                )
            }
            State.RUNNING -> tl.locate(trainingElapsedMs + (nowMs() - anchorMs))
            State.PAUSED, State.FINISHED, State.ERROR -> tl.locate(trainingElapsedMs)
            State.IDLE -> null
        }
    }

    fun prepareRemainingMs(): Long {
        val total = config.prepareCountdownSec * 1000L
        return when (state) {
            State.PREPARING -> (total - prepareElapsedMs - (nowMs() - anchorMs)).coerceAtLeast(0)
            else -> (total - prepareElapsedMs).coerceAtLeast(0)
        }
    }

    private fun freezePrepare(now: Long) {
        prepareElapsedMs += now - anchorMs
        anchorMs = now
    }

    private fun freezeTraining(now: Long) {
        trainingElapsedMs += now - anchorMs
        anchorMs = now
    }

    data class Transition(val from: State, val to: State) {
        val changed: Boolean get() = from != to
    }
}
