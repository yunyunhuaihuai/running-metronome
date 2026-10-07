package com.metronome.app.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 报警配置（用户可调参数；触发持续时间/恢复确认属于"有意等待"，
 * 与软件额外延迟分开统计，见验证报告）。
 */
data class AlarmConfig(
    val slowMarginSpm: Int = 8,      // 慢于 target-slowMargin 判偏慢
    val alarmAfterSec: Int = 5,      // 偏慢累计达到该时长 → 报警
    val fastMarginSpm: Int = 3,      // 不低于 target-fastMargin 持续 → 解除
    val recoverMs: Long = 600,       // 恢复确认时长
    val repeatSec: Int = 2,          // 短提示重复间隔
    val style: AlarmStyle = AlarmStyle.OVERLAY,
) {
    fun validated() = run {
        val slow = slowMarginSpm.coerceIn(SLOW_MARGIN_MIN, SLOW_MARGIN_MAX)
        copy(
            slowMarginSpm = slow,
            alarmAfterSec = alarmAfterSec.coerceIn(AFTER_SEC_MIN, AFTER_SEC_MAX),
            fastMarginSpm = fastMarginSpm.coerceIn(FAST_MARGIN_MIN, slow - 1),
            recoverMs = recoverMs.coerceIn(RECOVER_MS_MIN, RECOVER_MS_MAX),
            repeatSec = repeatSec.coerceIn(REPEAT_SEC_MIN, REPEAT_SEC_MAX),
        )
    }

    companion object {
        const val SLOW_MARGIN_MIN = 4
        const val SLOW_MARGIN_MAX = 20
        const val AFTER_SEC_MIN = 1
        const val AFTER_SEC_MAX = 15
        const val FAST_MARGIN_MIN = 1
        const val RECOVER_MS_MIN = 200L
        const val RECOVER_MS_MAX = 3_000L
        const val REPEAT_SEC_MIN = 1
        const val REPEAT_SEC_MAX = 10
    }
}

enum class AlarmStyle { OVERLAY, LONG_TONE }

/** 自定义音频资源的稳定引用：id 指向 App 私有目录副本，displayName 仅用于展示 */
data class AudioAsset(
    val id: String,
    val displayName: String,
)

/**
 * 命名训练预设：一份完整可应用配置。覆盖目标步频、输出方式/音量、
 * 两脚音色与自定义音频资源、振动、报警参数与训练计划。
 * 应用预设不会自行启动训练，也不清空运行中会话（由服务层保证）。
 */
data class Preset(
    val id: String,
    val name: String,
    val targetSpm: Int,
    val soundOn: Boolean,
    val beatVolume: Int,
    val channelMode: BlockRenderer.ChannelMode,
    val leftTimbre: Int,
    val rightTimbre: Int,
    val leftCustom: AudioAsset?,
    val rightCustom: AudioAsset?,
    val leftUseCustom: Boolean,
    val rightUseCustom: Boolean,
    val vibrateOn: Boolean,
    val vibrateStrength: Int,
    val detectionEnabled: Boolean,
    val alarmEnabled: Boolean,
    val alarm: AlarmConfig,
    val training: TrainingConfig,
)

/**
 * 训练配置的轻量序列化（纯 JDK）：用于 SharedPreferences 持久化。
 * 格式：timer|total|prep|segCount|seg…|loopStart|loopEnd|loopRounds
 */
object TrainingConfigCodec {
    fun encode(c: TrainingConfig): String = buildString {
        append(if (c.timerEnabled) 1 else 0).append('|')
        append(c.totalDurationSec).append('|')
        append(c.prepareCountdownSec).append('|')
        append(c.segments.size)
        for (s in c.segments) {
            append('|').append(PresetCodec.encodeSegment(s))
        }
        val l = c.loop
        append('|').append(l?.startIndex ?: -1)
        append('|').append(l?.endIndexInclusive ?: -1)
        append('|').append(l?.rounds ?: 0)
    }

    fun decode(text: String?): TrainingConfig {
        if (text.isNullOrBlank()) return TrainingConfig()
        return try {
            val f = text.split('|')
            if (f.size < 4) return TrainingConfig()
            var i = 0
            val timerEn = f[i++].toInt() == 1
            val total = f[i++].toInt()
            val prep = f[i++].toInt()
            val n = f[i++].toInt()
            val segs = ArrayList<TrainingSegment>(n.coerceAtMost(64))
            repeat(n.coerceAtMost(64)) {
                if (i < f.size) PresetCodec.decodeSegment(f[i++])?.let { segs.add(it) }
            }
            val ls = f.getOrElse(i) { "-1" }.toInt()
            val le = f.getOrElse(i + 1) { "-1" }.toInt()
            val lr = f.getOrElse(i + 2) { "0" }.toInt()
            val loop = if (ls >= 0 && le >= ls && lr >= 1) LoopSpec(ls, le, lr) else null
            TrainingConfig(
                timerEnabled = timerEn, totalDurationSec = total,
                prepareCountdownSec = prep, segments = segs, loop = loop,
            )
        } catch (_: Exception) {
            TrainingConfig()
        }
    }
}

/**
 * 预设序列化（纯 JDK，可 JVM 测试）。格式版本化；解码时跳过损坏条目。
 * 所有字符串字段 URL 编码，分隔符不会与内容冲突。
 *
 * 行格式：V1|字段…；每个段为独立编码单元。
 */
object PresetCodec {

    private const val FIELD = '|'
    private const val SEG_SEP: Char = 1.toChar()
    private const val SEG_FIELD: Char = 2.toChar()
    private const val VERSION = "V1"

