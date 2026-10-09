package com.metronome.app

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * 【仅 debug 构建】最小受控播放端：验证"先音乐后节拍"的压低链路。
 *
 * 启动后：
 *  1. 申请 AUDIOFOCUS_GAIN（模拟一个普通音乐播放器）；
 *  2. 持续播放 880Hz 正弦波（音乐替身）约 60 秒，期间所有焦点回调
 *     以 MetroDuck 标签写入 logcat（接收 duck 请求时把自身音量降到 20%）；
 *  3. 界面显示当前焦点状态；按返回键退出并放弃焦点。
 *
 * 配合主 App：先启动本 Activity（音乐响），再启动节拍会话 → 观察
 * MetroDuck 是否收到 LOSS_TRANSIENT_CAN_DUCK 并压低（系统 duck 行为）；
 * 反向顺序：先节拍后本播放器 → 节拍器收到 LOSS，MetroFocus 记录状态。
 */
class DuckProbeActivity : Activity() {

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var ducked = false
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var statusText: TextView

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val msg = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS (permanent)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            else -> "UNKNOWN($change)"
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) ducked = true
        if (change == AudioManager.AUDIOFOCUS_GAIN) ducked = false
        android.util.Log.i(TAG, "focus change: $msg")
        runOnUiThread { statusText.text = "焦点事件: $msg" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true) // 锁屏用例之后仍可由 ADB 重建受控播放端
        }
        active = WeakReference(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val title = TextView(this).apply {
            text = "DuckProbe：模拟音乐播放器（880Hz）\n焦点事件见 logcat -s MetroDuck"
        }
        statusText = TextView(this).apply { text = "准备中…" }
        box.addView(title)
        box.addView(statusText)
        setContentView(box)
        android.util.Log.i(TAG, "probe started")

        val am = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = try { am?.requestAudioFocus(focusRequest!!) } catch (_: Exception) { -1 }
        android.util.Log.i(TAG, "request GAIN -> $granted")
        statusText.text = "焦点请求: $granted"

        running = true
        playThread = Thread {
            val rate = 48000
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, 4800))
                .build()
            track = at
            try {
                at.play()
                val block = ShortArray(4800)   // 100ms
                var phase = 0.0
                var i = 0
                while (running && i < 600) {   // 60 秒
                    for (j in block.indices) {
                        phase += 2 * PI * 880 / rate
                        if (phase >= 2 * PI) phase -= 2 * PI
                        val gain = if (ducked) 0.2 else 0.5
                        block[j] = (sin(phase) * gain * 32767).toInt().toShort()
                    }
                    at.write(block, 0, block.size)
                    i++
                }
            } catch (_: Exception) {
            } finally {
                try { at.stop() } catch (_: Exception) {}
                try { at.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        running = false
        playThread?.join(1000)
        focusRequest?.let { req ->
            try {
                getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
            } catch (_: Exception) {}
        }
        android.util.Log.i(TAG, "probe destroyed, focus abandoned")
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MetroDuck"
        private var active: WeakReference<DuckProbeActivity>? = null

        /** 仅供 debug 广播测试使用；无需依赖锁屏上的返回键。 */
        fun finishForTest() {
            active?.get()?.finish()
        }
    }
}
