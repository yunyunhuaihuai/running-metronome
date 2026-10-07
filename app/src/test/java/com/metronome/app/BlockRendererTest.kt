package com.metronome.app

import com.metronome.app.core.BlockRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块渲染器回归（离线验证正式渲染路径）：
 *  - 长音色跨 block 尾音完整；非块边界起音；
 *  - 左右脚交替顺序确定、居中/交替声道正确；一拍 = 一步（拍速 = SPM）；
 *  - 主音量缩放；门控平滑关闭；报警短提示取消不再补发；
 *  - 改 SPM 重锚：下一拍 ≤ 一个新间隔，不补发不遗漏。
 */
class BlockRendererTest {

    private val frames = 480
    private val rate = 48000

    private class FakeSounds : BlockRenderer.SoundResolver {
        val beatCount = mutableMapOf<BlockRenderer.Foot, Int>()
        var promptPlayed = 0

        override fun beatPcm(foot: BlockRenderer.Foot): ShortArray {
            beatCount[foot] = (beatCount[foot] ?: 0) + 1
            // 2000 帧的"长音色"（≈42ms > 10ms block），幅度 10000
            return ShortArray(2000) { 10000 }
        }

        override fun promptPcm(): ShortArray {
            promptPlayed++
            return ShortArray(960) { 20000 }
        }
    }

    private fun renderer(sounds: FakeSounds = FakeSounds()) =
        BlockRenderer(frames, rate, stereo = true, sounds = sounds)

    private fun snap(
        spm: Int = 120,
        soundEnabled: Boolean = true,
        gateOpen: Boolean = true,
        volume: Float = 1f,
        channelMode: BlockRenderer.ChannelMode = BlockRenderer.ChannelMode.CENTER,
        alarmTone: Boolean = false,
        prompt: Boolean = false,
        promptInterval: Long = 2 * rate.toLong(),
        resetFoot: Boolean = false,
        resetScheduler: Boolean = false,
    ) = BlockRenderer.Snapshot(
        targetSpm = spm, soundEnabled = soundEnabled, gateOpen = gateOpen, volume = volume,
        channelMode = channelMode, alarmToneActive = alarmTone, promptActive = prompt,
        promptIntervalFrames = promptInterval, resetFootPhase = resetFoot,
        resetScheduler = resetScheduler,
    )

    /** 连续渲染 n 个 block，返回峰值与拍的聚合 */
    private class Run {
        var peakL = 0
        var peakR = 0
        var minL = 0
        val beats = mutableListOf<BlockRenderer.ScheduledBeat>()
    }

    private fun runBlocks(
        r: BlockRenderer,
        n: Int,
        snap: BlockRenderer.Snapshot,
        startFrame: Long = 0,
    ): Run {
        val out = ShortArray(frames * 2)
        val run = Run()
        var base = startFrame
        repeat(n) {
            val res = r.renderBlock(base, snap, out)
            run.beats += res.beats
            for (i in 0 until frames) {
                run.peakL = maxOf(run.peakL, out[i * 2].toInt())
                run.peakR = maxOf(run.peakR, out[i * 2 + 1].toInt())
                run.minL = minOf(run.minL, out[i * 2].toInt())
            }
            base += frames
        }
        return run
    }

    @Test
    fun `one beat per step - beat rate equals spm`() {
        val r = renderer()
        // 1 秒 = 100 blocks，120 SPM = 2 拍/秒 → 1 秒 2 拍
        val run = runBlocks(r, 100, snap(spm = 120))
        assertEquals("120 SPM 每秒 2 拍", 2, run.beats.size)
        // 改速重锚消耗掉部分区间：110 块（1.1s）内应有 3 拍（16000 帧间隔）
        val run180 = runBlocks(r, 110, snap(spm = 180), startFrame = 48_000)
        assertEquals("180 SPM 1.1 秒 3 拍", 3, run180.beats.size)
    }

    @Test
    fun `beat interval matches spm exactly`() {
        val r = renderer()
        val run = runBlocks(r, 200, snap(spm = 150))
        assertTrue(run.beats.size >= 2)
        val interval = run.beats[1].frame - run.beats[0].frame
        assertEquals(19_200.0, interval.toDouble(), 1.0)   // 60s/150SPM = 0.4s = 19200 帧
    }

    @Test
    fun `long sound tail continues across blocks`() {
        val r = renderer()
        val run = runBlocks(r, 6, snap(spm = 60))
        assertEquals(1, run.beats.size)
        // 音色 2000 帧 > 一块：后续块仍有输出（尾音不被截断）。
        // 门控约 2~3 块才完全打开，峰值阈值放宽。
        assertTrue("跨块尾音应存在（peak=${run.peakL}）", run.peakL >= 7000)
    }

    @Test
    fun `alternating feet deterministic order preserved`() {
        val r = renderer()
        val run = runBlocks(r, 100, snap(spm = 120))
        assertEquals(listOf(BlockRenderer.Foot.LEFT, BlockRenderer.Foot.RIGHT), run.beats.map { it.foot })
        // 新会话（resetFootPhase）重新从左脚开始
        val run2 = runBlocks(r, 100, snap(spm = 120, resetFoot = true), startFrame = 48_000)
        assertEquals(BlockRenderer.Foot.LEFT, run2.beats.first().foot)
    }

