package com.metronome.app

import com.metronome.app.core.PcmMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混音器回归：
 *  - 宽位累加与叠加顺序无关（旧版逐声部 Short 饱和的顺序失真已修复）；
 *  - 跨 block 连续播放；offset = blockFrames 顺延下一块；
 *  - voice 上限丢最旧；组淡出（报警提示取消）。
 */
class PcmMixerTest {

    private val frames = 480

    private fun pcmOf(vararg samples: Short): ShortArray = samples

    @Test
    fun `sum order does not change result`() {
        // 30000 + 30000 - 30000：旧版逐次写回得 2767；宽位累加正确得 60000（限幅前）
        val a = pcmOf(30000, 0)
        val b = pcmOf(0, 30000)
        val c = pcmOf(30000, 30000)
        val accL = IntArray(frames)

        val m1 = PcmMixer(frames)
        m1.addVoice(a); m1.addVoice(b); m1.addVoice(c)
        val r1 = IntArray(frames)
        m1.mixInto(r1, null)

        val m2 = PcmMixer(frames)
        m2.addVoice(c); m2.addVoice(a); m2.addVoice(b)
        val r2 = IntArray(frames)
        m2.mixInto(r2, null)

        assertTrue(r1.contentEquals(r2))
        assertEquals(60000, r1[0])
        assertEquals(60000, r1[1])
    }

    @Test
    fun `voice longer than block continues into next blocks`() {
        val m = PcmMixer(frames)
        val pcm = ShortArray(frames * 2 + 10) { i -> (i % 7 * 100).toShort() }
        m.addVoice(pcm, 0)
        val acc1 = IntArray(frames)
        m.mixInto(acc1, null)
        assertTrue(acc1[479] != 0)
        val acc2 = IntArray(frames)
        m.mixInto(acc2, null)
        assertEquals("第二块继续（不截断）", (480 % 7 * 100), acc2[0])
        val acc3 = IntArray(frames)
        m.mixInto(acc3, null)
        assertEquals("第三块播完剩余 10 帧", (960 % 7 * 100), acc3[0])
        assertEquals(969 % 7 * 100, acc3[9])
        assertEquals("播完即移除", 0, m.activeVoices)
        val acc4 = IntArray(frames)
        m.mixInto(acc4, null)
        assertEquals(0, acc4[0])
    }

    @Test
    fun `offset equal to block frames defers to next block`() {
        val m = PcmMixer(frames)
        m.addVoice(pcmOf(123), frames)
        val acc = IntArray(frames)
        m.mixInto(acc, null)
        assertEquals(0, acc[0])
        assertEquals("仍在队列", 1, m.activeVoices)
        val acc2 = IntArray(frames)
        m.mixInto(acc2, null)
        assertEquals(123, acc2[0])
    }

    @Test
    fun `stereo routing left right and both`() {
        val m = PcmMixer(frames)
        m.addVoice(pcmOf(1000), 0, PcmMixer.Group.BEAT, PcmMixer.Target.BOTH)
        val l = IntArray(frames); val r = IntArray(frames)
        m.mixInto(l, r)
        assertEquals(1000, l[0])
        assertEquals(1000, r[0])

        val m2 = PcmMixer(frames)
        m2.addVoice(pcmOf(1000), 0, PcmMixer.Group.BEAT, PcmMixer.Target.LEFT)
        m2.addVoice(pcmOf(2000), 0, PcmMixer.Group.BEAT, PcmMixer.Target.RIGHT)
        val l2 = IntArray(frames); val r2 = IntArray(frames)
        m2.mixInto(l2, r2)
        assertEquals("左声部只进左声道", 1000, l2[0])
        assertEquals("右声部只进右声道", 2000, r2[0])
    }

    @Test
    fun `max voices drops oldest`() {
        val m = PcmMixer(frames, maxVoices = 3)
        repeat(5) { v -> m.addVoice(ShortArray(frames * 2) { v.toShort() }, 0) }
        // 保留最后 3 个（值 2/3/4），均未播完
        val acc = IntArray(frames)
        m.mixInto(acc, null)
        assertEquals(9, acc[0])
        assertEquals("未播完的 3 个 voice 仍在", 3, m.activeVoices)
    }

    @Test
    fun `fadeOutGroup removes group voices with decay`() {
        val m = PcmMixer(frames)
        m.addVoice(ShortArray(frames) { 1000 }, 0, PcmMixer.Group.PROMPT)
        m.addVoice(ShortArray(frames * 2) { 1000 }, 0, PcmMixer.Group.BEAT)
        m.fadeOutGroup(PcmMixer.Group.PROMPT, 100)
        val acc1 = IntArray(frames)
        m.mixInto(acc1, null)
        // 提示音约 100 帧内衰减到 0，节拍不受影响
        assertEquals(2000, acc1[0])
        assertTrue("提示音在块内应衰减", acc1[50] < 2000)
        assertEquals("块尾只剩节拍声部", 1000, acc1[frames - 1])
        assertEquals("淡出后提示声部移除，节拍仍在", 1, m.activeVoices)
    }

    @Test
    fun `clear removes all voices`() {
        val m = PcmMixer(frames)
        m.addVoice(ShortArray(frames) { 500 }, 0)
        m.clear()
        assertEquals(0, m.activeVoices)
        val acc = IntArray(frames)
        m.mixInto(acc, null)
        assertFalse(acc.any { it != 0 })
    }
}
