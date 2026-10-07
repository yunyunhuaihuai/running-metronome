package com.metronome.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.metronome.app.core.BlockRenderer
import kotlin.math.min

/**
 * 试听播放器：使用与正式播放完全相同的音色解析、音量与最终化处理
 * （[SoundBank.resolveBeat] × [MetronomeEngine.beatVolume] × [BlockRenderer.finalizeSample]），
 * 但使用独立 AudioTrack：
 *  - 不启动训练、不增加拍数、不改变会话进度；
 *  - 播完自动释放，不遗留播放资源；重复点击替换当前试听；
 *  - 会话未运行时临时申请 MAY_DUCK 焦点（不打断音乐，压低其音量），
 *    播完放弃；会话运行中（本 App 已持有焦点）不再申请，避免自抢。
 */
object PreviewManager {
    private var thread: HandlerThread? = null
    private var track: AudioTrack? = null
    private var generation = 0

    fun preview(context: Context, foot: BlockRenderer.Foot) {
        val app = context.applicationContext
        stop()
        generation++
        val myGen = generation
        val pcm = SoundBank.resolveBeat(foot)   // 与正式播放同一解析（含自定义回退）
        if (pcm.isEmpty()) return
        val volume = MetronomeEngine.beatVolume.value / 100f
        val focusOwnedByService = MetronomeEngine.running.value

        thread = HandlerThread("metronome-preview").also { it.start() }
        val handler = Handler(thread!!.looper)
        handler.post {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            var focusRequest: AudioFocusRequest? = null
            if (!focusOwnedByService) {
                val am = app.getSystemService(AudioManager::class.java)
                if (am != null) {
                    val req = AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    ).setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    ).build()
                    try { am.requestAudioFocus(req) } catch (_: Exception) {}
                    focusRequest = req
                }
            }
            val minBuf = AudioTrack.getMinBufferSize(
                MetronomeEngine.RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
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
                        .setSampleRate(MetronomeEngine.RATE)
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
                // 与正式路径相同的最终化公式：极短起坡 × 节拍音量 × 限幅，末尾淡出
                val ramp = 288
                val buf = ShortArray(pcm.size + ramp)
                val vol = volume.coerceIn(0f, 1f)
                for (i in buf.indices) {
                    val src = if (i < pcm.size) pcm[i].toInt()
                    else (pcm[pcm.size - 1] * (1f - (i - pcm.size + 1f) / ramp)).toInt()
                    val rampIn = if (i < ramp) (i + 1f) / ramp else 1f
                    buf[i] = BlockRenderer.finalizeSample(src, vol * rampIn)
                }
                var off = 0
                while (off < buf.size && generation == myGen) {
                    val n = at.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                }
                Thread.sleep(30)
            } catch (_: Exception) {
            } finally {
                try { at.stop() } catch (_: Exception) {}
                try { at.release() } catch (_: Exception) {}
                if (track === at) track = null
                focusRequest?.let { req ->
                    try {
                        app.getSystemService(AudioManager::class.java)
                            ?.abandonAudioFocusRequest(req)
                    } catch (_: Exception) {}
                }
                if (generation == myGen) stopThread()
            }
        }
    }

    fun stop() {
        generation++
        stopThread()
    }

    private fun stopThread() {
        thread?.quitSafely()
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
    }
}
