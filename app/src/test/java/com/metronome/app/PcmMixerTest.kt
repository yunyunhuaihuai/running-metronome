package com.metronome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证跨 block PCM 混音器的核心行为：
 * 跨 block 连续播放、block 中间起始、重叠混音、饱和钳制、及时移除、上限保护。
 */
class PcmMixerTest {

    private val N = 480 // 与服务一致的 block 大小（10ms @48kHz）

    @Test
    fun `pcm longer than one block continues across blocks`() {
        val mixer = PcmMixer(N)
        val pcm = ShortArray(1000) { (it % 251).toShort() } // 可辨识波形
        mixer.addVoice(pcm, 0)

        val b1 = ShortArray(N).also { mixer.mixInto(it) }
        val b2 = ShortArray(N).also { mixer.mixInto(it) }
        val b3 = ShortArray(N).also { mixer.mixInto(it) }

        // 第一个 block 完整包含 pcm[0..480)，绝不能被截断
        assertEquals(pcm.sliceArray(0 until N).toList(), b1.toList())
        // 第二个 block 继续播放剩余部分
        assertEquals(pcm.sliceArray(N until 2 * N).toList(), b2.toList())
        // 尾部 40 帧播完后移除，其后为静音
        assertEquals(pcm.sliceArray(2 * N until 1000).toList(), b3.sliceArray(0 until 40).toList())
        assertTrue(b3.sliceArray(40 until N).all { it == 0.toShort() })
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun `pcm starting mid-block continues into next block`() {
        val mixer = PcmMixer(N)
        val pcm = ShortArray(1000) { ((it * 7) % 301 - 150).toShort() }
        val offset = 300
        mixer.addVoice(pcm, offset)

        val b1 = ShortArray(N).also { mixer.mixInto(it) }
        for (i in 0 until offset) assertEquals(0, b1[i].toInt())
        for (i in offset until N) assertEquals(pcm[i - offset].toInt(), b1[i].toInt())

        // 后续 block 从 pcm[offset 之后] 无缝继续
        val b2 = ShortArray(N).also { mixer.mixInto(it) }
        for (i in 0 until N) assertEquals(pcm[N - offset + i].toInt(), b2[i].toInt())

        // 1000 - 180 - 480 = 340 帧尾部
        val b3 = ShortArray(N).also { mixer.mixInto(it) }
        for (i in 0 until 340) assertEquals(pcm[N - offset + N + i].toInt(), b3[i].toInt())
        assertTrue(b3.sliceArray(340 until N).all { it == 0.toShort() })
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun `overlapping voices are summed`() {
        val mixer = PcmMixer(N)
        val a = ShortArray(N) { if (it < 100) 1000 else 0 }
        val b = ShortArray(N) { if (it < 100) 2000 else 0 }
        mixer.addVoice(a, 0)
        mixer.addVoice(b, 0)

        val block = ShortArray(N).also { mixer.mixInto(it) }
        for (i in 0 until 100) assertEquals(3000, block[i].toInt())
        for (i in 100 until N) assertEquals(0, block[i].toInt())
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun `overflow saturates instead of wrapping`() {
        val mixer = PcmMixer(N)
        val loud = ShortArray(N) { 30000 }
        val loud2 = ShortArray(N) { 30000 }
        mixer.addVoice(loud, 0)
        mixer.addVoice(loud2, 0)
        val block = ShortArray(N).also { mixer.mixInto(it) }
        // 60000 饱和到 Short.MAX_VALUE，而不是回绕成负数
        assertTrue(block.all { it == 32767.toShort() })

        val mixer2 = PcmMixer(N)
        mixer2.addVoice(ShortArray(N) { -30000 }, 0)
        mixer2.addVoice(ShortArray(N) { -30000 }, 0)
        val block2 = ShortArray(N).also { mixer2.mixInto(it) }
        assertTrue(block2.all { it == (-32768).toShort() })
    }

    @Test
    fun `voice is removed after finishing and later blocks stay silent`() {
        val mixer = PcmMixer(N)
        mixer.addVoice(ShortArray(N) { 12345 }, 0)
        val b1 = ShortArray(N).also { mixer.mixInto(it) }
        assertEquals(0, mixer.activeVoices)
        assertTrue(b1.all { it == 12345.toShort() })
        val b2 = ShortArray(N).also { mixer.mixInto(it) }
        assertTrue(b2.all { it == 0.toShort() })
    }

    @Test
    fun `oldest voice is dropped when max voices exceeded`() {
        val mixer = PcmMixer(N, maxVoices = 4)
        mixer.addVoice(ShortArray(N) { 1 }, 0)   // 会被挤掉
        mixer.addVoice(ShortArray(N) { 10 }, 0)
        mixer.addVoice(ShortArray(N) { 100 }, 0)
        mixer.addVoice(ShortArray(N) { 1000 }, 0)
        mixer.addVoice(ShortArray(N) { 10000 }, 0)
        assertEquals(4, mixer.activeVoices)
        val block = ShortArray(N).also { mixer.mixInto(it) }
        // 10+100+1000+10000 = 11110；若没丢弃最旧则为 11111
        assertEquals(11110, block[0].toInt())
    }

    @Test
    fun `three second custom sample tail plays fully across many blocks`() {
        val mixer = PcmMixer(N)
        val totalFrames = 3 * 48000 // 自定义音频上限 3 秒
        val pcm = ShortArray(totalFrames) { ((it * 13) % 4001 - 2000).toShort() }
        val lead = 100 // 模拟 block 中间触发
        mixer.addVoice(pcm, lead)

        // 逐 block 拼接输出，验证整段（含最后不足一个 block 的尾部）完整
        val out = ArrayList<Short>(totalFrames + lead + N)
        var voices: Int
        do {
            val block = ShortArray(N).also { mixer.mixInto(it) }
            out.addAll(block.toList())
            voices = mixer.activeVoices
        } while (voices > 0)

        assertTrue("输出应至少包含全部样本（最后不足一个 block 的收尾块会被整体追加）", out.size >= totalFrames + lead)
        for (i in 0 until lead) assertEquals(0, out[i].toInt())
        for (i in 0 until totalFrames) {
            if (out[lead + i] != pcm[i]) {
                throw AssertionError("tail mismatch at frame $i: ${out[lead + i]} != ${pcm[i]}")
            }
        }
    }

    @Test
    fun `voice with offset equal to block frames is deferred to next block`() {
        val mixer = PcmMixer(N)
        val pcm = ShortArray(10) { 500 }
        mixer.addVoice(pcm, N) // 恰好落在 block 末尾 → 下一 block 开头播放
        val b1 = ShortArray(N).also { mixer.mixInto(it) }
        assertTrue(b1.all { it == 0.toShort() })
        assertEquals(1, mixer.activeVoices)
        val b2 = ShortArray(N).also { mixer.mixInto(it) }
        assertEquals(500, b2[0].toInt())
        assertTrue(b2.sliceArray(10 until N).all { it == 0.toShort() })
    }
}