    @Test
    fun `center mode feeds both channels - alternate feeds one per foot`() {
        val r = renderer()
        val run = runBlocks(r, 100, snap(spm = 120, channelMode = BlockRenderer.ChannelMode.CENTER))
        assertTrue("居中：两耳都听到", run.peakL > 0 && run.peakR > 0)
        assertEquals(run.peakL, run.peakR)

        val r2 = renderer()
        val run2 = runBlocks(
            r2, 100, snap(spm = 120, channelMode = BlockRenderer.ChannelMode.ALTERNATE_LR)
        )
        assertTrue("交替：左声道有输出", run2.peakL > 0)
        assertTrue("交替：右声道有输出", run2.peakR > 0)
    }

    @Test
    fun `volume scales output`() {
        val r = renderer()
        val full = runBlocks(r, 100, snap(spm = 120, volume = 1f))
        val half = runBlocks(r, 100, snap(spm = 120, volume = 0.5f), startFrame = 48_000)
        assertTrue(full.peakL > 0)
        assertTrue("半音量应约为一半", half.peakL < full.peakL && half.peakL > full.peakL / 3)
    }

    @Test
    fun `gate closed silences output but beats still scheduled`() {
        val r = renderer()
        val run = runBlocks(r, 100, snap(spm = 120, gateOpen = false))
        assertTrue("门控关闭后输出应为 0（平滑收尾后）", run.peakL == 0)
        assertEquals("静音不影响节拍调度（振动/计数）", 2, run.beats.size)
    }

    @Test
    fun `sound disabled schedules beats without sound`() {
        val sounds = FakeSounds()
        val r = BlockRenderer(frames, rate, true, sounds)
        val run = runBlocks(r, 100, snap(spm = 120, soundEnabled = false))
        assertEquals(0, run.peakL)
        assertEquals("不发声但仍有拍", 2, run.beats.size)
        assertEquals("不解析音色", 0, sounds.beatCount.size)
    }

    @Test
    fun `prompt overlay fires once per interval and cancels cleanly`() {
        val sounds = FakeSounds()
        val r = BlockRenderer(frames, rate, true, sounds)
        // 报警持续 2.5s（提示间隔 2s）：应有 2 次提示（0s 与 2s 附近）
        val run = runBlocks(r, 250, snap(spm = 120, prompt = true, promptInterval = 2L * rate))
        assertTrue("短提示应叠加在节拍上", sounds.promptPlayed >= 2)
        assertTrue("节拍继续", run.beats.size == 5)
        // 解除：不再安排新提示，已安排的淡出
        sounds.promptPlayed = 0
        val run2 = runBlocks(r, 150, snap(spm = 120, prompt = false), startFrame = 250L * frames)
        assertEquals("解除后不补发提示", 0, sounds.promptPlayed)
        assertTrue("节拍继续", run2.beats.size == 3)
    }

    @Test
    fun `alarm long tone replaces beat sound`() {
        val sounds = FakeSounds()
        val r = BlockRenderer(frames, rate, true, sounds)
        val run = runBlocks(r, 100, snap(spm = 120, alarmTone = true))
        assertEquals("长音模式不发节拍音色", 0, sounds.beatCount.size)
        assertTrue("长音持续出声", run.peakL > 5000)
        assertEquals("节拍调度仍进行", 2, run.beats.size)
    }

    @Test
    fun `spm change re-anchors next beat within one new interval`() {
        val r = renderer()
        // 120 SPM 稳定 0.5s，随后提到 180
        val run1 = runBlocks(r, 50, snap(spm = 120))
        assertEquals(1, run1.beats.size)
        val switchFrame = 50L * frames
        val run2 = runBlocks(r, 100, snap(spm = 180), startFrame = switchFrame)
        assertTrue(run2.beats.isNotEmpty())
        val first = run2.beats.first().frame - switchFrame
        val newInterval = 60.0 * rate / 180   // 16000 帧
        assertTrue(
            "改速后下一拍应在一个新间隔内出现（${first} 帧 > ${newInterval + 480}）",
            first <= newInterval + 480
        )
        // 区间 [24000, 72000)：拍间隔恰为 16000 → 恰 2 拍（不补发不遗漏）
        assertEquals(2, run2.beats.size)
        assertEquals(16000, run2.beats[1].frame - run2.beats[0].frame)
    }

    @Test
    fun `spm zero produces no new beats`() {
        val r = renderer()
        val run = runBlocks(r, 100, snap(spm = 0))
        assertEquals(0, run.beats.size)
        assertEquals(0, run.peakL)
    }

    @Test
    fun `no int wraparound on overlapping loud sounds`() {
        val sounds = object : BlockRenderer.SoundResolver {
            override fun beatPcm(foot: BlockRenderer.Foot): ShortArray =
                ShortArray(4800) { 30000 }   // 100ms 大音色，高步频下多拍重叠

            override fun promptPcm(): ShortArray = ShortArray(4800) { 30000 }
        }
        val r = BlockRenderer(frames, rate, true, sounds)
        val run = runBlocks(r, 200, snap(spm = 200, prompt = true, promptInterval = rate.toLong()))
        // 多声部叠加必须限幅在合理范围，绝不回绕（回绕会表现为 ±32000 级的翻转）
        assertTrue("峰值应被限幅（≤32700）", run.peakL <= 32700)
        assertTrue("负峰不应回绕（min=${run.minL}）", run.minL >= -32700)
        assertTrue(run.peakL > 0)
    }

    @Test
    fun `one shot plays through official path`() {
        val r = renderer()
        r.queueOneShot(ShortArray(960) { 20000 })
        val run = runBlocks(r, 3, snap(spm = 0))   // 无节拍，只有 oneshot
        assertTrue("oneshot 应可听", run.peakL > 15000)
        assertTrue("播完自动清理", !r.hasActiveSound())
    }
}
