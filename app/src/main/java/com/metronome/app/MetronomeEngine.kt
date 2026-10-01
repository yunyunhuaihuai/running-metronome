package com.metronome.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * UI 与前台服务共享的全局状态单例。
 * UI 侧只写设置；服务侧的音频线程在每个采样块边界读取最新值，
 * 因此 BPM / 开关的变更无需任何跨线程通知即实时生效。
 */
object MetronomeEngine {
    /** 输出采样率，也是 PCM 节拍时钟的基准 */
    const val RATE = 48000
    const val TAG_BEAT = "MetroBeat"
    const val TAG_STATE = "MetroState"

    val bpm = MutableStateFlow(120)          // 0 = 停止
    val soundOn = MutableStateFlow(true)
    val vibrateOn = MutableStateFlow(false)
    /** 振动强度百分比 1..100（100 = 最强，出厂默认） */
    val vibrateStrength = MutableStateFlow(100)
    val timbreIndex = MutableStateFlow(0)
    val useCustomSound = MutableStateFlow(false)
    val customSoundName = MutableStateFlow<String?>(null)

    /** 前台服务是否存活 */
    val running = MutableStateFlow(false)
    /** 音频时钟是否在走（BPM > 0） */
    val clockRunning = MutableStateFlow(false)
    /** 已实际播出的节拍数（由 AudioTrack marker 回调递增，代表"听到的那一下"） */
    val beatCount = MutableStateFlow(0L)

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext
                .getSharedPreferences("metronome", Context.MODE_PRIVATE)
            bpm.value = p.getInt("bpm", 120).coerceIn(0, 200)
            soundOn.value = p.getBoolean("soundOn", true)
            vibrateOn.value = p.getBoolean("vibrateOn", false)
            vibrateStrength.value = p.getInt("vibrateStrength", 100).coerceIn(1, 100)
            timbreIndex.value = p.getInt("timbre", 0).coerceIn(0, SoundBank.BUILT_IN.size - 1)
            useCustomSound.value = p.getBoolean("useCustom", false)
            customSoundName.value = p.getString("customName", null)
            SoundBank.loadCustomUri(p)
            prefs = p
        }
    }

    fun setBpm(v: Int) {
        val c = v.coerceIn(0, 200)
        bpm.value = c
        save("bpm", c)
    }

    fun setSoundOn(v: Boolean) { soundOn.value = v; save("soundOn", v) }
    fun setVibrateOn(v: Boolean) { vibrateOn.value = v; save("vibrateOn", v) }

    fun setVibrateStrength(v: Int) {
        val c = v.coerceIn(1, 100)
        vibrateStrength.value = c
        save("vibrateStrength", c)
    }
    fun setTimbre(i: Int) { timbreIndex.value = i; save("timbre", i) }
    fun setUseCustom(v: Boolean) { useCustomSound.value = v; save("useCustom", v) }

    fun setCustomSound(name: String?, uri: String?) {
        customSoundName.value = name
        SoundBank.customUriString = uri
        save("customName", name)
        save("customUri", uri)
    }

    private fun save(key: String, value: Any?) {
        prefs?.edit()?.apply {
            when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                else -> remove(key)
            }
        }?.apply()
    }

    fun logState(where: String) {
        val ti = timbreIndex.value.coerceIn(0, SoundBank.BUILT_IN.size - 1)
        Log.i(
            TAG_STATE,
            "state=$where bpm=${bpm.value} sound=${soundOn.value} vibrate=${vibrateOn.value}" +
                "(${vibrateStrength.value}%) " +
                "timbre=${SoundBank.BUILT_IN[ti]} custom=${useCustomSound.value}(${customSoundName.value ?: "无"}) " +
                "running=${running.value} clock=${clockRunning.value} beats=${beatCount.value}"
        )
    }
}
