package com.metronome.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 自动化测试入口（仅 debug 构建存在，release APK 不含此组件）：
 *   am broadcast -a com.metronome.app.debug.START            启动会话（服务+训练）
 *   am broadcast -a com.metronome.app.debug.PAUSE / RESUME
 *   am broadcast -a com.metronome.app.debug.STOP             停止会话与服务
 *   am broadcast -a com.metronome.app.debug.SET \
 *       --ei spm 120 --ez sound true --ez vibrate false --ei vibStrength 80 \
 *       --ei volume 80 --ei channel 0 --ei timbreL 0 --ei timbreR 1 \
 *       --ez detect true --ez alarm true --ei alarmStyle 0 --ei alarmAfter 5 \
 *       --ei slowMargin 8 --ei recoverMs 600 --ei repeatSec 2 \
 *       --ez timer false --ei totalSec 0 --ei prepareSec 0 \
 *       --ez segments false --ei segDurs "60,90" --ei segSpms "120,150" \
 *       --ei loopFrom 0 --ei loopTo 1 --ei loopRounds 3
 *   am broadcast -a com.metronome.app.debug.STATUS           打印会话与设置状态
 *   am broadcast -a com.metronome.app.debug.INJECT --ei spm 100 --ei durationMs 5000
 *       （按给定步频在后台线程持续注入合成步点，模拟跑步；模拟器无 Step Detector 时用）
 *   am broadcast -a com.metronome.app.debug.INJECT_STOP      停止注入
 *   am broadcast -a com.metronome.app.debug.DUCK             启动 DuckProbe 压低验证播放端
 *
 * SET 的各参数均可省略，省略即保持不变。
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        MetronomeEngine.init(context)
        when (intent.action) {
            "com.metronome.app.debug.START" -> MetronomeService.start(context)
            "com.metronome.app.debug.PAUSE" -> MetronomeService.pause(context)
            "com.metronome.app.debug.RESUME" -> MetronomeService.resume(context)
            "com.metronome.app.debug.STOP" -> MetronomeService.stop(context)
            "com.metronome.app.debug.SET" -> {
                if (intent.hasExtra("spm")) MetronomeEngine.setTargetSpm(intent.getIntExtra("spm", -1))
                if (intent.hasExtra("sound")) MetronomeEngine.setSoundOn(
                    intent.getBooleanExtra("sound", true)
                )
                if (intent.hasExtra("vibrate")) MetronomeEngine.setVibrateOn(
                    intent.getBooleanExtra("vibrate", false)
                )
                if (intent.hasExtra("vibStrength")) MetronomeEngine.setVibrateStrength(
                    intent.getIntExtra("vibStrength", -1)
                )
                if (intent.hasExtra("volume")) MetronomeEngine.setBeatVolume(
                    intent.getIntExtra("volume", -1)
                )
                if (intent.hasExtra("channel")) MetronomeEngine.setChannelMode(
                    if (intent.getIntExtra("channel", 0) == 1)
                        com.metronome.app.core.BlockRenderer.ChannelMode.ALTERNATE_LR
                    else com.metronome.app.core.BlockRenderer.ChannelMode.CENTER
                )
                if (intent.hasExtra("timbre")) {
                    val t = intent.getIntExtra("timbre", 0)
                    MetronomeEngine.setLeftTimbre(t)
                    MetronomeEngine.setRightTimbre(t)
                }
                if (intent.hasExtra("timbreL")) MetronomeEngine.setLeftTimbre(
                    intent.getIntExtra("timbreL", 0)
                )
                if (intent.hasExtra("timbreR")) MetronomeEngine.setRightTimbre(
                    intent.getIntExtra("timbreR", 0)
                )
                if (intent.hasExtra("detect")) MetronomeEngine.setDetectionEnabled(
                    intent.getBooleanExtra("detect", true)
                )
                if (intent.hasExtra("alarm")) MetronomeEngine.setAlarmEnabled(
                    intent.getBooleanExtra("alarm", true)
                )
                val base = MetronomeEngine.alarmConfig.value
                var alarm = base
                if (intent.hasExtra("alarmStyle")) {
                    alarm = alarm.copy(
                        style = if (intent.getIntExtra("alarmStyle", 0) == 1)
                            com.metronome.app.core.AlarmStyle.LONG_TONE
                        else com.metronome.app.core.AlarmStyle.OVERLAY
                    )
                }
                if (intent.hasExtra("alarmAfter")) {
                    alarm = alarm.copy(alarmAfterSec = intent.getIntExtra("alarmAfter", 5))
                }
                if (intent.hasExtra("slowMargin")) {
                    alarm = alarm.copy(slowMarginSpm = intent.getIntExtra("slowMargin", 8))
                }
                if (intent.hasExtra("recoverMs")) {
                    alarm = alarm.copy(recoverMs = intent.getIntExtra("recoverMs", 600).toLong())
                }
                if (intent.hasExtra("repeatSec")) {
                    alarm = alarm.copy(repeatSec = intent.getIntExtra("repeatSec", 2))
                }
                if (alarm != base) MetronomeEngine.setAlarmConfig(alarm)

                var cfg = MetronomeEngine.trainingConfig.value
                if (intent.hasExtra("timer")) cfg = cfg.copy(timerEnabled = intent.getBooleanExtra("timer", false))
                if (intent.hasExtra("totalSec")) cfg = cfg.copy(totalDurationSec = intent.getIntExtra("totalSec", 0))
                if (intent.hasExtra("prepareSec")) cfg = cfg.copy(prepareCountdownSec = intent.getIntExtra("prepareSec", 0))
                if (intent.hasExtra("segments")) {
                    val on = intent.getBooleanExtra("segments", false)
                    cfg = if (on) {
                        val durs = intent.getStringExtra("segDurs")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                            ?: listOf(60, 60)
                        val spms = intent.getStringExtra("segSpms")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                            ?: listOf(120, 120)
                        val n = maxOf(durs.size, spms.size)
                        val segs = (0 until n).map { i ->
                            com.metronome.app.core.TrainingSegment(
                                "段${i + 1}",
                                durs.getOrElse(i) { durs.last() },
                                spms.getOrElse(i) { spms.last() },
                            )
                        }
                        val lf = intent.getIntExtra("loopFrom", -1)
                        val lt = intent.getIntExtra("loopTo", -1)
                        val lr = intent.getIntExtra("loopRounds", 1)
                        cfg.copy(
                            segments = segs,
                            loop = if (lf in 0..lt && lt < segs.size && lr >= 1) {
                                com.metronome.app.core.LoopSpec(lf, lt, lr)
                            } else null,
                        )
                    } else {
                        cfg.copy(segments = emptyList(), loop = null)
                    }
                }
                MetronomeEngine.setTrainingConfig(cfg)
                MetronomeEngine.logState("debug-set")
            }
            "com.metronome.app.debug.STATUS" -> {
                MetronomeEngine.logState("debug-status")
                StepTracker.snapshot.value.let {
                    android.util.Log.i(
                        MetronomeEngine.TAG_STATE,
                        "detector: enabled=${it.detectionEnabled} avail=${it.availability}" +
                            " state=${it.state} cadence=${"%.1f".format(it.cadenceSpm)}" +
                            " display=${"%.1f".format(it.displaySpm)} alarm=${it.alarm}" +
                            " progress=${it.progressMs} target=${it.targetSpm}" +
                            " alarmEnabled=${it.alarmEnabled}"
                    )
                }
            }
            "com.metronome.app.debug.INJECT" -> {
                val spm = intent.getIntExtra("spm", 0)
                val durationMs = intent.getIntExtra("durationMs", 0).toLong()
                startInjection(spm, durationMs)
            }
            "com.metronome.app.debug.INJECT_STOP" -> stopInjection()
            "com.metronome.app.debug.DUCK" -> {
                context.startActivity(
                    Intent(context, DuckProbeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun startInjection(spm: Int, durationMs: Long) {
        if (spm <= 0 || durationMs <= 0) return
        stopInjection()
        injectionStop = false
        injectionActive = true
        injectionThread = Thread {
            val interval = 60_000.0 / spm
            var next = SystemClock.elapsedRealtime().toDouble()
            val end = SystemClock.elapsedRealtime() + durationMs
            while (!injectionStop && SystemClock.elapsedRealtime() < end) {
                StepTracker.injectStep(SystemClock.elapsedRealtime())
                next += interval
                val sleep = (next - SystemClock.elapsedRealtime()).toLong()
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.start() }
        android.util.Log.i(
            MetronomeEngine.TAG_STATE, "inject-start spm=$spm durationMs=$durationMs"
        )
    }

    private fun stopInjection() {
        injectionStop = true
        injectionThread?.join(1000)
        injectionThread = null
    }

    companion object {
        @Volatile private var injectionStop = false
        @Volatile private var injectionThread: Thread? = null
        @Volatile var injectionActive = false
    }
}
