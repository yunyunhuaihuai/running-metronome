package com.metronome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 离线渲染正式路径的短 WAV 样本（音色库/提示音/结束音），
 * 供人工听检截断/突变并检查峰值、RMS、首尾连续性。
 *
 * 说明：这只证明渲染链路（合成/限幅/包络），不代表手机扬声器或
 * 蓝牙耳机的现场听感。
 *
 * 运行方式（可选执行，平时跳过）：
 *   gradlew testDebugUnitTest --tests "*WavRenderToolTest*"
 *     -Drender.wav.out=<输出目录>
 */
class WavRenderToolTest {

    @Test
    fun `render built-in samples to wav when output dir provided`() {
        val outDir = System.getProperty("render.wav.out") ?: return   // 未指定则跳过
        val dir = File(outDir).apply { mkdirs() }

        SoundBank.ensureBuiltIns()
        val samples = linkedMapOf(
            "beat_click.wav" to SoundBank.builtin(0),
            "beat_beep.wav" to SoundBank.builtin(1),
            "beat_wood.wav" to SoundBank.builtin(2),
            "beat_cowbell.wav" to SoundBank.builtin(3),
            "beat_softwood.wav" to SoundBank.builtin(4),
            "beat_softbell.wav" to SoundBank.builtin(5),
            "beat_lowdrum.wav" to SoundBank.builtin(6),
            "prompt.wav" to SoundBank.promptPcm(),
            "finish.wav" to SoundBank.finishPcm(),
        )
        for ((name, pcm) in samples) {
            writeWav(File(dir, name), pcm, SoundBank.RATE)
            checkSample(name, pcm)
        }
    }

    /** 峰值/首尾/单调性检查：所有音色首尾必须为 0（无 click），峰值不超限 */
    private fun checkSample(name: String, pcm: ShortArray) {
        assertTrue("$name 不能为空", pcm.isNotEmpty())
        var peak = 0
        for (s in pcm) peak = maxOf(peak, if (s < 0) -s else s.toInt())
        assertTrue("$name 峰值超限 $peak", peak <= 32700)
        assertEquals("$name 首样本应为 0（无起音 click）: $name", 0, pcm[0].toInt())
        assertEquals("$name 尾样本应为 0（无收尾 click）: $name", 0, pcm[pcm.size - 1].toInt())
        assertTrue("$name 应有实际内容", peak > 1000)
    }

    private fun writeWav(file: File, pcm: ShortArray, rate: Int) {
        val dataLen = pcm.size * 2
        FileOutputStream(file).use { out ->
            out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            out.writeLE(36 + dataLen)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.writeLE(16)
            out.writeLE(1)          // PCM
            out.writeLE(1)          // mono
            out.writeLE(rate)
            out.writeLE(rate * 2)   // byte rate
            out.writeLE(2)          // block align
            out.writeLE(16)         // bits
            out.write("data".toByteArray())
            out.writeLE(dataLen)
            for (s in pcm) {
                out.write(s.toInt() and 0xFF)
                out.write((s.toInt() shr 8) and 0xFF)
            }
        }
    }

    private fun FileOutputStream.writeLE(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }
}
