package com.metronome.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.metronome.app.core.AlarmConfig
import com.metronome.app.core.AlarmStyle
import com.metronome.app.core.AudioAsset
import com.metronome.app.core.BlockRenderer
import com.metronome.app.core.Preset
import com.metronome.app.core.PresetCodec
import com.metronome.app.core.SessionStateMachine
import com.metronome.app.core.TrainingConfig
import com.metronome.app.core.TrainingConfigCodec
import com.metronome.app.core.TrainingTimeline
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * UI 与前台服务共享的全局状态单例。
 * UI 侧只写设置；服务侧的音频线程在每个采样块边界读取最新值，
 * 因此目标步频 / 开关的变更无需跨线程通知即实时生效。
 *
 * 目标步频（targetSpm）即节拍速度：一拍对应一步，跑步界面统一用 SPM。
 * BPM=0 不再作为暂停手段——会话由 SessionState 管理，暂停是显式动作。
 */
object MetronomeEngine {
    /** 输出采样率，也是 PCM 节拍时钟的基准 */
    const val RATE = 48000
    const val TAG_BEAT = "MetroBeat"
    const val TAG_STATE = "MetroState"
    const val TAG_FOCUS = "MetroFocus"

    const val SPM_MIN = 20
    const val SPM_MAX = 200

    // ------------------------------------------------------------ 设置
    val targetSpm = MutableStateFlow(120)
    val soundOn = MutableStateFlow(true)
    val beatVolume = MutableStateFlow(80)           // 节拍独立音量 0..100（不改系统媒体音量）
    val vibrateOn = MutableStateFlow(false)
    /** 振动强度百分比 1..100（100 = 最强，出厂默认） */
    val vibrateStrength = MutableStateFlow(100)

    val channelMode = MutableStateFlow(BlockRenderer.ChannelMode.CENTER)
    val leftTimbre = MutableStateFlow(0)
    val rightTimbre = MutableStateFlow(1)
    val leftUseCustom = MutableStateFlow(false)
    val rightUseCustom = MutableStateFlow(false)
    val leftCustom = MutableStateFlow<AudioAsset?>(null)
    val rightCustom = MutableStateFlow<AudioAsset?>(null)

    /** 步频检测开关（与报警开关独立：检测可开、报警可关，反之报警依赖检测） */
    val detectionEnabled = MutableStateFlow(true)
    val alarmEnabled = MutableStateFlow(true)
    val alarmConfig = MutableStateFlow(AlarmConfig())

    /** 训练配置（定时/分段/循环/准备倒计时）；会话启动时做快照，运行中编辑不影响已运行会话 */
    val trainingConfig = MutableStateFlow(TrainingConfig())

    /** 每次应用预设后自增，用于 UI 一次性刷新 */
    val presetAppliedTick = MutableStateFlow(0)

    // ------------------------------------------------------------ 会话观察
    /** 前台服务是否存活 */
    val running = MutableStateFlow(false)
    /** 音频时钟是否在走（会话 RUNNING/PREPARING 且未暂停） */
    val clockRunning = MutableStateFlow(false)
    /** 已实际播出的节拍数（marker 回调递增，代表"听到的那一下"；计的是节拍不是脚步） */
    val beatCount = MutableStateFlow(0L)
    val sessionState = MutableStateFlow(SessionStateMachine.State.IDLE)
    /** 当前训练时间线位置（服务控制线程发布；null = 无会话） */
    val sessionPos = MutableStateFlow<TrainingTimeline.Position?>(null)

    // ------------------------------------------------------------ 自定义音频加载状态
    enum class LoadStatus { NONE, LOADING, READY, FAILED }

    data class CustomLoadState(
        val status: LoadStatus = LoadStatus.NONE,
        val message: String? = null,
    )

    val leftLoad = MutableStateFlow(CustomLoadState())
    val rightLoad = MutableStateFlow(CustomLoadState())

    // ------------------------------------------------------------ 预设
    val presets = MutableStateFlow<List<Preset>>(emptyList())

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext
                .getSharedPreferences("metronome", Context.MODE_PRIVATE)
            prefs = p
            SoundBank.ensureBuiltIns()   // 非实时线程提前合成，音频线程只读

