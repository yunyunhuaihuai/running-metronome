package com.metronome.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.metronome.app.core.BlockRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 音色来源：内置音色为启动时（非音频线程）合成的 PCM；自定义音频经
 * MediaCodec 解码、混缩为单声道并线性重采样到 48kHz，以稳定资源 id
 * 存入 App 私有目录（[AudioAssets]），重启后仍可用。
 *
 * 音质处理（针对"截断/爆音/刺耳/机械感"）：
 *  - 所有内置音色以极短起音（≤1ms）保证辨拍清晰，不靠长淡入；
 *  - 末尾统一 3ms 淡出到零，消除收尾 click；
 *  - 新增 3 种更柔和自然的音色（软木/柔铃/低鼓），保留原 4 种；
 *  - 全部提前合成，音频线程只读不可变数组。
 */
object SoundBank {
    private const val TAG = "SoundBank"
    val BUILT_IN = listOf("咔嗒", "哔声", "木鱼", "牛铃", "软木", "柔铃", "低鼓")
    const val RATE = MetronomeEngine.RATE
    const val MAX_CUSTOM_SECONDS = 3.0
    const val DECODE_TIMEOUT_MS = 10_000L
    // 包括 RIFF 头、元数据与待读取的 PCM；拒绝伪造的巨型 chunk 和无限零长度 chunk 链。
    private const val MAX_WAV_SCAN_BYTES = 64L * 1024 * 1024
    private const val MAX_WAV_CHUNKS = 256

    @Volatile private var builtIns: Array<ShortArray>? = null
    @Volatile var leftPcm: ShortArray? = null
    @Volatile var rightPcm: ShortArray? = null