    fun encode(presets: List<Preset>): String = buildString {
        presets.forEach { p ->
            if (isNotEmpty()) append('\n')
            append(VERSION).append(FIELD)
            append(enc(p.id)).append(FIELD)
            append(enc(p.name)).append(FIELD)
            append(p.targetSpm).append(FIELD)
            append(if (p.soundOn) 1 else 0).append(FIELD)
            append(p.beatVolume).append(FIELD)
            append(if (p.channelMode == BlockRenderer.ChannelMode.ALTERNATE_LR) 1 else 0).append(FIELD)
            append(p.leftTimbre).append(FIELD)
            append(p.rightTimbre).append(FIELD)
            append(enc(p.leftCustom?.id ?: "")).append(FIELD)
            append(enc(p.leftCustom?.displayName ?: "")).append(FIELD)
            append(enc(p.rightCustom?.id ?: "")).append(FIELD)
            append(enc(p.rightCustom?.displayName ?: "")).append(FIELD)
            append(if (p.leftUseCustom) 1 else 0).append(FIELD)
            append(if (p.rightUseCustom) 1 else 0).append(FIELD)
            append(if (p.vibrateOn) 1 else 0).append(FIELD)
            append(p.vibrateStrength).append(FIELD)
            append(if (p.detectionEnabled) 1 else 0).append(FIELD)
            append(if (p.alarmEnabled) 1 else 0).append(FIELD)
            append(p.alarm.slowMarginSpm).append(FIELD)
            append(p.alarm.alarmAfterSec).append(FIELD)
            append(p.alarm.fastMarginSpm).append(FIELD)
            append(p.alarm.recoverMs).append(FIELD)
            append(p.alarm.repeatSec).append(FIELD)
            append(if (p.alarm.style == AlarmStyle.LONG_TONE) 1 else 0).append(FIELD)
            append(if (p.training.timerEnabled) 1 else 0).append(FIELD)
            append(p.training.totalDurationSec).append(FIELD)
            append(p.training.prepareCountdownSec).append(FIELD)
            append(enc(encodeSegments(p.training.segments))).append(FIELD)
            val l = p.training.loop
            if (l == null) append(-1).append(FIELD).append(-1).append(FIELD).append(0)
            else append(l.startIndex).append(FIELD).append(l.endIndexInclusive).append(FIELD).append(l.rounds)
        }
    }

    fun decode(text: String): List<Preset> = text.lines().mapNotNull { line ->
        val f = line.split(FIELD)
        if (f.size < 32 || f[0] != VERSION) return@mapNotNull null
        try {
            var i = 1
            fun next(): String = f[i++]
            fun int(): Int = next().toInt()
            fun bool(): Boolean = int() == 1
            val id = dec(next())
            val name = dec(next())
            val spm = int()
            val soundOn = bool()
            val vol = int()
            val chan = if (int() == 1) BlockRenderer.ChannelMode.ALTERNATE_LR else BlockRenderer.ChannelMode.CENTER
            val lt = int(); val rt = int()
            val lcid = dec(next()); val lname = dec(next())
            val rcid = dec(next()); val rname = dec(next())
            val lUse = bool(); val rUse = bool()
            val vib = bool(); val vibS = int()
            val detect = bool(); val alarmEn = bool()
            val alarm = AlarmConfig(
                slowMarginSpm = int(), alarmAfterSec = int(), fastMarginSpm = int(),
                recoverMs = next().toLong(), repeatSec = int(),
                style = if (int() == 1) AlarmStyle.LONG_TONE else AlarmStyle.OVERLAY,
            )
            val timerEn = bool(); val totalSec = int(); val prepSec = int()
            val segments = decodeSegments(dec(next()))
            val ls = int(); val le = int(); val lr = int()
            val loop = if (ls >= 0 && le >= ls && lr >= 1) LoopSpec(ls, le, lr) else null
            Preset(
                id = id, name = name, targetSpm = spm, soundOn = soundOn, beatVolume = vol,
                channelMode = chan, leftTimbre = lt, rightTimbre = rt,
                leftCustom = if (lcid.isEmpty()) null else AudioAsset(lcid, lname),
                rightCustom = if (rcid.isEmpty()) null else AudioAsset(rcid, rname),
                leftUseCustom = lUse, rightUseCustom = rUse,
                vibrateOn = vib, vibrateStrength = vibS,
                detectionEnabled = detect, alarmEnabled = alarmEn,
                alarm = alarm.validated(),
                training = TrainingConfig(
                    timerEnabled = timerEn, totalDurationSec = totalSec,
                    prepareCountdownSec = prepSec, segments = segments, loop = loop,
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeSegments(segs: List<TrainingSegment>): String =
        segs.joinToString(SEG_SEP.toString()) { encodeSegment(it) }

    private fun decodeSegments(text: String): List<TrainingSegment> =
        if (text.isEmpty()) emptyList() else text.split(SEG_SEP).mapNotNull { decodeSegment(it) }

    /** 单段编码：name<US>dur<US>spm（ TrainingConfigCodec 与预设共用） */
    fun encodeSegment(s: TrainingSegment): String =
        "${enc(s.name)}$SEG_FIELD${s.durationSec}$SEG_FIELD${s.targetSpm}"

    fun decodeSegment(text: String): TrainingSegment? {
        val p = text.split(SEG_FIELD)
        if (p.size != 3) return null
        return try {
            TrainingSegment(dec(p[0]), p[1].toInt(), p[2].toInt())
        } catch (_: Exception) {
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun dec(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8.name())
    } catch (_: Exception) {
        s
    }
}
