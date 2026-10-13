package com.metronome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A3+C4 音色的离线信号检查；实际操场听感仍需用目标设备试听。 */
class ElectronicSoundTest {

    @Test
    fun `new sounds keep existing preset indexes and have usable levels`() {
        assertEquals(
            listOf("咔嗒", "哔声", "木鱼", "牛铃", "软木", "柔铃", "低鼓"),
            SoundBank.BUILT_IN.take(7),
        )
        assertEquals(listOf("电子短音", "电子中音"), SoundBank.BUILT_IN.drop(7))

        val short = SoundBank.builtin(7)
        val mid = SoundBank.builtin(8)
        assertEquals(5_040, short.size)   // 105ms @ 48kHz
        assertEquals(8_640, mid.size)     // 180ms @ 48kHz
        for (pcm in listOf(short, mid)) {
            assertEquals(0, pcm.first().toInt())
            assertEquals(0, pcm.last().toInt())
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("peak=$peak", peak in 25_000..32_699)
            val rms = sqrt(pcm.map { it.toDouble() * it }.average())
            assertTrue("电子音色平均电平过低：rms=$rms", rms > 14_000.0)
        }
    }

    @Test
    fun `electronic beat keeps both pitches and speaker-range harmonics`() {
        val pcm = SoundBank.builtin(7)
        // 在稳定段测频谱，二次泛音 440/523Hz 帮助小扬声器播出 A3/C4。
        for (hz in listOf(220.0, 261.625565, 440.0, 523.25113)) {
            val amplitude = projection(pcm, hz, 720, 2_880)
            assertTrue("$hz Hz component too weak: $amplitude", amplitude > 0.23)
        }
    }

    private fun projection(pcm: ShortArray, hz: Double, from: Int, endExclusive: Int): Double {
        val rate = SoundBank.RATE.toDouble()
        var real = 0.0
        var imag = 0.0
        for (i in from until endExclusive) {
            val sample = pcm[i] / 32700.0
            val phase = 2 * PI * hz * i / rate
            real += sample * cos(phase)
            imag += sample * sin(phase)
        }
        return 2.0 * sqrt(real * real + imag * imag) / (endExclusive - from)
    }
}
