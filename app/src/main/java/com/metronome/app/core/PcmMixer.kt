package com.metronome.app.core

/**
 * 轻量跨 block PCM 混音器（仅音频线程访问，无锁）。
 *
 * 与旧版的关键差异（修复顺序相关失真）：
 *  - 所有 voice 先叠加到宽位 Int/Float 累加缓冲，全部叠加完成后
 *    一次性施加最终增益与限幅（由渲染端 [BlockRenderer] 的 finalize 完成）；
 *    旧版"每加一个声部写回 Short 并饱和"会让先饱和的声部吃掉后续声部，
 *    例如 30000 + 30000 - 30000 旧版得 2767，宽位累加正确得 30000；
 *  - voice 带 [Group] 标签：报警提示取消时可以对未播完的提示声部
 *    做 [fadeOutGroup] 极短淡出，节拍声部不受影响；
 *  - 长于一个 block 的音色（哔声 70ms、牛铃 220ms、自定义音频最长 3s、
 *    结束提示音）跨 block 连续混入，不在 block 边界截断；
 *  - voice 数量上限 [maxVoices]，超出丢弃最旧（新节拍优先可听），
 *    防止异常长音频在高步频下无界堆积。
 *
 * voice 是单声道 PCM；立体声输出时由 [mixInto] 按 toBoth 决定进一路还是两路。
 */
class PcmMixer(private val blockFrames: Int, private val maxVoices: Int = DEFAULT_MAX_VOICES) {

    enum class Group { BEAT, PROMPT, ONESHOT }

    /** 声道路由：左 / 右 / 双耳（居中） */
    enum class Target { LEFT, RIGHT, BOTH }

    class Voice(
        val pcm: ShortArray,
        val group: Group,
        val target: Target,
        var pos: Int,
        var delay: Int,
        var fadeGain: Float = 1f,
        var fadeStep: Float = 0f,   // >0 时每帧线性淡出，到 0 移除
    )

    private val voices = ArrayDeque<Voice>()

    /** 当前未播完的 voice 数（测试与诊断用） */
    val activeVoices: Int get() = voices.size

    /**
     * 加入一段新声音。startOffset 为它在本 block 内的起始帧（0..blockFrames）；
     * 等于 blockFrames 时表示从下一 block 开始播放。
     */
    fun addVoice(
        pcm: ShortArray,
        startOffset: Int = 0,
        group: Group = Group.BEAT,
        target: Target = Target.BOTH,
    ) {
        require(startOffset in 0..blockFrames) { "startOffset out of block: $startOffset" }
        if (voices.size >= maxVoices) voices.removeFirst()
        voices.addLast(Voice(pcm, group, target, 0, startOffset))
    }

    /**
     * 让指定组的未播完 voice 在 fadeFrames 帧内线性淡出并移除。
     * 用于报警解除时让"已安排但未播完"的短提示安静收尾；
     * 已写入输出流的部分无法撤回，由文档说明。
     */
    fun fadeOutGroup(group: Group, fadeFrames: Int) {
        if (fadeFrames <= 0) {
            voices.removeAll { it.group == group }
            return
        }
        for (v in voices) {
            if (v.group == group && v.fadeStep <= 0f) {
                v.fadeStep = v.fadeGain / fadeFrames
            }
        }
    }

    fun clear() = voices.clear()

    /**
     * 把所有 active voice 按各自声道路由叠加进宽位累加缓冲（不裁剪）；
     * 本 block 播完/淡出完的 voice 被移除。
     * @param accR 单声道输出时传 null（Target.RIGHT 视作 LEFT 处理）
     */
    fun mixInto(accL: IntArray, accR: IntArray?) {
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            var dst = v.delay
            v.delay = 0
            if (dst >= blockFrames) {
                v.delay = dst - blockFrames // 顺延到下一 block
                continue
            }
            var src = v.pos
            val pcm = v.pcm
            val hasR = accR != null
            var gain = v.fadeGain
            val step = v.fadeStep
            while (dst < blockFrames && src < pcm.size) {
                val s = (pcm[src].toInt() * gain).toInt()
                when {
                    v.target == Target.BOTH && hasR -> {
                        accL[dst] += s; accR[dst] += s
                    }
                    v.target == Target.RIGHT && hasR -> accR[dst] += s
                    else -> accL[dst] += s
                }
                dst++
                src++
                if (step > 0f) {
                    gain -= step
                    if (gain <= 0f) break
                }
            }
            v.pos = src
            v.fadeGain = if (step > 0f) gain.coerceAtLeast(0f) else gain
            if (src >= pcm.size || (step > 0f && v.fadeGain <= 0f)) it.remove()
        }
    }

    companion object {
        /**
         * 上限取值：自定义音频最长 3s，BPM 200 时约 3.3 拍/s，
         * 满重叠约 10 个 voice；12 留少量余量，超出丢最旧。
         */
        const val DEFAULT_MAX_VOICES = 12
    }
}
