package com.metronome.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 音色来源：内置音色为启动时合成的 PCM；自定义音频经 MediaCodec 解码、
 * 混缩为单声道并线性重采样到 48kHz，统一格式后混入同一输出流。
 */
object SoundBank {
    private const val TAG = "SoundBank"
    val BUILT_IN = listOf("咔嗒", "哔声", "木鱼", "牛铃")
    const val RATE = MetronomeEngine.RATE
    const val MAX_CUSTOM_SECONDS = 3.0

    @Volatile
    var customPcm: ShortArray? = null

    @Volatile
    var customUriString: String? = null

    @Volatile
    private var builtIns: Array<ShortArray>? = null

    /** 音频线程调用：无锁读 volatile 引用 */
    fun currentSound(timbreIndex: Int, useCustom: Boolean): ShortArray {
        if (useCustom) customPcm?.let { return it }
        val arr = ensureBuiltIns()
        return arr[timbreIndex.coerceIn(0, arr.size - 1)]
    }

    fun loadCustomUri(prefs: android.content.SharedPreferences) {
        customUriString = prefs.getString("customUri", null)
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun ensureBuiltIns(): Array<ShortArray> {
        builtIns?.let { return it }
        synchronized(this) {
            builtIns?.let { return it }
            val arr = arrayOf(
                // 咔嗒：高频阻尼正弦 + 2ms 噪声头
                render(0.020) { t ->
                    sin(2 * PI * 1900 * t) * exp(-t * 300) * 0.9 + clickNoise(t)
                },
                // 哔声：1kHz 纯音，5ms 起 12ms 落
                render(0.070) { t ->
                    val env = min(1.0, t / 0.005) *
                        min(1.0, (0.070 - t) / 0.012).coerceIn(0.0, 1.0)
                    sin(2 * PI * 1000 * t) * env * 0.85
                },
                // 木鱼：双频阻尼
                render(0.060) { t ->
                    sin(2 * PI * 850 * t) * exp(-t * 90) * 0.55 +
                        sin(2 * PI * 1700 * t) * exp(-t * 220) * 0.45
                },
                // 牛铃：双频慢衰减
                render(0.220) { t ->
                    (sin(2 * PI * 560 * t) + 0.7 * sin(2 * PI * 845 * t)) * exp(-t * 16) * 0.38
                }
            )
            builtIns = arr
            return arr
        }
    }

    private fun clickNoise(t: Double): Double =
        if (t < 0.002) ((t * 19993.0 % 1.0) * 2 - 1) * 0.3 * (1 - t / 0.002) else 0.0

    private fun render(seconds: Double, f: (Double) -> Double): ShortArray {
        val n = (seconds * RATE).roundToInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = f(i / RATE.toDouble()).coerceIn(-1.0, 1.0)
            out[i] = (v * 32700.0).roundToInt().toShort()
        }
        return out
    }

    /** 解码任意音频 URI → 单声道 48kHz PCM16，最长 3 秒；失败返回 null */
    suspend fun decodeCustom(context: Context, uri: Uri): ShortArray? =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("audio/")) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) return@withContext null
                extractor.selectTrack(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val capSrcFrames = (srcRate * (MAX_CUSTOM_SECONDS + 1.0)).toInt()
                val mono = ShortArray(capSrcFrames)
                var frames = 0

                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputEos = false
                    var outputEos = false
                    var loops = 0
                    while (!outputEos && frames < capSrcFrames && ++loops < 10_000) {
                        if (!inputEos) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val ib = codec.getInputBuffer(inIdx)!!
                                val size = extractor.readSampleData(ib, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                        if (outIdx >= 0) {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            ob.order(ByteOrder.nativeOrder())
                            val sb = ob.asShortBuffer()
                            val n = sb.remaining() / srcCh
                            if (n > 0) {
                                val tmp = ShortArray(n * srcCh)
                                sb.get(tmp)
                                var p = 0
                                for (i in 0 until n) {
                                    var acc = 0
                                    for (c in 0 until srcCh) acc += tmp[p++].toInt()
                                    if (frames < capSrcFrames) mono[frames++] = (acc / srcCh).toShort()
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = codec.outputFormat
                            srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                }
                if (frames == 0) return@withContext null
                resample(mono, frames, srcRate, RATE)
            } catch (e: Exception) {
                Log.w(TAG, "自定义音频解码失败", e)
                null
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        }

    /** 线性插值重采样并截取前 MAX_CUSTOM_SECONDS 秒 */
    private fun resample(src: ShortArray, frames: Int, srcRate: Int, dstRate: Int): ShortArray {
        val outN = min(
            frames.toLong() * dstRate / srcRate,
            (dstRate * MAX_CUSTOM_SECONDS).toLong()
        ).toInt()
        val out = ShortArray(outN)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until outN) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = pos - i0
            val s0 = src[i0.coerceAtMost(frames - 1)].toInt()
            val s1 = src[(i0 + 1).coerceAtMost(frames - 1)].toInt()
            out[i] = (s0 + ((s1 - s0) * frac).toInt()).coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
