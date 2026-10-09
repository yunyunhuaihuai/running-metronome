package com.metronome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * WAV 回退解析器（[SoundBank.parseWav]）回归：
 * 与 WavRenderToolTest 相同的头部写法做往返；覆盖各 PCM 位深、float、
 * 立体声混缩、畸形 chunk 与取消。
 */
class WavParseTest {

    private fun wavBytes(samples: ShortArray, channels: Int = 1, rate: Int = 48000, bits: Int = 16): ByteArray {
        val out = ByteArrayOutputStream()
        val frames = samples.size / channels
        val dataLen = frames * channels * (bits / 8)
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        out.writeLE(36 + dataLen + (dataLen and 1))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeLE(16)
        // 标准 16 字节 fmt：audioFormat(2) channels(2) rate(4) byteRate(4) blockAlign(2) bits(2)
        out.write(1); out.write(0)                                   // PCM 整型
        out.write(channels and 0xFF); out.write((channels shr 8) and 0xFF)
        out.writeLE(rate)
        out.writeLE(rate * channels * (bits / 8))
        val blockAlign = channels * (bits / 8)
        out.write(blockAlign and 0xFF); out.write((blockAlign shr 8) and 0xFF)
        out.write(bits and 0xFF); out.write((bits shr 8) and 0xFF)
        out.write("data".toByteArray())
        out.writeLE(dataLen)
        for (i in 0 until frames) for (c in 0 until channels) {
            val s = samples[i * channels + c]
            when (bits) {
                16 -> {
                    out.write(s.toInt() and 0xFF)
                    out.write((s.toInt() shr 8) and 0xFF)
                }
                8 -> out.write((s.toInt() + 128) and 0xFF)
            }
        }
        if ((dataLen and 1) != 0) out.write(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLE(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun wavFromData(data: ByteArray, bits: Int, format: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.writeLE(36 + data.size + (data.size and 1))
        out.write("WAVEfmt ".toByteArray())
        out.writeLE(16)
        out.write(format); out.write(0)
        out.write(1); out.write(0) // mono
        out.writeLE(48000)
        out.writeLE(48000 * (bits / 8))
        out.write(bits / 8); out.write(0) // block align
        out.write(bits); out.write(0)
        out.write("data".toByteArray())
        out.writeLE(data.size)
        out.write(data)
        if ((data.size and 1) != 0) out.write(0)
        return out.toByteArray()
    }

    private fun withOddChunkBeforeData(wav: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(wav, 0, 36) // RIFF + 16-byte fmt chunk
        out.write("JUNK".toByteArray())
        out.writeLE(3)
        out.write(byteArrayOf(1, 2, 3, 0)) // 3 data bytes + 1 RIFF padding byte
        out.write(wav, 36, wav.size - 36)
        return out.toByteArray().also { bytes ->
            val riffSize = bytes.size - 8
            for (i in 0..3) bytes[4 + i] = (riffSize shr (8 * i)).toByte()
        }
    }

    @Test
    fun `round trip pcm16 mono`() {
        val samples = shortArrayOf(0, 100, -100, 3276, -3276, 16383)
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavBytes(samples)))!!
        assertEquals(48000, parsed.second)
        assertEquals(samples.toList(), parsed.first.toList())
    }

    @Test
    fun `stereo is downmixed to mono`() {
        // 左满幅、右零 → 混缩后应为左的一半
        val samples = shortArrayOf(1000, 0, -2000, 0)
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavBytes(samples, channels = 2)))!!
        assertEquals(2, parsed.first.size)
        assertEquals(500, parsed.first[0].toInt())
        assertEquals(-1000, parsed.first[1].toInt())
    }

    @Test
    fun `pcm8 unsigned samples map to signed pcm16`() {
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavFromData(byteArrayOf(0, -128, -1), 8)))!!
        assertEquals(listOf(-32768, 0, 32512), parsed.first.map { it.toInt() })
    }

    @Test
    fun `pcm24 signed samples preserve sign and magnitude`() {
        val data = byteArrayOf(0, 0, 0, 0, 0, 0x40, 0, 0, 0xC0.toByte())
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavFromData(data, 24)))!!
        assertEquals(listOf(0, 16384, -16384), parsed.first.map { it.toInt() })
    }

    @Test
    fun `pcm32 signed samples preserve sign and magnitude`() {
        val data = byteArrayOf(0, 0, 0, 0x40, 0, 0, 0, 0xC0.toByte())
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavFromData(data, 32)))!!
        assertEquals(listOf(16384, -16384), parsed.first.map { it.toInt() })
    }

    @Test
    fun `ieee float32 samples are clipped and converted`() {
        val data = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0f).putFloat(0.5f).putFloat(-0.5f).putFloat(2f).array()
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavFromData(data, 32, format = 3)))!!
        assertEquals(listOf(0, 16384, -16384, 32767), parsed.first.map { it.toInt() })
    }

    @Test
    fun `other sample rates preserved`() {
        val samples = shortArrayOf(0, 500, -500, 1000)
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavBytes(samples, rate = 44100)))!!
        assertEquals(44100, parsed.second)
        assertEquals(4, parsed.first.size)
    }

    @Test
    fun `odd sized metadata chunk padding is skipped`() {
        val samples = shortArrayOf(0, 1200, -1200)
        val wav = withOddChunkBeforeData(wavBytes(samples))
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wav))!!
        assertEquals(samples.toList(), parsed.first.toList())
    }

    @Test
    fun `short fmt chunk and unsupported compression are rejected`() {
        val valid = wavBytes(shortArrayOf(100, -100))
        val shortFmt = valid.copyOf().also { it[16] = 12 }
        assertNull(SoundBank.parseWav(ByteArrayInputStream(shortFmt)))

        val aLaw = valid.copyOf().also { it[20] = 6 } // WAVE_FORMAT_ALAW
        assertNull(SoundBank.parseWav(ByteArrayInputStream(aLaw)))
    }

    @Test
    fun `legacy render with misdeclared fmt size is rejected with reason`() {
        // 曾经的 WAV 写入器把 2 字节字段写成 4 字节，却仍声明 fmt 长度为 16。
        // data 实际在偏移 44，解析器按声明在偏移 36 查找，必须拒绝而非误播。
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.writeLE(36 + 4)
        out.write("WAVEfmt ".toByteArray())
        out.writeLE(16)
        out.writeLE(1)       // 错误：PCM 格式本应占 2 字节
        out.writeLE(1)       // 错误：声道数本应占 2 字节
        out.writeLE(48000)
        out.writeLE(96000)
        out.writeLE(2)       // 错误：blockAlign 本应占 2 字节
        out.writeLE(16)      // 错误：位深本应占 2 字节
        out.write("data".toByteArray())
        out.writeLE(4)
        out.write(byteArrayOf(0, 0, 1, 0))

        val reasons = mutableListOf<String>()
        assertNull(SoundBank.parseWav(ByteArrayInputStream(out.toByteArray()), reasons::add))
        assertTrue(reasons.single().contains("fmt 声道数/采样率非法"))
    }

    @Test
    fun `truncated stream returns null`() {
        val full = wavBytes(shortArrayOf(0, 100, -100, 200))
        // 砍掉 data 后半段（头部声明比实际多）
        val truncated = full.copyOf(full.size - 6)
        assertNull(SoundBank.parseWav(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `oversized metadata chunk is rejected before reading its body`() {
        val prefix = wavBytes(shortArrayOf(1)).copyOfRange(0, 36)
        val out = ByteArrayOutputStream().apply {
            write(prefix)
            write("JUNK".toByteArray())
            writeLE(-1) // unsigned 0xffffffff bytes
        }.toByteArray()
        var bodyReads = 0
        val endlessBody = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < out.size) out[pos++].toInt() and 0xFF else {
                bodyReads++
                0
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= out.size) {
                    bodyReads++
                    if (bodyReads > 1) fail("oversized metadata body was scanned")
                    b[off] = 0
                    return 1
                }
                val n = minOf(len, out.size - pos)
                out.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        val reasons = mutableListOf<String>()
        assertNull(SoundBank.parseWav(endlessBody, reasons::add))
        assertEquals(0, bodyReads)
        assertTrue(reasons.single().contains("扫描上限"))
    }

    @Test
    fun `too many zero length chunks are rejected`() {
        val out = ByteArrayOutputStream()
        out.write(wavBytes(shortArrayOf(1)), 0, 36)
        repeat(257) { out.write("JUNK".toByteArray()); out.writeLE(0) }
        val reasons = mutableListOf<String>()
        assertNull(SoundBank.parseWav(ByteArrayInputStream(out.toByteArray()), reasons::add))
        assertTrue(reasons.single().contains("chunk 数"))
    }

    @Test
    fun `truncated metadata chunk is rejected`() {
        val out = ByteArrayOutputStream()
        out.write(wavBytes(shortArrayOf(1)), 0, 36)
        out.write("JUNK".toByteArray())
        out.writeLE(8)
        out.write(byteArrayOf(1, 2))
        val reasons = mutableListOf<String>()
        assertNull(SoundBank.parseWav(ByteArrayInputStream(out.toByteArray()), reasons::add))
        assertTrue(reasons.single().contains("数据截断"))
    }

    @Test
    fun `cancellation is checked while scanning chunks`() {
        val out = ByteArrayOutputStream()
        out.write(wavBytes(shortArrayOf(1)), 0, 36)
        out.write("JUNK".toByteArray())
        out.writeLE(8192)
        out.write(ByteArray(8192))
        var checks = 0
        try {
            SoundBank.parseWav(ByteArrayInputStream(out.toByteArray()), checkActive = {
                if (++checks >= 6) throw CancellationException("cancelled")
            })
            fail("cancellation was ignored")
        } catch (_: CancellationException) {
            assertTrue(checks >= 6)
        }
    }

    @Test
    fun `non wav input returns null`() {
        assertNull(SoundBank.parseWav(ByteArrayInputStream("not a wav file at all".toByteArray())))
        assertNull(SoundBank.parseWav(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `renders of built-in tool wav parse back`() {
        // 与 WavRenderToolTest 相同头部格式的真实样本字节数可被解析
        val samples = ShortArray(4800) { (it % 100 * 40).toShort() }
        val parsed = SoundBank.parseWav(ByteArrayInputStream(wavBytes(samples)))!!
        assertEquals(4800, parsed.first.size)
        assertTrue(parsed.first[10] == samples[10])
    }
}
