package com.metronome.app

/**
 * 轻量跨 block PCM 混音器（仅音频时钟线程访问，无锁）。
 *
 * 每个 beat 触发时把整段 PCM 作为 voice 加入（可带 block 内起始偏移）；
 * 每生成一个输出 block 时调用 [mixInto]，所有未播完的 voice 继续混入，
 * 播完即移除。因此长于一个 block（480 帧 / 10ms @48kHz）的音色
 * （哔声 70ms、牛铃 220ms、自定义音频最长 3s）能跨 block 连续播放，
 * 不再在 block 边界被截断，相邻节拍的声音也能自然重叠。
 *
 * 多 voice 叠加使用 Int 累加并饱和钳制到 PCM16，避免 Short 溢出回绕。
 * voice 数量上限 [maxVoices]，超出时丢弃最旧的 voice，
 * 防止异常长音频在高 BPM 下无界堆积。
 */
class PcmMixer(private val blockFrames: Int, private val maxVoices: Int = DEFAULT_MAX_VOICES) {

    private class Voice(val pcm: ShortArray, var pos: Int, var delay: Int)

    private val voices = ArrayDeque<Voice>()

    /** 当前未播完的 voice 数（测试与诊断用） */
    val activeVoices: Int get() = voices.size

    /**
     * 加入一段新声音。startOffset 为它在本 block 内的起始帧（0..blockFrames）；
     * 等于 blockFrames 时表示从下一 block 开始播放。
     */
    fun addVoice(pcm: ShortArray, startOffset: Int = 0) {
        require(startOffset in 0..blockFrames) { "startOffset out of block: $startOffset" }
        if (voices.size >= maxVoices) voices.removeFirst()
        voices.addLast(Voice(pcm, 0, startOffset))
    }

    fun clear() = voices.clear()

    /** 把所有 active voice 原地混入 block；本 block 播完的 voice 被移除 */
    fun mixInto(block: ShortArray) {
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
            while (dst < blockFrames && src < pcm.size) {
                val mixed = block[dst] + pcm[src].toInt() // Int 累加，防 Short 溢出
                block[dst] = mixed.coerceIn(-32768, 32767).toShort()
                dst++
                src++
            }
            v.pos = src
            if (src >= pcm.size) it.remove()
        }
    }

    companion object {
        /**
         * 上限取值：自定义音频最长 3s，BPM 200 时约 3.3 拍/s，
         * 满重叠约 10 个 voice；12 留少量余量，超出丢最旧（新节拍优先可听）。
         */
        const val DEFAULT_MAX_VOICES = 12
    }
}
