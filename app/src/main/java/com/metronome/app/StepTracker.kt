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
import com.metronome.app.core.DetectorCore
import com.metronome.app.core.SessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * 硬件 Step Detector 驱动的步频跟踪与掉速报警（Android 接入层）。
 *
 * 状态机见 [DetectorCore]（纯 Kotlin）：事件时间/处理时间分离、
 * 新鲜度 deadline 与报警解耦、恢复有独立确认 deadline、目标变化重置累计。
 *
 * 本类负责：
 *  - 权限 / 传感器存在性 / registerListener 返回值检查；任一失败都不进入
 *    tracking，绝不因 cadence=0 误报掉速；
 *  - 全部可变状态串行化到 stateLock：sensor 回调（主线程）与协程
 *    （Default 线程）都只通过锁访问状态机；
 *  - 事件驱动 + 一次性 deadline（由 DetectorCore.nextDeadlineMs 提供）；
 *    新鲜度检查与报警开关、报警状态解耦——报警已触发/报警关闭时
 *    仍会按时标记 STALE；
 *  - 对外只发布统一 [Snapshot]（UI 订阅单一 Flow，状态/数值任一变化都会
 *    更新显示，修复旧版"数值不变时状态切换漏更新"的问题）；
 *  - 检测开关独立：detectionEnabled=false 时不注册传感器、清空状态，
 *    UI 显示"检测已关闭"而不是伪装成 0 SPM；
 *  - debug 注入入口 [injectStep]：模拟器无 Step Detector 时验证报警链路。
 */
object StepTracker : SensorEventListener {
    private const val TAG = "StepTracker"

    enum class Availability { OK, NO_PERMISSION, NO_SENSOR, REGISTER_FAILED, DISABLED }

    data class Snapshot(
        val detectionEnabled: Boolean = true,
        val availability: Availability = Availability.OK,
        val state: DetectorCore.State = DetectorCore.State.WARMING_UP,
        val cadenceSpm: Float = 0f,      // 报警路径估计（快速）
        val displaySpm: Float = 0f,      // 显示估计（平滑）
        val alarm: Boolean = false,
        val progressMs: Long = 0,
        val targetSpm: Int = 0,
        val alarmEnabled: Boolean = true,
        val sessionActive: Boolean = false,
    ) {
        val displayValid: Boolean
            get() = detectionEnabled && availability == Availability.OK &&
                state != DetectorCore.State.WARMING_UP
    }

    /** UI 订阅的统一检测快照 */
    val snapshot = MutableStateFlow(Snapshot())

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var stepDetector: Sensor? = null

    private val core = DetectorCore(SystemClock::elapsedRealtime)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var deadlineJob: Job? = null
    private var flowWatcher: Job? = null

    private val stateLock = Any()

    @Volatile private var isTracking = false
    @Volatile private var activityVisible = false
    /** debug 注入激活后（模拟器无传感器），跳过 NO_SENSOR 限制 */
    @Volatile var injectionActive = false
        private set

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

