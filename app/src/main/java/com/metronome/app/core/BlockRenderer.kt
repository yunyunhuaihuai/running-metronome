package com.metronome.app.core

import kotlin.math.sin

/**
 * 单块（10ms）音频渲染器：把节拍调度、双脚音色、报警提示、长音、
 * 主音量、静音门控全部落到 PCM（纯 Kotlin，可离线验证）。
 *
 * 音频服务每个 block 调一次 [renderBlock]，传入流内帧基点与配置快照；
 * 渲染器返回该 block 内"将可听到的拍"（用于 AudioTrack marker 驱动振动/计数）。
 *
 * 关键行为：
 *  - 一拍 = 一步：左右脚交替只改变每拍用哪只脚的音色/声道，
 *    总拍速始终等于设定 SPM（不除二、不加倍）；
 *  - 会话起始交替顺序确定（默认左脚开始），暂停/恢复保留顺序，
 *    报警提示与试听不改变交替相位；
 *  - 目标 SPM 变化时按一致相位规则重锚下一拍：下一拍最迟在
 *    一个新间隔内出现，既不补发多拍也不遗漏（旧版沿用旧间隔的缺陷）；
 *  - 输出门控 [Snapshot.gateOpen] 控制最终是否出声：关闭时不加新声部、
 *    门控以极短斜坡（约 6ms）压到 0，已排队尾音随之平滑消失；
 *    门控与节拍调度（marker/振动/计数）无关——只静音不出，不停止计时；
 *  - 所有声部叠加到宽位累加缓冲，最后一次性施加 主音量×门控 并限幅，
 *    正常设置不进入限幅（限幅仅峰值兜底）；
 *  - 报警"叠加短提示"模式：长提示间隔按音频帧（单调）排定，
 *    解除时未安排的提示直接取消，未播完的提示声部极短淡出；
 *  - 报警"长音"模式：长音替代节拍声部（旧行为），相位连续、平滑进出。
 */
