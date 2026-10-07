package com.metronome.app.core

/**
 * 训练计划模型与时间线定位（纯 Kotlin，无 Android 依赖，可 JVM 单元测试）。
 *
 * 定时训练与分段训练是两个独立开关，四种组合：
 *  - 定时关、分段关：普通正计时，无自动结束；
 *  - 定时开、分段关：固定目标步频，总时长到点结束；
 *  - 定时关、分段开：按段列表顺序执行（含循环范围），自然完成后结束；
 *  - 定时开、分段开：按分段计划运行，总时长到点优先结束（硬上限）。
 *
 * 循环语义（用户已确认）：只重复 [LoopSpec] 指定的连续段，共 rounds 轮
 * （1 轮 = 该组只执行一遍）；热身、冷身以及循环范围外的段各执行一次。
 *
 * 时间语义（工程默认）：
 *  - 准备倒计时属于会话但不属于训练：不计入有效训练时长，也不是训练段；
 *  - 热身/冷身等所有段都计入训练时长；
 *  - 总时长是硬上限：到点立即结束，不为补跑某段/冷身延长；
 *  - 总时长到点与自然完成发生在同一时刻时，只结束一次。
 *
 * 所有定位都是纯函数：给定"已用有效训练时长"推导当前段/轮/剩余，
 * 不依赖逐回调累加，长时间未调度后一次评估即可跨多段得到正确位置。
 */
data class TrainingSegment(
    val name: String,
    val durationSec: Int,
    val targetSpm: Int,
)

data class LoopSpec(
    val startIndex: Int,
    val endIndexInclusive: Int,
    val rounds: Int,
) {
    init {
        require(rounds >= 1) { "rounds must be >= 1" }
        require(startIndex in 0..endIndexInclusive) { "invalid loop range" }
    }
}

data class TrainingConfig(
    val timerEnabled: Boolean = false,
    val totalDurationSec: Int = 0,
    val prepareCountdownSec: Int = 0,
    val segments: List<TrainingSegment> = emptyList(),
    val loop: LoopSpec? = null,
) {
    /** 分段训练开关 = 段列表非空（不为它单独设布尔，避免两处状态不一致） */
    val segmentMode: Boolean get() = segments.isNotEmpty()

    /** 定时开关真实生效 = 开关打开且时长合法 */
    val timerValid: Boolean get() = timerEnabled && totalDurationSec > 0

    fun validate(): List<String> {
        val errs = mutableListOf<String>()
        if (timerEnabled && totalDurationSec <= 0) errs += "定时训练时长必须大于 0"
        if (totalDurationSec > MAX_TOTAL_SEC) errs += "总时长超出上限（${MAX_TOTAL_SEC / 3600} 小时）"
        if (prepareCountdownSec !in 0..MAX_PREPARE_SEC) errs += "准备倒计时需在 0..$MAX_PREPARE_SEC 秒"
        segments.forEachIndexed { i, s ->
            if (s.durationSec <= 0) errs += "第 ${i + 1} 段时长必须大于 0"
            if (s.durationSec > MAX_SEGMENT_SEC) errs += "第 ${i + 1} 段时长超出上限"
            if (s.targetSpm !in MIN_SPM..MAX_SPM) errs += "第 ${i + 1} 段步频需在 $MIN_SPM..$MAX_SPM"
        }
        loop?.let { l ->
            if (l.endIndexInclusive >= segments.size) errs += "循环范围超出段列表"
            if (l.rounds < 1) errs += "循环轮数必须 ≥ 1"
        }
        return errs
    }

    fun isValid(): Boolean = validate().isEmpty()

    /** 执行序列：(段索引, 轮次 1-based)。loop 为空或段列表为空时按顺序一遍。 */
    fun executionOrder(): List<Pair<Int, Int>> {
        if (!segmentMode) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        val l = loop
        if (l == null || l.endIndexInclusive >= segments.size || l.rounds < 1) {
            for (i in segments.indices) out += i to 1
            return out
        }
        for (i in 0 until l.startIndex) out += i to 1
        for (r in 1..l.rounds) for (i in l.startIndex..l.endIndexInclusive) out += i to r
        for (i in (l.endIndexInclusive + 1) until segments.size) out += i to 1
        return out
    }

    /** 不设总时长时的自然总时长（毫秒）；分段关闭时为 0（无自动结束）。 */
    fun naturalTotalMs(): Long =
        executionOrder().sumOf { (i, _) -> segments[i].durationSec.toLong() * 1000L }

    /** 生效的总上限（毫秒）；null = 未启用定时或未开启分段（定时独立于分段也可生效） */
    fun effectiveCapMs(): Long? = if (timerValid) totalDurationSec.toLong() * 1000L else null

    companion object {
        const val MIN_SPM = 20
        const val MAX_SPM = 200
        const val MAX_TOTAL_SEC = 12 * 3600     // 与 marker 帧号 32bit 安全范围一致
        const val MAX_PREPARE_SEC = 60
        const val MAX_SEGMENT_SEC = 4 * 3600

        /** 分段关闭、定时开启时的合法最小总时长（秒） */
        const val MIN_TOTAL_SEC = 10
    }
}