            targetSpm.value = p.getInt("targetSpm", p.getInt("bpm", 120)).coerceIn(SPM_MIN, SPM_MAX)
            soundOn.value = p.getBoolean("soundOn", true)
            beatVolume.value = p.getInt("beatVolume", 80).coerceIn(0, 100)
            vibrateOn.value = p.getBoolean("vibrateOn", false)
            vibrateStrength.value = p.getInt("vibrateStrength", 100).coerceIn(1, 100)
            channelMode.value = if (p.getInt("channelMode", 0) == 1)
                BlockRenderer.ChannelMode.ALTERNATE_LR else BlockRenderer.ChannelMode.CENTER
            val timbres = SoundBank.BUILT_IN.size
            val leftT = p.getInt("leftTimbre", p.getInt("timbre", 0)).coerceIn(0, timbres - 1)
            val rightT = p.getInt("rightTimbre", leftT).coerceIn(0, timbres - 1)
            leftTimbre.value = leftT
            rightTimbre.value = rightT
            leftCustom.value = loadAsset(p, "leftCustomId", "leftCustomName")
            rightCustom.value = loadAsset(p, "rightCustomId", "rightCustomName")
            leftUseCustom.value = p.getBoolean("leftUseCustom", false) && leftCustom.value != null
            rightUseCustom.value = p.getBoolean("rightUseCustom", false) && rightCustom.value != null
            detectionEnabled.value = p.getBoolean("detectionEnabled", true)
            alarmEnabled.value = p.getBoolean("alarmEnabled", true)
            alarmConfig.value = AlarmConfig(
                slowMarginSpm = p.getInt("alarmSlowMargin", 8),
                alarmAfterSec = p.getInt("alarmAfterSec", 5),
                fastMarginSpm = p.getInt("alarmFastMargin", 3),
                recoverMs = p.getLong("alarmRecoverMs", 600),
                repeatSec = p.getInt("alarmRepeatSec", 2),
                style = if (p.getInt("alarmStyle", 0) == 1) AlarmStyle.LONG_TONE else AlarmStyle.OVERLAY,
            ).validated()
            trainingConfig.value = TrainingConfigCodec.decode(p.getString("trainingConfig", null))
            presets.value = PresetCodec.decode(p.getString("presets", "") ?: "")