class BlockRenderer(
    private val blockFrames: Int,
    val sampleRate: Int,
    val stereo: Boolean,
    private val sounds: SoundResolver,
) {

    /** 音色/提示音来源；返回 null 表示资源未就绪（该拍静音但仍计数） */
    interface SoundResolver {
        fun beatPcm(foot: Foot): ShortArray?
        fun promptPcm(): ShortArray?
    }

    enum class Foot { LEFT, RIGHT }

    /** 声道模式：居中（两耳都听，默认）或左右脚分别进左/右声道（可选交替） */
    enum class ChannelMode { CENTER, ALTERNATE_LR }

    data class Snapshot(
        val targetSpm: Int,             // 0 = 不产生新拍（暂停/停止）
        val soundEnabled: Boolean,      // 用户声音开关
        val gateOpen: Boolean,          // 最终门控（用户开关 × 焦点 × 无错误）
        val volume: Float,              // 节拍独立音量 0..1
        val channelMode: ChannelMode,
        val alarmToneActive: Boolean,   // 长音模式报警
        val promptActive: Boolean,      // 叠加短提示报警
        val promptIntervalFrames: Long,
        val resetFootPhase: Boolean = false, // 新会话第一拍从左脚重新开始
        val resetScheduler: Boolean = false, // 会话/轨道重建：重排节拍基准
    )

    data class ScheduledBeat(val num: Long, val frame: Long, val foot: Foot)

    class BlockResult(val beats: List<ScheduledBeat>, val peakL: Int, val peakR: Int)

    private val mixer = PcmMixer(blockFrames)
    private val accL = IntArray(blockFrames)
    private val accR = IntArray(blockFrames)

    private var nextBeat = -1.0
    private var lastSpm = -1
    private var beatCounter = 0L
    private var footPhase = Foot.LEFT
    private var nextPromptAt = -1L
    private var alarmPhase = 0.0
    private var alarmGain = 0.0
    private var gate = 0f
    private val pendingOneShots = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()

    /** 预备下一 block 播放的一次性声音（结束提示等）；走正式混音/音量路径 */
    fun queueOneShot(pcm: ShortArray) {
        if (pcm.isNotEmpty()) pendingOneShots.add(pcm)
    }

    /** 丢弃未播出的一次性声音 */
    fun clearOneShots() = pendingOneShots.clear()

    /** 是否还有待播/未播完的声音（结束提示收尾判定用） */
    fun hasActiveSound(): Boolean = mixer.activeVoices > 0 || pendingOneShots.isNotEmpty()

    /** 重置全部调度状态（会话启动/轨道重建时调用：拍号清零、左脚先） */
    fun reset() {
        mixer.clear()
        pendingOneShots.clear()
        nextBeat = -1.0
        lastSpm = -1
        beatCounter = 0L
        footPhase = Foot.LEFT
        nextPromptAt = -1L
        alarmPhase = 0.0
        alarmGain = 0.0
        gate = 0f
    }

    /**
     * 暂停后恢复：清理已排队声部并重排节拍基准，但保留拍数与交替相位
     * （已确认需求：暂停不销毁进度、恢复保留交替顺序）。
     */
    fun resetForResume() {
        mixer.clear()
        pendingOneShots.clear()
        nextBeat = -1.0
        lastSpm = -1
        nextPromptAt = -1L
        alarmGain = 0.0
        gate = 0f
    }

    /**
     * 渲染一个 block。
     * @param frameBase 本 block 起始帧（流内绝对帧号）
     * @param out 输出缓冲：单声道长 blockFrames；立体声交错长 blockFrames*2
     */
    fun renderBlock(frameBase: Long, snap: Snapshot, out: ShortArray): BlockResult {
        java.util.Arrays.fill(accL, 0)
        if (stereo) java.util.Arrays.fill(accR, 0)

        if (snap.resetFootPhase) footPhase = Foot.LEFT
        if (snap.resetScheduler) {
            nextBeat = -1.0
            lastSpm = -1
            nextPromptAt = -1
        }

        val spm = snap.targetSpm
        val framesPerBeat = if (spm > 0) 60.0 * sampleRate / spm else 0.0

        // 目标 SPM 变化：重锚相位（下一拍 ≤ 一个新间隔内），保持节拍连续
        if (spm > 0 && spm != lastSpm) {
            if (nextBeat < 0.0) {
                nextBeat = frameBase + LEAD_FRAMES.toDouble()   // 会话启动/轨道重建
            } else if (lastSpm > 0) {
                nextBeat = frameBase + framesPerBeat            // 运行中改速：不补发、不遗漏
            }
            lastSpm = spm
        }

        val beats = ArrayList<ScheduledBeat>(2)

        // 1) 排布本 block 内的节拍（marker/振动/计数无条件进行）
        if (spm > 0) {
            var beat = nextBeat
            val end = frameBase + blockFrames
            val longToneSuppress = snap.alarmToneActive && snap.soundEnabled
            val target = when (snap.channelMode) {
                BlockRenderer.ChannelMode.CENTER -> PcmMixer.Target.BOTH
                BlockRenderer.ChannelMode.ALTERNATE_LR ->
                    if (footPhase == Foot.LEFT) PcmMixer.Target.LEFT else PcmMixer.Target.RIGHT
            }
            while (beat < end) {
                val startF = Math.round(beat)
                val offset = (startF - frameBase).toInt().coerceIn(0, blockFrames)
                if (!longToneSuppress && snap.soundEnabled) {
                    val pcm = sounds.beatPcm(footPhase)
                    if (pcm != null && pcm.isNotEmpty()) {
                        mixer.addVoice(pcm, offset, PcmMixer.Group.BEAT, target)
                    }
                }
                beats += ScheduledBeat(beatCounter, startF, footPhase)
                beatCounter++
                footPhase = if (footPhase == Foot.LEFT) Foot.RIGHT else Foot.LEFT
                beat += framesPerBeat
            }
            nextBeat = beat
        }

        // 2) 报警短提示：按音频帧单调排定；解除时立即停止安排后续提示
        if (snap.promptActive && snap.soundEnabled) {
            if (nextPromptAt < 0) nextPromptAt = frameBase + PROMPT_LEAD_FRAMES
            if (nextPromptAt < frameBase) nextPromptAt = frameBase // 长时间未出声后不补发积压提示
            while (nextPromptAt < frameBase + blockFrames) {
                val offset = (nextPromptAt - frameBase).toInt().coerceIn(0, blockFrames)
                sounds.promptPcm()?.let {
                    if (it.isNotEmpty()) mixer.addVoice(it, offset, PcmMixer.Group.PROMPT, PcmMixer.Target.BOTH)
                }
                nextPromptAt += snap.promptIntervalFrames.coerceAtLeast(blockFrames.toLong())
            }
        } else if (nextPromptAt >= 0) {
            // 刚解除：未播出的提示声部极短淡出，后续不再安排
            mixer.fadeOutGroup(PcmMixer.Group.PROMPT, PROMPT_FADE_FRAMES)
            nextPromptAt = -1
        }

        // 3) 一次性声音（结束提示等）
        while (true) {
            val one = pendingOneShots.poll() ?: break
            mixer.addVoice(one, 0, PcmMixer.Group.ONESHOT, PcmMixer.Target.BOTH)
        }

        // 4) 报警长音（LONG_TONE 模式）：相位连续 + 约 4ms 淡入淡出
        val toneTarget = if (snap.alarmToneActive && snap.soundEnabled) ALARM_GAIN else 0.0
        if (toneTarget > 0.0 || alarmGain > 0.0) {
            val fadeStep = ALARM_GAIN / (ALARM_FADE_SEC * sampleRate)
            for (i in 0 until blockFrames) {
                alarmPhase += 2.0 * PI_D * ALARM_FREQ / sampleRate
                if (alarmPhase >= 2.0 * PI_D) alarmPhase -= 2.0 * PI_D
                alarmGain = when {
                    alarmGain < toneTarget -> minOf(toneTarget, alarmGain + fadeStep)
                    alarmGain > toneTarget -> maxOf(0.0, alarmGain - fadeStep)
                    else -> alarmGain
                }
                accL[i] += (sin(alarmPhase) * alarmGain * 32767.0).toInt()
                if (stereo) accR[i] += accL[i]
            }
        }

        // 5) 混音（宽位累加，不裁剪）
        mixer.mixInto(accL, if (stereo) accR else null)

        // 6) 最终化：门控斜坡 × 主音量，一次限幅
        val gateTarget = if (snap.gateOpen) 1f else 0f
        val gateStep = GATE_RAMP_FRAMES.toFloat()
        var peakL = 0
        var peakR = 0
        val vol = snap.volume.coerceIn(0f, 1f)
        if (stereo) {
            for (i in 0 until blockFrames) {
                gate += (gateTarget - gate) / gateStep
                val l = finalizeSample(accL[i], gate * vol); out[i * 2] = l
                val r = finalizeSample(accR[i], gate * vol); out[i * 2 + 1] = r
                val li = if (l < 0) -l.toInt() else l.toInt()
                val ri = if (r < 0) -r.toInt() else r.toInt()
                if (li > peakL) peakL = li
                if (ri > peakR) peakR = ri
            }
        } else {
            for (i in 0 until blockFrames) {
                gate += (gateTarget - gate) / gateStep
                val l = finalizeSample(accL[i], gate * vol); out[i] = l
                val li = if (l < 0) -l.toInt() else l.toInt()
                if (li > peakL) peakL = li
            }
        }
        return BlockResult(beats, peakL, peakR)
    }

    companion object {
        private const val PI_D = Math.PI
        private const val ALARM_FREQ = 440.0
        private const val ALARM_GAIN = 0.3
        private const val ALARM_FADE_SEC = 0.004
        private const val LEAD_FRAMES = 480      // 新会话/重锚后第一拍延迟 10ms
        private const val PROMPT_LEAD_FRAMES = 480
        private const val PROMPT_FADE_FRAMES = 480   // 提示取消淡出 10ms
        private const val GATE_RAMP_FRAMES = 288     // 门控全行程约 6ms @48k（逐帧比例逼近）

        /** 单样本最终化：增益 + 峰值兜底限幅（正常设置不触发） */
        fun finalizeSample(acc: Int, gain: Float): Short {
            val v = acc * gain
            return when {
                v > 32700f -> 32700f
                v < -32700f -> -32700f
                else -> v
            }.toInt().toShort()
        }
    }
}
