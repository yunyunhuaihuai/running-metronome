package com.metronome.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 自动化测试入口：
 *   adb shell am broadcast -a com.metronome.app.debug.START
 *   adb shell am broadcast -a com.metronome.app.debug.STOP
 *   adb shell am broadcast -a com.metronome.app.debug.SET \
 *       --ei bpm 120 --ez sound true --ez vibrate false --ei vibStrength 80 \
 *       --ei timbre 0 --ez custom false
 *   adb shell am broadcast -a com.metronome.app.debug.STATUS
 * SET 的各参数均可省略，省略即保持不变。
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MetronomeEngine.init(context)
        when (intent.action) {
            "com.metronome.app.debug.START" -> MetronomeService.start(context)
            "com.metronome.app.debug.STOP" -> MetronomeService.stop(context)
            "com.metronome.app.debug.SET" -> {
                if (intent.hasExtra("bpm")) MetronomeEngine.setBpm(intent.getIntExtra("bpm", -1))
                if (intent.hasExtra("sound")) MetronomeEngine.setSoundOn(
                    intent.getBooleanExtra("sound", true)
                )
                if (intent.hasExtra("vibrate")) MetronomeEngine.setVibrateOn(
                    intent.getBooleanExtra("vibrate", false)
                )
                if (intent.hasExtra("vibStrength")) MetronomeEngine.setVibrateStrength(
                    intent.getIntExtra("vibStrength", -1)
                )
                if (intent.hasExtra("timbre")) MetronomeEngine.setTimbre(
                    intent.getIntExtra("timbre", 0)
                )
                if (intent.hasExtra("custom")) MetronomeEngine.setUseCustom(
                    intent.getBooleanExtra("custom", false)
                )
                MetronomeEngine.logState("debug-set")
            }
            "com.metronome.app.debug.STATUS" -> MetronomeEngine.logState("debug-status")
        }
    }
}
