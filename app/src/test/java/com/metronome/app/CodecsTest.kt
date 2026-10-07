package com.metronome.app

import com.metronome.app.core.AlarmConfig
import com.metronome.app.core.AlarmStyle
import com.metronome.app.core.AudioAsset
import com.metronome.app.core.BlockRenderer
import com.metronome.app.core.LoopSpec
import com.metronome.app.core.Preset
import com.metronome.app.core.PresetCodec
import com.metronome.app.core.TrainingConfig
import com.metronome.app.core.TrainingConfigCodec
import com.metronome.app.core.TrainingSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设/训练配置编解码回归：往返一致、中文与特殊字符、损坏输入容错。 */
class CodecsTest {

    private val preset = Preset(
        id = "p1",
        name = "间歇跑|热身/恢复 ×3",
        targetSpm = 180,
        soundOn = true,
        beatVolume = 70,
        channelMode = BlockRenderer.ChannelMode.ALTERNATE_LR,
        leftTimbre = 4,
        rightTimbre = 5,
        leftCustom = AudioAsset("aud_left", "我的左脚音.wav"),
        rightCustom = null,
        leftUseCustom = true,
        rightUseCustom = false,
        vibrateOn = true,
        vibrateStrength = 85,
        detectionEnabled = true,
        alarmEnabled = false,
        alarm = AlarmConfig(
            slowMarginSpm = 10, alarmAfterSec = 3, fastMarginSpm = 2,
            recoverMs = 800, repeatSec = 4, style = AlarmStyle.LONG_TONE,
        ),
        training = TrainingConfig(
            timerEnabled = true,
            totalDurationSec = 510,
            prepareCountdownSec = 15,
            segments = listOf(
                TrainingSegment("热身", 120, 160),
                TrainingSegment("训练|段", 60, 190),
                TrainingSegment("恢复", 30, 170),
                TrainingSegment("冷身", 120, 150),
            ),
            loop = LoopSpec(1, 2, 3),
        ),
    )

    @Test
    fun `preset round trip preserves everything`() {
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(preset)))
        assertEquals(1, decoded.size)
        assertEquals(preset, decoded[0])
    }

    @Test
    fun `empty custom assets decode to null`() {
        val p = preset.copy(leftCustom = null, leftUseCustom = false)
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(p)))
        assertEquals(p, decoded[0])
        assertNull(decoded[0].leftCustom)
    }

    @Test
    fun `corrupted lines are skipped not fatal`() {
        val good = PresetCodec.encode(listOf(preset))
        val mixed = "V1|garbage\nnot a preset\n$good\nV1|x"
        val decoded = PresetCodec.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals(preset, decoded[0])
    }

    @Test
    fun `empty list round trip`() {
        assertTrue(PresetCodec.decode(PresetCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `training config codec round trip`() {
        val decoded = TrainingConfigCodec.decode(TrainingConfigCodec.encode(preset.training))
        assertEquals(preset.training, decoded)
    }

    @Test
    fun `training config codec tolerant of null garbage`() {
        assertEquals(TrainingConfig(), TrainingConfigCodec.decode(null))
        assertEquals(TrainingConfig(), TrainingConfigCodec.decode(""))
        assertEquals(TrainingConfig(), TrainingConfigCodec.decode("%%%$|bad|"))
    }

    @Test
    fun `alarm config validation clamps bad values`() {
        val v = AlarmConfig(
            slowMarginSpm = 100, alarmAfterSec = 0, fastMarginSpm = 50,
            recoverMs = -5, repeatSec = 999,
        ).validated()
        assertTrue(v.slowMarginSpm in AlarmConfig.SLOW_MARGIN_MIN..AlarmConfig.SLOW_MARGIN_MAX)
        assertTrue(v.alarmAfterSec >= AlarmConfig.AFTER_SEC_MIN)
        assertTrue(v.fastMarginSpm < v.slowMarginSpm)
        assertTrue(v.recoverMs >= AlarmConfig.RECOVER_MS_MIN)
        assertTrue(v.repeatSec <= AlarmConfig.REPEAT_SEC_MAX)
    }
}