/**
 * 时间线定位器。构造时预展开执行序列并累计各段起止，
 * [locate] 为纯函数：任何 elapsed 值（含跨越多个边界的跳变）都能直接得到正确位置。
 */
class TrainingTimeline(private val config: TrainingConfig) {

    enum class Phase { PREPARE, TRAINING, FINISHED }

    data class Position(
        val phase: Phase,
        val segmentIndex: Int,          // 训练中有效；PREPARE = -1
        val round: Int,                 // 1-based；非循环段 = 1
        val segmentElapsedMs: Long,
        val segmentRemainingMs: Long,
        val trainingElapsedMs: Long,
        val totalRemainingMs: Long?,    // 定时启用时非空（剩余训练时长，不含准备）
        val naturalRemainingMs: Long?,  // 分段计划自然剩余；无计划 = null
        val nextSegmentIndex: Int,      // -1 = 没有下一段
        val segmentTargetSpm: Int,
        val finished: Boolean,
        val finishedByCap: Boolean,     // true = 总时长截断；false = 自然完成
    )

    private class Slot(val segIndex: Int, val round: Int, val startMs: Long, val endMs: Long)

    private val slots: List<Slot>
    private val naturalTotalMs: Long

    init {
        val order = config.executionOrder()
        var t = 0L
        val list = mutableListOf<Slot>()
        for ((seg, round) in order) {
            val d = config.segments[seg].durationSec.toLong() * 1000L
            list += Slot(seg, round, t, t + d)
            t += d
        }
        slots = list
        naturalTotalMs = t
    }

    fun totalNaturalMs(): Long = naturalTotalMs

    /**
     * @param trainingElapsedMs 有效训练时长（不含准备倒计时、不含暂停时间）
     */
    fun locate(trainingElapsedMs: Long): Position {
        val cap = config.effectiveCapMs()
        val finite = slots.isNotEmpty()

        // 结束判定：定时上限与自然完成取较早者；恰好同时刻只结束一次（优先自然完成语义）
        if (finite && trainingElapsedMs >= naturalTotalMs) return finished(trainingElapsedMs, byCap = false)
        if (cap != null && trainingElapsedMs >= cap) {
            // 上限截断：上限可能落在准备之外的任意点（含某段中间）
            return finished(trainingElapsedMs, byCap = true)
        }
        if (!finite) {
            // 普通正计时 / 仅定时（无分段）：无段信息
            val rem = cap?.let { it - trainingElapsedMs }
            return Position(
                phase = Phase.TRAINING, segmentIndex = -1, round = 1,
                segmentElapsedMs = trainingElapsedMs, segmentRemainingMs = rem ?: -1L,
                trainingElapsedMs = trainingElapsedMs, totalRemainingMs = rem, naturalRemainingMs = null,
                nextSegmentIndex = -1, segmentTargetSpm = 0, finished = false, finishedByCap = false,
            )
        }

        // 线性查找即可（段数有限）；防御性钳制，异常输入不会越界
        val e = trainingElapsedMs.coerceAtLeast(0L)
        var slot = slots.last()
        for (s in slots) {
            if (e < s.endMs) { slot = s; break }
        }
        val seg = config.segments[slot.segIndex]
        // "下一段"指内容不同的下一段：同段的下一轮不算
        val next = slots.firstOrNull { it.startMs >= slot.endMs && it.segIndex != slot.segIndex }
            ?.segIndex ?: -1
        val capRem = cap?.let { it - trainingElapsedMs }
        val naturalRem = naturalTotalMs - trainingElapsedMs
        return Position(
            phase = Phase.TRAINING,
            segmentIndex = slot.segIndex,
            round = slot.round,
            segmentElapsedMs = e - slot.startMs,
            segmentRemainingMs = slot.endMs - e,
            trainingElapsedMs = e,
            totalRemainingMs = capRem,
            naturalRemainingMs = naturalRem,
            nextSegmentIndex = next,
            segmentTargetSpm = seg.targetSpm,
            finished = false,
            finishedByCap = false,
        )
    }

    private fun finished(elapsed: Long, byCap: Boolean) = Position(
        phase = Phase.FINISHED, segmentIndex = -1, round = 1,
        segmentElapsedMs = 0, segmentRemainingMs = 0,
        trainingElapsedMs = elapsed,
        totalRemainingMs = 0, naturalRemainingMs = 0,
        nextSegmentIndex = -1, segmentTargetSpm = 0,
        finished = true, finishedByCap = byCap,
    )
}