    /** 在非音频线程提前调用（MetronomeEngine.init） */
    fun ensureBuiltIns() {
        if (builtIns != null) return
        synchronized(this) {
            if (builtIns != null) return
            builtIns = arrayOf(
                // 咔嗒：高频阻尼正弦 + 1ms 噪声头（噪声也做 0.5ms 起坡，避免零点跳变）
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
                },
                // 软木：柔和木质敲击，起音明确、衰减快、高频少
                render(0.070) { t ->
                    val attack = min(1.0, t / 0.0008)
                    (sin(2 * PI * 1050 * t) * exp(-t * 140) * 0.52 +
                        sin(2 * PI * 2100 * t) * exp(-t * 260) * 0.18) * attack
                },
                // 柔铃：基频+泛音，1ms 起音、慢衰减，比牛铃轻且更"自然"
                render(0.240) { t ->
                    val attack = min(1.0, t / 0.001)
                    (sin(2 * PI * 1320 * t) * exp(-t * 26) * 0.34 +
                        sin(2 * PI * 2640 * t) * exp(-t * 75) * 0.10) * attack
                },
                // 低鼓：短促低频"咚"，起音清晰，久听不刺耳
                render(0.130) { t ->
                    val attack = min(1.0, t / 0.0012)
                    val pitch = 190.0 - 60.0 * min(1.0, t / 0.06)
                    sin(2 * PI * pitch * t) * exp(-t * 34) * 0.62 * attack
                },
            )
        }
    }

    private fun clickNoise(t: Double): Double =
        if (t < 0.002) {
            val ramp = min(1.0, t / 0.0005)
            ((t * 19993.0 % 1.0) * 2 - 1) * 0.3 * (1 - t / 0.002) * ramp
        } else 0.0

    fun builtin(index: Int): ShortArray {
        ensureBuiltIns()
        val arr = builtIns!!
        return arr[index.coerceIn(0, arr.size - 1)]
    }

    /** 报警短提示音（叠加模式）：短促双频"滴"，不替代节拍 */
    fun promptPcm(): ShortArray = render(0.090) { t ->
        val env = min(1.0, t / 0.003) * min(1.0, (0.090 - t) / 0.020).coerceIn(0.0, 1.0)
        (sin(2 * PI * 1250 * t) * 0.6 + sin(2 * PI * 2500 * t) * 0.2) * env
    }

    /** 结束提示音：三音上行琶音，一次 */
    fun finishPcm(): ShortArray = render(0.75) { t ->
        val env: Double
        val freq: Double
        when {
            t < 0.22 -> { freq = 880.0; env = fadeEnv(t, 0.004, 0.20, 0.22) }
            t < 0.46 -> { freq = 1100.0; env = fadeEnv(t - 0.24, 0.004, 0.20, 0.22) }
            else -> { freq = 1320.0; env = fadeEnv(t - 0.48, 0.004, 0.24, 0.27) }
        }
        if (env <= 0.0) 0.0 else sin(2 * PI * freq * t) * env * 0.5
    }

    private fun fadeEnv(t: Double, attack: Double, holdEnd: Double, total: Double): Double =
        if (t < 0) 0.0
        else min(1.0, t / attack) * min(1.0, (total - t) / (total - holdEnd)).coerceIn(0.0, 1.0)

    /** 按当前设置解析某只脚的节拍 PCM；自定义未就绪时回退内置（状态在 UI 明示） */
    fun resolveBeat(foot: BlockRenderer.Foot): ShortArray {
        val useCustom = if (foot == BlockRenderer.Foot.LEFT) MetronomeEngine.leftUseCustom.value
        else MetronomeEngine.rightUseCustom.value
        if (useCustom) {
            val pcm = if (foot == BlockRenderer.Foot.LEFT) leftPcm else rightPcm
            if (pcm != null && pcm.isNotEmpty()) return pcm
        }
        val idx = if (foot == BlockRenderer.Foot.LEFT) MetronomeEngine.leftTimbre.value
        else MetronomeEngine.rightTimbre.value
        return builtin(idx)
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun render(seconds: Double, f: (Double) -> Double): ShortArray {
        val n = (seconds * RATE).roundToInt()
        val out = ShortArray(n)
        val tailStart = n - (0.003 * RATE).roundToInt()
        for (i in 0 until n) {
            val t = i / RATE.toDouble()
            var v = f(t).coerceIn(-1.0, 1.0)
            // 末尾 3ms 线性淡出到零（最后样本严格为 0）：消除音色收尾的非零跳变 click
            if (i >= tailStart) {
                v *= (n - 1 - i).toDouble() / (n - 1 - tailStart).coerceAtLeast(1).toDouble()
            }
            out[i] = (v * 32700.0).roundToInt().toShort()
        }
        out[n - 1] = 0
        return out
    }

    // ------------------------------------------------------------ 自定义音频解码

    /**
     * 解码任意音频 URI → 单声道 48kHz PCM16，最长 3 秒。
     * 主路径 MediaExtractor/MediaCodec（系统支持的全部容器与编码）；
     * 失败时回退手写 PCM WAV 解析（部分系统/模拟器缺 WAV 提取器）。
     * 失败返回 null（不抛出）。超时/取消由调用方通过协程取消 + 本函数的
     * 帧数上限与循环上限保证不无限阻塞。
     */
    suspend fun decodeCustom(context: Context, uri: Uri): ShortArray? =
        withTimeout(DECODE_TIMEOUT_MS) { decodeCustomWithinTimeout(context, uri) }

    private suspend fun decodeCustomWithinTimeout(context: Context, uri: Uri): ShortArray? {
        val viaExtractor = decodeViaExtractor(context, uri)
        if (viaExtractor != null) return viaExtractor
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        Log.i(TAG, "extractor 路径失败/不支持，尝试 WAV 直接解析")
        val wav = try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) Log.w(TAG, "WAV 文件流无法打开")
            stream?.use { input ->
                parseWav(
                    input,
                    onReject = { reason -> Log.w(TAG, "WAV 解析拒绝：$reason") },
                    checkActive = { coroutine.ensureActive() },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "WAV 直接解析失败", e)
            null
        }
        if (wav == null) {
            Log.w(TAG, "WAV 直接解析未得到数据（非 PCM WAV 或流不可读）")
            return null
        }
        val (mono, srcRate) = wav
        if (mono.isEmpty() || srcRate <= 0) return null
        Log.i(TAG, "WAV 解析成功：rate=$srcRate frames=${mono.size}")
        return resample(mono, mono.size, srcRate, RATE)
    }

    /**
     * 纯 WAV 解析（无 Android 依赖，可 JVM 单元测试）：
     * 解析 RIFF/PCM WAV 流（8/16/24/32-bit 整型与 IEEE float，多声道混缩单声道），
     * 返回 (单声道 PCM, 采样率)；非 PCM WAV / 截断流返回 null。
     */
    fun parseWav(
        input: java.io.InputStream,
        onReject: (String) -> Unit = {},
        checkActive: () -> Unit = {},
    ): Pair<ShortArray, Int>? {
        fun reject(reason: String): Pair<ShortArray, Int>? {
            onReject(reason)
            return null
        }
        fun readFully(buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                checkActive()
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                if (n == 0) {
                    val one = input.read()
                    if (one < 0) return false
                    buf[off++] = one.toByte()
                } else off += n
            }
            return true
        }
        fun skipFully(n: Long): Boolean {
            var left = n
            val skip = ByteArray(4096)
            while (left > 0) {
                checkActive()
                val r = input.read(skip, 0, minOf(left, skip.size.toLong()).toInt())
                if (r < 0) return false
                if (r == 0) {
                    if (input.read() < 0) return false
                    left--
                } else left -= r
            }
            return true
        }
        val riff = ByteArray(12)
        if (!readFully(riff)) return reject("RIFF 头不足 12 字节")
        if (!(riff[0] == 'R'.code.toByte() && riff[1] == 'I'.code.toByte() &&
                riff[2] == 'F'.code.toByte() && riff[3] == 'F'.code.toByte() &&
                riff[8] == 'W'.code.toByte() && riff[9] == 'A'.code.toByte() &&
                riff[10] == 'V'.code.toByte() && riff[11] == 'E'.code.toByte())
        ) return reject("缺少 RIFF/WAVE 标识")

        var channels = 0
        var rate = 0
        var bits = 0
        var floatPcm = false
        var data: ByteArray? = null
        val head = ByteArray(8)
        var scanned = 12L
        var chunks = 0
        while (readFully(head)) {
            if (++chunks > MAX_WAV_CHUNKS) return reject("WAV chunk 数超过 $MAX_WAV_CHUNKS")
            scanned += head.size
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val size = ((head[7].toLong() and 0xFF) shl 24) or
                ((head[6].toLong() and 0xFF) shl 16) or
                ((head[5].toLong() and 0xFF) shl 8) or
                (head[4].toLong() and 0xFF)
            val paddedSize = size + (size and 1L)
            if (id != "data" && paddedSize > MAX_WAV_SCAN_BYTES - scanned)
                return reject("$id 块超过 WAV 扫描上限 $MAX_WAV_SCAN_BYTES 字节")
            when (id) {
                "fmt " -> {
                    if (size < 16) return reject("fmt 块长度 $size 小于 16")
                    val fmt = ByteArray(minOf(size, 40L).toInt())
                    if (!readFully(fmt)) return reject("fmt 块数据截断")
                    if (size > fmt.size && !skipFully(size - fmt.size)) return reject("fmt 扩展数据截断")
                    val audioFormat = ((fmt[1].toInt() and 0xFF) shl 8) or (fmt[0].toInt() and 0xFF)
                    channels = ((fmt[3].toInt() and 0xFF) shl 8) or (fmt[2].toInt() and 0xFF)
                    rate = ((fmt[7].toInt() and 0xFF) shl 24) or ((fmt[6].toInt() and 0xFF) shl 16) or
                        ((fmt[5].toInt() and 0xFF) shl 8) or (fmt[4].toInt() and 0xFF)
                    bits = ((fmt[15].toInt() and 0xFF) shl 8) or (fmt[14].toInt() and 0xFF)
                    // 只按声明的 PCM 编码解读字节，避免将 A-law 等压缩数据误播成噪声。
                    if (audioFormat != 1 && audioFormat != 3)
                        return reject("fmt 音频格式 $audioFormat 非 PCM/IEEE float")
                    if (channels !in 1..8 || rate !in 1..384_000)
                        return reject("fmt 声道数/采样率非法：channels=$channels rate=$rate")
                    if (audioFormat == 3 && bits != 32)
                        return reject("fmt float 位深非 32：bits=$bits")
                    if (audioFormat == 1 && bits !in listOf(8, 16, 24, 32))
                        return reject("fmt PCM 位深不支持：bits=$bits")
                    val blockAlign = ((fmt[13].toInt() and 0xFF) shl 8) or (fmt[12].toInt() and 0xFF)
                    if (blockAlign != channels * (bits / 8))
                        return reject("fmt blockAlign=$blockAlign 与 channels=$channels bits=$bits 不符")
                    floatPcm = audioFormat == 3   // 1=PCM 整型，3=IEEE float
                }
                "data" -> {
                    if (channels == 0 || rate == 0)
                        return reject("data 块出现在有效 fmt 块之前")
                    val cap = (rate.coerceAtLeast(1).toLong() * channels.coerceAtLeast(1) *
                        (bits / 8).coerceAtLeast(1) * (MAX_CUSTOM_SECONDS + 1.0)).toLong()
                    val toRead = minOf(size, cap).toInt()
                    if (toRead > MAX_WAV_SCAN_BYTES - scanned)
                        return reject("data 块超过 WAV 扫描上限 $MAX_WAV_SCAN_BYTES 字节")
                    val buf = ByteArray(toRead)
                    if (!readFully(buf)) return reject("data 块数据截断")
                    data = buf
                    scanned += toRead
                }
                else -> if (!skipFully(size)) return reject("$id 块数据截断")
            }
            // RIFF chunk 的有效数据长度为奇数时，末尾有一个不计入 size 的填充字节。
            if (data == null && (size and 1L) != 0L && !skipFully(1))
                return reject("$id 块末尾填充字节缺失")
            if (data == null) scanned += paddedSize
            if (data != null && channels > 0 && rate > 0) break
        }
        val raw = data ?: return reject("未找到 data 块")
        if (raw.isEmpty() || channels <= 0 || rate <= 0) return reject("data 为空或 fmt 无效")
        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return reject("位深非法：bits=$bits")
        val frames = raw.size / (bytesPerSample * channels)
        val mono = ShortArray(frames)
        var p = 0
        for (i in 0 until frames) {
            if ((i and 4095) == 0) checkActive()
            var acc = 0L
            for (c in 0 until channels) {
                val v: Double = when (bits) {
                    16 -> {
                        val lo = raw[p].toInt() and 0xFF
                        val hi = raw[p + 1].toInt()
                        ((hi shl 8) or lo).toShort().toInt() / 32768.0
                    }
                    8 -> ((raw[p].toInt() and 0xFF) - 128) / 128.0
                    24 -> {
                        val b0 = raw[p].toInt() and 0xFF
                        val b1 = raw[p + 1].toInt() and 0xFF
                        val b2 = raw[p + 2].toInt() and 0xFF
                        val s = (b2 shl 16) or (b1 shl 8) or b0
                        ((s shl 8) shr 8) / 8388608.0
                    }
                    32 -> {
                        val ib = ((raw[p + 3].toInt() and 0xFF) shl 24) or
                            ((raw[p + 2].toInt() and 0xFF) shl 16) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or (raw[p].toInt() and 0xFF)
                        if (floatPcm) java.lang.Float.intBitsToFloat(ib).toDouble()
                        else ib / 2147483648.0
                    }
                    else -> return reject("采样位深不支持：bits=$bits")
                }
                acc += Math.round(v.coerceIn(-1.0, 1.0) * 32768.0)
                p += bytesPerSample
            }
            mono[i] = (acc / channels).toInt().coerceIn(-32768, 32767).toShort()
        }
        return mono to rate
    }

    /** MediaExtractor/MediaCodec 主解码路径 */
    private suspend fun decodeViaExtractor(context: Context, uri: Uri): ShortArray? =
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
                // 部分解码器输出 float PCM（KEY_PCM_ENCODING 标识），不区分会读出噪声。
                // getOutputBuffer() 的 position/limit 已限定有效输出范围（API 合同），直接读取。
                var pcmEncoding =
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
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
                        currentCoroutineContext().ensureActive()
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
                            val n: Int
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = ob.asFloatBuffer()
                                n = fb.remaining() / srcCh
                                if (n > 0) {
                                    val tmp = FloatArray(n * srcCh)
                                    fb.get(tmp)
                                    var p = 0
                                    for (i in 0 until n) {
                                        var acc = 0f
                                        var bad = false
                                        for (c in 0 until srcCh) {
                                            val v = tmp[p++]
                                            if (!v.isFinite()) bad = true
                                            acc += v
                                        }
                                        if (bad) continue
                                        val v = (acc / srcCh).coerceIn(-1f, 1f)
                                        if (frames < capSrcFrames)
                                            mono[frames++] = (v * 32767f).toInt().toShort()
                                    }
                                }
                            } else {
                                val sb = ob.asShortBuffer()
                                n = sb.remaining() / srcCh
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
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = codec.outputFormat
                            srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                }
                if (frames == 0) return@withContext null
                resample(mono, frames, srcRate, RATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "自定义音频解码失败", e)
                null
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        }

    /** 线性插值重采样并截取前 MAX_CUSTOM_SECONDS 秒 */
    private fun resample(src: ShortArray, frames: Int, srcRate: Int, dstRate: Int): ShortArray {
        if (frames <= 0 || srcRate <= 0) return ShortArray(0)
        val outN = min(
            frames.toLong() * dstRate / srcRate,
            (dstRate * MAX_CUSTOM_SECONDS).toLong()
        ).toInt()
        if (outN <= 0) return ShortArray(0)
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

/**
 * 自定义音频资源的私有目录存储：id → filesDir/audio/<id>.pcm。
 * 显示名存 SharedPreferences（由 MetronomeEngine 管理），id 是稳定标识，
 * 预设/设置只保存 id，不保存外部文件路径或显示名。
 */
object AudioAssets {
    private const val DIR = "audio"

    fun pcmFile(context: Context, id: String) =
        java.io.File(java.io.File(context.filesDir, DIR), "$id.pcm")

    fun savePcm(context: Context, id: String, pcm: ShortArray): Boolean = try {
        val f = pcmFile(context, id)
        f.parentFile?.mkdirs()
        java.io.DataOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(f))
        ).use { out ->
            out.writeInt(pcm.size)
            for (s in pcm) out.writeShort(s.toInt())
        }
        true
    } catch (e: Exception) {
        Log.w("AudioAssets", "savePcm failed", e)
        false
    }

    fun loadPcm(context: Context, id: String): ShortArray? = try {
        val f = pcmFile(context, id)
        if (!f.exists()) null else java.io.DataInputStream(
            java.io.BufferedInputStream(java.io.FileInputStream(f))
        ).use { input ->
            val n = input.readInt()
            if (n <= 0 || n > (SoundBank.MAX_CUSTOM_SECONDS * SoundBank.RATE).toInt() + 4800) return null
            ShortArray(n) { input.readShort() }
        }
    } catch (e: Exception) {
        Log.w("AudioAssets", "loadPcm failed", e)
        null
    }

    fun delete(context: Context, id: String) {
        try { pcmFile(context, id).delete() } catch (_: Exception) {}
    }
}
