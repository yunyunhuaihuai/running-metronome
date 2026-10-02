package com.metronome.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * 硬件 Step Detector 驱动的步频跟踪与掉速报警。
 *
 * 状态机见 [CadenceStateMachine]（真实单调时间、WARMING_UP/VALID/STALE）。
 * 本类负责：
 *  - 权限 / 传感器存在性 / registerListener 返回值检查；
 *    任一失败都不进入 tracking，也就绝不会因 cadence=0 误报掉速；
 *  - 把全部可变状态串行化到 stateLock：sensor 回调（主线程）与
 *    协程（Default 线程）都只通过锁访问状态机，不依赖未定义的可见性；
 *  - 事件驱动 + 低频一次性 deadline 取代固定 5Hz ticker：
 *      · WARMING_UP / 无报警需求（未运行或 BPM=0）：零定时器，全靠步伐事件；
 *      · VALID：仅一个"步伐超时"deadline（lastStep + 2.5s）；
 *      · 慢速累计期间：报警到点 deadline + 约 4Hz 进度刷新，报警即停；
 *      · 已报警：零定时器，恢复由下一个步伐事件判定。
 *    所有判断基于绝对时间戳，deadline 调度延迟不会导致误判。
 */
object StepTracker : SensorEventListener {
    private const val TAG = "StepTracker"

    enum class Availability { OK, NO_PERMISSION, NO_SENSOR, REGISTER_FAILED }

    val currentCadence = MutableStateFlow(0)
    val isSlowForLongTime = MutableStateFlow(false)
    val alarmProgressSec = MutableStateFlow(0)
    val trackerState = MutableStateFlow(CadenceStateMachine.State.WARMING_UP)
    val availability = MutableStateFlow(Availability.OK)

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var stepDetector: Sensor? = null

    private val machine = CadenceStateMachine(SystemClock::elapsedRealtime)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var deadlineJob: Job? = null
    private var flowWatcher: Job? = null

    private val stateLock = Any()

    @Volatile
    private var isTracking = false

    fun init(context: Context) {
        if (sensorManager != null) return
        val app = context.applicationContext
        appContext = app
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        stepDetector = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        Log.i(TAG, "StepTracker initialized. Sensor: ${stepDetector?.name ?: "None"}")
        startFlowWatcher()
    }

    /** running/bpm 变化即重新评估：BPM=0 或未运行时清零报警，且不挂任何定时器 */
    private fun startFlowWatcher() {
        if (flowWatcher != null) return
        flowWatcher = scope.launch {
            combine(MetronomeEngine.running, MetronomeEngine.bpm) { r, b -> r to b }
                .collect {
                    synchronized(stateLock) { evaluateAndScheduleLocked() }
                }
        }
    }

    @Synchronized
    fun start() {
        if (isTracking) return
        val sm = sensorManager ?: run {
            availability.value = Availability.NO_SENSOR
            return
        }
        val sd = stepDetector
        if (sd == null) {
            Log.w(TAG, "No step detector sensor found on this device!")
            availability.value = Availability.NO_SENSOR
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                appContext!!, Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "ACTIVITY_RECOGNITION not granted; tracker unavailable")
            availability.value = Availability.NO_PERMISSION
            return
        }
        val ok = try {
            sm.registerListener(this, sd, SensorManager.SENSOR_DELAY_FASTEST, 0)
        } catch (e: Exception) {
            Log.w(TAG, "registerListener threw", e)
            false
        }
        if (!ok) {
            Log.w(TAG, "Step detector registration failed")
            availability.value = Availability.REGISTER_FAILED
            return
        }
        synchronized(stateLock) {
            machine.reset()
            publishLocked()
        }
        isTracking = true
        availability.value = Availability.OK
        Log.i(TAG, "StepTracker started (event-driven, no fixed ticker)")
    }

    @Synchronized
    fun stop() {
        if (!isTracking) return
        isTracking = false
        deadlineJob?.cancel()
        deadlineJob = null
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        synchronized(stateLock) {
            machine.reset()
            publishLocked()
        }
        Log.i(TAG, "StepTracker stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
        if (!isTracking) return
        // event.timestamp 与 SystemClock.elapsedRealtimeNanos 同基（自 API 19 起）
        val stepMs = event.timestamp / 1_000_000L
        synchronized(stateLock) {
            machine.onStep(stepMs, MetronomeEngine.bpm.value, alarmEnabled())
            publishLocked()
            scheduleDeadlineLocked()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun alarmEnabled(): Boolean =
        MetronomeEngine.running.value && MetronomeEngine.bpm.value > 0

    private fun evaluateAndScheduleLocked() {
        machine.evaluate(MetronomeEngine.bpm.value, alarmEnabled())
        publishLocked()
        scheduleDeadlineLocked()
    }

    private fun publishLocked() {
        currentCadence.value = machine.cadenceSpm
        isSlowForLongTime.value = machine.slowAlarm
        alarmProgressSec.value = machine.slowProgressSec
        trackerState.value = machine.state
    }

    /** 只在状态机给出 deadline 时挂一次性定时器，其余时间零唤醒 */
    private fun scheduleDeadlineLocked() {
        deadlineJob?.cancel()
        val dl = machine.nextDeadlineMs(MetronomeEngine.bpm.value, alarmEnabled()) ?: return
        val delayMs = (dl - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        deadlineJob = scope.launch {
            delay(delayMs)
            synchronized(stateLock) { evaluateAndScheduleLocked() }
        }
    }
}