            migrateV1CustomAudio(context, p)
            // 已保存的双脚自定义资源从私有目录恢复 PCM
            AudioImporter.restoreFromAssets(context)
        }
    }

    /** 旧版单音色/单自定义音频迁移：映射到两脚共用，保留已有偏好 */
    private fun migrateV1CustomAudio(context: Context, p: SharedPreferences) {
        if (p.getBoolean("migrated_v2", false)) return
        val useCustom = p.getBoolean("useCustom", false)
        val uri = p.getString("customUri", null)
        val name = p.getString("customName", null)
        if (useCustom && uri != null && leftCustom.value == null) {
            // 异步解码旧 URI 并落为双脚共用的稳定资源
            AudioImporter.migrateLegacy(context, uri, name)
        }
        p.edit().putBoolean("migrated_v2", true).apply()
    }

    private fun loadAsset(p: SharedPreferences, idKey: String, nameKey: String): AudioAsset? {
        val id = p.getString(idKey, null) ?: return null
        return AudioAsset(id, p.getString(nameKey, null) ?: id)
    }

    fun setTargetSpm(v: Int) {
        val c = v.coerceIn(SPM_MIN, SPM_MAX)
        targetSpm.value = c
        save("targetSpm", c)
    }

    fun setSoundOn(v: Boolean) { soundOn.value = v; save("soundOn", v) }

    fun setBeatVolume(v: Int) {
        val c = v.coerceIn(0, 100)
        beatVolume.value = c
        save("beatVolume", c)
    }

    fun setVibrateOn(v: Boolean) { vibrateOn.value = v; save("vibrateOn", v) }

    fun setVibrateStrength(v: Int) {
        val c = v.coerceIn(1, 100)
        vibrateStrength.value = c
        save("vibrateStrength", c)
    }

    fun setChannelMode(m: BlockRenderer.ChannelMode) {
        channelMode.value = m
        save("channelMode", if (m == BlockRenderer.ChannelMode.ALTERNATE_LR) 1 else 0)
    }

    fun setLeftTimbre(i: Int) {
        val c = i.coerceIn(0, SoundBank.BUILT_IN.size - 1)
        leftTimbre.value = c; save("leftTimbre", c)
    }

    fun setRightTimbre(i: Int) {
        val c = i.coerceIn(0, SoundBank.BUILT_IN.size - 1)
        rightTimbre.value = c; save("rightTimbre", c)
    }

    fun setLeftUseCustom(v: Boolean) {
        if (!v) AudioImporter.cancelPending(BlockRenderer.Foot.LEFT)
        leftUseCustom.value = v
        save("leftUseCustom", v)
    }
    fun setRightUseCustom(v: Boolean) {
        if (!v) AudioImporter.cancelPending(BlockRenderer.Foot.RIGHT)
        rightUseCustom.value = v
        save("rightUseCustom", v)
    }

    fun setLeftCustom(asset: AudioAsset?) {
        leftCustom.value = asset
        save("leftCustomId", asset?.id)
        save("leftCustomName", asset?.displayName)
    }

    fun setRightCustom(asset: AudioAsset?) {
        rightCustom.value = asset
        save("rightCustomId", asset?.id)
        save("rightCustomName", asset?.displayName)
    }

    fun setDetectionEnabled(v: Boolean) { detectionEnabled.value = v; save("detectionEnabled", v) }
    fun setAlarmEnabled(v: Boolean) { alarmEnabled.value = v; save("alarmEnabled", v) }

    fun setAlarmConfig(c: AlarmConfig) {
        alarmConfig.value = c.validated()
        alarmConfig.value.let {
            save("alarmSlowMargin", it.slowMarginSpm)
            save("alarmAfterSec", it.alarmAfterSec)
            save("alarmFastMargin", it.fastMarginSpm)
            save("alarmRecoverMs", it.recoverMs)
            save("alarmRepeatSec", it.repeatSec)
            save("alarmStyle", if (it.style == AlarmStyle.LONG_TONE) 1 else 0)
        }
    }

    fun setTrainingConfig(c: TrainingConfig) {
        trainingConfig.value = c
        save("trainingConfig", TrainingConfigCodec.encode(c))
    }

    // ------------------------------------------------------------ 预设存取

    fun upsertPreset(preset: Preset) {
        val list = presets.value.toMutableList()
        val idx = list.indexOfFirst { it.id == preset.id }
        if (idx >= 0) list[idx] = preset else list.add(preset)
        presets.value = list
        save("presets", PresetCodec.encode(list))
    }

    fun deletePreset(id: String) {
        val list = presets.value.filterNot { it.id == id }
        presets.value = list
        save("presets", PresetCodec.encode(list))
    }

    /** 从当前设置构造预设快照 */
    fun currentAsPreset(id: String, name: String): Preset = Preset(
        id = id, name = name, targetSpm = targetSpm.value, soundOn = soundOn.value,
        beatVolume = beatVolume.value, channelMode = channelMode.value,
        leftTimbre = leftTimbre.value, rightTimbre = rightTimbre.value,
        leftCustom = leftCustom.value, rightCustom = rightCustom.value,
        leftUseCustom = leftUseCustom.value, rightUseCustom = rightUseCustom.value,
        vibrateOn = vibrateOn.value, vibrateStrength = vibrateStrength.value,
        detectionEnabled = detectionEnabled.value, alarmEnabled = alarmEnabled.value,
        alarm = alarmConfig.value, training = trainingConfig.value,
    )

    /** 应用预设：只写设置，不启动训练、不影响运行中会话（音频线程逐块读取新值） */
    fun applyPreset(p: Preset) {
        setTargetSpm(p.targetSpm)
        setSoundOn(p.soundOn)
        setBeatVolume(p.beatVolume)
        setChannelMode(p.channelMode)
        setLeftTimbre(p.leftTimbre)
        setRightTimbre(p.rightTimbre)
        setLeftCustom(p.leftCustom)
        setRightCustom(p.rightCustom)
        setLeftUseCustom(p.leftUseCustom && p.leftCustom != null)
        setRightUseCustom(p.rightUseCustom && p.rightCustom != null)
        setVibrateOn(p.vibrateOn)
        setVibrateStrength(p.vibrateStrength)
        setDetectionEnabled(p.detectionEnabled)
        setAlarmEnabled(p.alarmEnabled)
        setAlarmConfig(p.alarm)
        setTrainingConfig(p.training)
        presetAppliedTick.value += 1
    }

    private fun save(key: String, value: Any?) {
        prefs?.edit()?.apply {
            when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                else -> remove(key)
            }
        }?.apply()
    }

    fun logState(where: String) {
        Log.i(
            TAG_STATE,
            "state=$where spm=${targetSpm.value} sound=${soundOn.value} vol=${beatVolume.value}" +
                " vibrate=${vibrateOn.value}(${vibrateStrength.value}%) chan=${channelMode.value}" +
                " L=${timbreName(leftTimbre.value, leftUseCustom.value, leftCustom.value)}" +
                " R=${timbreName(rightTimbre.value, rightUseCustom.value, rightCustom.value)}" +
                " detect=${detectionEnabled.value} alarm=${alarmEnabled.value}" +
                " running=${running.value} clock=${clockRunning.value} session=${sessionState.value}" +
                " beats=${beatCount.value}"
        )
    }

    private fun timbreName(idx: Int, useCustom: Boolean, asset: AudioAsset?): String =
        if (useCustom) "custom(${asset?.displayName ?: "缺失"})" else
            SoundBank.BUILT_IN.getOrElse(idx) { "?" }

    fun logFocus(where: String) {
        Log.i(TAG_FOCUS, where)
    }
}