    /** 由 Activity 报告前台可见性：检测在"页面可见"或"服务运行"时启用 */
    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        synchronized(stateLock) { reconcileTrackingLocked() }
    }

    /**
     * 检测的持有规则（工程默认）：
     *  - 检测开关关闭 → 不检测；
     *  - 服务运行（训练会话）→ 检测；
     *  - 服务未运行但主页面可见 → 检测（页面显示实时步频）；
     *  - 其余（后台且无会话）→ 停止注册，省电。
     */
    private fun reconcileTrackingLocked() {
        val enabled = MetronomeEngine.detectionEnabled.value
        val shouldTrack = enabled &&
            (MetronomeEngine.running.value || activityVisible || injectionActive)
        if (shouldTrack && !isTracking) startLocked()
        if (!shouldTrack && isTracking) stopLocked()
        publishLocked()
    }

    private fun startFlowWatcher() {
        if (flowWatcher != null) return
        flowWatcher = scope.launch {
            combine(
                MetronomeEngine.detectionEnabled,
                MetronomeEngine.running,
                MetronomeEngine.targetSpm,
                MetronomeEngine.alarmEnabled,
                MetronomeEngine.sessionState,
            ) { detect, run, target, alarmEn, sess ->
                arrayOf(detect, run, target, alarmEn, sess)
            }.collect {
                synchronized(stateLock) {
                    val prevTarget = core.config.targetSpm
                    val newTarget = MetronomeEngine.targetSpm.value
                    // 目标变化/新段/暂停恢复：清累计 + 适应期（报警响应重置规则）
                    val targetChanged = prevTarget != newTarget
                    updateCoreConfigLocked()
                    if (targetChanged && MetronomeEngine.running.value) {
                        core.onTargetChanged()
                    }
                    reconcileTrackingLocked()
                    evaluateAndScheduleLocked()
                }
            }
        }
    }

    /** 会话是否处于"训练计时中"（报警只在真实训练时有意义） */
    private fun sessionActiveForAlarm(): Boolean =
        MetronomeEngine.sessionState.value == SessionStateMachine.State.RUNNING

    private fun updateCoreConfigLocked() {
        val old = core.config
        val alarmOn = MetronomeEngine.alarmEnabled.value && sessionActiveForAlarm()
        val ac = MetronomeEngine.alarmConfig.value
        core.config = DetectorCore.Config(
            alarmEnabled = alarmOn,
            targetSpm = MetronomeEngine.targetSpm.value,
            slowMarginSpm = ac.slowMarginSpm,
            fastMarginSpm = ac.fastMarginSpm,
            alarmAfterMs = ac.alarmAfterSec * 1000L,
            recoverMs = ac.recoverMs,
            staleTimeoutMs = old.staleTimeoutMs,
            minStepsForValid = old.minStepsForValid,
            minStepIntervalMs = old.minStepIntervalMs,
            graceMs = old.graceMs,
        )
    }

    private fun startLocked() {
        if (injectionActive) {
            // 模拟器/无传感器设备：注入模式下直接进入可用状态
            isTracking = true
            core.reset()
            availabilityInternal = Availability.OK
            Log.i(TAG, "StepTracker started (injection mode)")
            return
        }
        val sm = sensorManager ?: run { availabilityInternal = Availability.NO_SENSOR; return }
        val sd = stepDetector
        if (sd == null) {
            Log.w(TAG, "No step detector sensor found on this device")
            availabilityInternal = Availability.NO_SENSOR
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                appContext!!, Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "ACTIVITY_RECOGNITION not granted; tracker unavailable")
            availabilityInternal = Availability.NO_PERMISSION
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
            availabilityInternal = Availability.REGISTER_FAILED
            return
        }
        core.reset()
        isTracking = true
        availabilityInternal = Availability.OK
        Log.i(TAG, "StepTracker started (event-driven, no fixed ticker)")
    }

    private fun stopLocked() {
        if (!isTracking) return
        isTracking = false
        deadlineJob?.cancel()
        deadlineJob = null
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        core.reset()
        Log.i(TAG, "StepTracker stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
        if (!isTracking) return
        val arrival = SystemClock.elapsedRealtime()
        // event.timestamp 与 SystemClock.elapsedRealtimeNanos 同基（自 API 19 起）
        val eventMs = event.timestamp / 1_000_000L
        synchronized(stateLock) {
            core.onStep(eventMs, arrival)
            publishLocked()
            scheduleDeadlineLocked()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * debug 注入一步（仅测试使用：模拟器无 Step Detector、或验证批量迟到事件）。
     * 复用与真实事件完全相同的估计/状态机路径。
     */
    fun injectStep(eventMs: Long = SystemClock.elapsedRealtime()) {
        if (!injectionActive) {
            injectionActive = true
        }
        synchronized(stateLock) {
            if (!isTracking) reconcileTrackingLocked()
            if (!isTracking) return
            core.onStep(eventMs, SystemClock.elapsedRealtime())
            publishLocked()
            scheduleDeadlineLocked()
        }
    }

    fun clearInjection() {
        injectionActive = false
        synchronized(stateLock) { reconcileTrackingLocked() }
    }

    /**
     * 会话开始/恢复/段切换时由服务调用：清空偏慢累计并给予适应期，
     * 避免沿用旧目标/旧段的累计立刻误报。
     */
    fun coreNotifySessionReset() {
        synchronized(stateLock) {
            updateCoreConfigLocked()
            core.onTargetChanged()
            publishLocked()
            scheduleDeadlineLocked()
        }
    }

    private fun evaluateAndScheduleLocked() {
        core.evaluate()
        publishLocked()
        scheduleDeadlineLocked()
    }

    private fun publishLocked() {
        updateCoreConfigLocked()
        val c = core
        val wasAlarm = snapshot.value.alarm
        val s = Snapshot(
            detectionEnabled = MetronomeEngine.detectionEnabled.value,
            availability = if (!MetronomeEngine.detectionEnabled.value) Availability.DISABLED
            else availabilityInternal,
            state = c.state,
            cadenceSpm = c.cadenceSpm,
            displaySpm = c.displaySpm,
            alarm = c.slowAlarm,
            progressMs = c.slowProgressMs,
            targetSpm = c.config.targetSpm,
            alarmEnabled = c.config.alarmEnabled,
            sessionActive = sessionActiveForAlarm(),
        )
        if (BuildConfig.DEBUG && wasAlarm != s.alarm) {
            val wall = SystemClock.elapsedRealtime()
            if (s.alarm) {
                Log.i("MetroAlarm", "enter slowStart=${c.slowStartAtMs} coreAt=${c.alarmEnteredAtMs} wall=$wall wait=${c.config.alarmAfterMs} cadence=${c.cadenceSpm} target=${c.config.targetSpm}")
            } else {
                Log.i("MetroAlarm", "exit recoveryStart=${c.alarmRecoveryStartAtMs} coreAt=${c.alarmExitedAtMs} wall=$wall confirm=${c.config.recoverMs} cadence=${c.cadenceSpm} target=${c.config.targetSpm}")
            }
        }
        if (s != snapshot.value) snapshot.value = s
    }

    /** 只在状态机给出 deadline 时挂一次性定时器，其余时间零唤醒 */
    private fun scheduleDeadlineLocked() {
        deadlineJob?.cancel()
        val dl = core.nextDeadlineMs() ?: return
        val delayMs = (dl - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        deadlineJob = scope.launch {
            delay(delayMs)
            synchronized(stateLock) {
                if (!isTracking) return@synchronized
                core.evaluate()
                publishLocked()
                scheduleDeadlineLocked()
            }
        }
    }

    private var availabilityInternal = Availability.OK
}
