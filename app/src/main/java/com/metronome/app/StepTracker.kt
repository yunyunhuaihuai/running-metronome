package com.metronome.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

object StepTracker : SensorEventListener {
    private const val TAG = "StepTracker"

    private var sensorManager: SensorManager? = null
    private var stepDetector: Sensor? = null

    val currentCadence = MutableStateFlow(0)
    val isSlowForLongTime = MutableStateFlow(false)
    val alarmProgressSec = MutableStateFlow(0)

    // Recent step timestamps in nanoseconds (hardware elapsedRealtimeNanos)
    private val recentStepNanos = ConcurrentLinkedQueue<Long>()
    private var lastStepNanos: Long = 0L

    private var slowDurationMs: Long = 0L
    private var fastStreakCount: Int = 0

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null

    @Volatile
    private var isTracking = false

    fun init(context: Context) {
        if (sensorManager != null) return
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        stepDetector = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        Log.i(TAG, "StepTracker initialized. Sensor: ${stepDetector?.name ?: "None"}")
    }

    @Synchronized
    fun start() {
        if (isTracking) return
        val sm = sensorManager ?: return
        val sd = stepDetector
        if (sd == null) {
            Log.w(TAG, "No step detector sensor found on this device!")
            return
        }

        recentStepNanos.clear()
        lastStepNanos = 0L
        slowDurationMs = 0L
        fastStreakCount = 0
        currentCadence.value = 0
        isSlowForLongTime.value = false
        alarmProgressSec.value = 0

        // Register with SENSOR_DELAY_FASTEST and maxReportLatencyUs = 0 for immediate event delivery
        val ok = sm.registerListener(this, sd, SensorManager.SENSOR_DELAY_FASTEST, 0)
        Log.i(TAG, "StepTracker registered: success=$ok")
        isTracking = true

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(200)
                tick()
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isTracking) return
        isTracking = false
        tickerJob?.cancel()
        tickerJob = null
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        recentStepNanos.clear()
        lastStepNanos = 0L
        slowDurationMs = 0L
        fastStreakCount = 0
        currentCadence.value = 0
        isSlowForLongTime.value = false
        alarmProgressSec.value = 0
        Log.i(TAG, "StepTracker stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
        val eventNano = event.timestamp
        val nowElapsedNano = SystemClock.elapsedRealtimeNanos()

        // Use event.timestamp if valid, fallback to nowElapsedNano
        val ts = if (eventNano > 0 && eventNano <= nowElapsedNano) eventNano else nowElapsedNano

        synchronized(recentStepNanos) {
            val last = lastStepNanos
            if (last > 0) {
                val deltaNano = ts - last
                val deltaSec = deltaNano / 1_000_000_000.0
                // Filter out physical impossibilities (< 0.22s means > 272 SPM)
                if (deltaSec < 0.22) {
                    Log.d(TAG, "Ignored jitter step: deltaSec=%.3f".format(deltaSec))
                    return
                }
            }
            lastStepNanos = ts
            recentStepNanos.add(ts)

            // Keep steps within the last 5 seconds or maximum 10 steps
            val windowStart = ts - 5_000_000_000L
            while (recentStepNanos.peek()?.let { it < windowStart } == true) {
                recentStepNanos.poll()
            }
            while (recentStepNanos.size > 10) {
                recentStepNanos.poll()
            }
        }

        computeCadence(nowElapsedNano)
    }

    private fun computeCadence(nowNano: Long) {
        val list: List<Long>
        synchronized(recentStepNanos) {
            list = recentStepNanos.toList()
        }

        if (list.size >= 2) {
            val oldest = list.first()
            val newest = list.last()
            val totalSec = (newest - oldest) / 1_000_000_000.0
            if (totalSec > 0.1) {
                val intervals = list.size - 1
                val calculatedSpm = (intervals / totalSec * 60.0).toInt().coerceIn(30, 240)
                currentCadence.value = calculatedSpm
                Log.d(TAG, "Step! steps=${list.size} over %.2fs -> cadence=$calculatedSpm SPM".format(totalSec))
            }
        }
    }

    private fun tick() {
        val nowNano = SystemClock.elapsedRealtimeNanos()
        val last = lastStepNanos

        // Timeout check: if no step in 2.5 seconds, user has stopped!
        if (last == 0L || (nowNano - last) > 2_500_000_000L) {
            if (currentCadence.value != 0) {
                currentCadence.value = 0
                synchronized(recentStepNanos) {
                    recentStepNanos.clear()
                }
                Log.d(TAG, "Timeout: no steps for 2.5s, cadence reset to 0")
            }
        } else {
            computeCadence(nowNano)
        }

        val targetBpm = MetronomeEngine.bpm.value
        val cadence = currentCadence.value
        val isServiceRunning = MetronomeEngine.running.value

        // If metronome is not running or target BPM is 0, reset alarm
        if (!isServiceRunning || targetBpm <= 0) {
            slowDurationMs = 0L
            fastStreakCount = 0
            isSlowForLongTime.value = false
            alarmProgressSec.value = 0
            return
        }

        // Hysteresis & Debounce
        // Slow condition: cadence is below (target - 8)
        val isSlow = (cadence < targetBpm - 8)

        if (isSlow) {
            fastStreakCount = 0
            slowDurationMs += 200
            val sec = (slowDurationMs / 1000).toInt()
            alarmProgressSec.value = sec

            // Trigger after 5 seconds of continuous slow cadence
            if (slowDurationMs >= 5000L) {
                if (!isSlowForLongTime.value) {
                    isSlowForLongTime.value = true
                    Log.w(TAG, "ALARM TRIGGERED: cadence=$cadence < target=$targetBpm for 5s")
                }
            }
        } else {
            // If cadence recovers to (target - 3) or higher, count fast streak
            if (cadence >= targetBpm - 3) {
                fastStreakCount++
                // If fast for 3 ticks (600ms): immediately clear alarm!
                if (fastStreakCount >= 3) {
                    slowDurationMs = 0L
                    alarmProgressSec.value = 0
                    if (isSlowForLongTime.value) {
                        isSlowForLongTime.value = false
                        Log.i(TAG, "ALARM RECOVERED: cadence=$cadence reached target=$targetBpm")
                    }
                }
            } else {
                // In dead-band (between target-8 and target-3): freeze timer, avoid fluttering
                fastStreakCount = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
