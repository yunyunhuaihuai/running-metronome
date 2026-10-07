package com.metronome.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.metronome.app.core.BlockRenderer
import com.metronome.app.core.SessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * 前台服务（mediaPlayback）：训练会话 + AudioTrack PCM 采样时钟。
 *
 * 架构（单一控制线程 + 专用音频线程 + 振动线程）：
 *  - 控制线程（metronome-ctl）：所有会话命令（启动/暂停/继续/停止/调步频）、
 *    音频焦点申请与放弃、通知与 MediaSession 更新、会话计时 tick 在此串行执行；
 *    音频焦点 API 绝不在音频实时线程调用。
 *  - 音频线程（metronome-clock）：持有 AudioTrack 生命周期，每 10ms 渲染一个
 *    block（[BlockRenderer]：双脚音色/声道/报警提示/门控/主音量/限幅），
 *    以实际成功写入的帧数推进内容时间线；部分写入的尾部保留重写、不重渲染；
 *    可恢复错误（DEAD_OBJECT 等）有限次重建轨道并重设帧基准，超限进入 ERROR。
 *  - 振动线程（metronome-vib）：AudioTrack marker 回调驱动，振动与计数与
 *    听到的声音对齐；声音被门控静音时不影响振动与计数。
 *
 * 会话状态机（[SessionStateMachine]）持有训练进度：暂停冻结计时不清进度，
 * 停止才销毁；FINISHED 保留完成信息，再次启动是新会话。
 */
class MetronomeService : Service() {

    companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "metronome"
        const val TAG = "MetronomeService"

        const val ACTION_START = "com.metronome.app.action.START_SESSION"
        const val ACTION_PAUSE = "com.metronome.app.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.metronome.app.action.RESUME_SESSION"
        const val ACTION_STOP = "com.metronome.app.action.STOP_SESSION"
        const val ACTION_ADJUST = "com.metronome.app.action.ADJUST_TARGET"
        const val ACTION_RECOVER_FOCUS = "com.metronome.app.action.RECOVER_FOCUS"

        private const val BLOCK_FRAMES = 480   // 每次写入 10ms @48kHz
        private const val TICK_MS = 250L
        private const val MAX_TRACK_REBUILDS = 3
        private const val DUCK_VOLUME_FACTOR = 0.3f

        fun start(context: Context) =
            ContextCompat.startForegroundService(
                context, Intent(context, MetronomeService::class.java).setAction(ACTION_START)
            )

        fun pause(context: Context) =
            context.startService(Intent(context, MetronomeService::class.java).setAction(ACTION_PAUSE))

        fun resume(context: Context) =
            context.startService(Intent(context, MetronomeService::class.java).setAction(ACTION_RESUME))

        fun stop(context: Context) =
            context.startService(Intent(context, MetronomeService::class.java).setAction(ACTION_STOP))

        fun adjustTarget(context: Context, delta: Int) =
            context.startService(
                Intent(context, MetronomeService::class.java).setAction(ACTION_ADJUST)
                    .putExtra("delta", delta)
            )
    }

    enum class FocusUi { NONE, ACTIVE, DUCKED, INTERRUPTED, LOST, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ------------------------------------------------------------ 控制线程
    private lateinit var ctlThread: HandlerThread
    private lateinit var ctlHandler: Handler

    // ------------------------------------------------------------ 会话
    private val session = SessionStateMachine(SystemClock::elapsedRealtime)
    private val focusUi = MutableStateFlow(FocusUi.NONE)

    @Volatile private var currentTargetSpm = 0     // 音频线程读
    @Volatile private var renderActive = false     // 音频线程读：是否渲染
    @Volatile private var finishChimePending = false
    @Volatile private var chimeRequest = false     // 音频线程消费：排队结束提示音
    @Volatile private var mutedByFocus = false     // 音频线程读
    @Volatile private var ducked = false           // 音频线程读：被要求压低
    @Volatile private var serviceAlive = false

    private var lastSegmentKey: Pair<Int, Int>? = null  // (segIndex, round)

    // ------------------------------------------------------------ 资源
    private var vibrator: Vibrator? = null
    private var vibThread: HandlerThread? = null
    private lateinit var vibHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var clockThread: ClockThread? = null

    // ------------------------------------------------------------ 音频焦点
    // 策略：会话运行且声音开 → 申请 GAIN_TRANSIENT_MAY_DUCK（音乐整体压低、
    // 节拍叠加其上）；被系统要求压低（LOSS_TRANSIENT_CAN_DUCK）→ 本 App 音量
    // 降到 30%；临时丢失 → 静音、GAIN 后自动恢复；永久丢失 → 静音并明确提示，
    // 不自动抢回，用户通过通知"恢复声音"或声音开关重新获得。
    // 仅振动（声音关）时从不申请焦点；暂停/结束/停止时主动放弃（音乐恢复原音量）。
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // 回调在主线程投递，序列化到控制线程处理
        if (this::ctlHandler.isInitialized) {
            ctlHandler.post { handleFocusChange(change) }
        }
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                mutedByFocus = true
                ducked = false
                focusUi.value = FocusUi.LOST
                abandonAudioFocusRequest()
                MetronomeEngine.logFocus("focus LOSS -> muted (user recover required)")
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mutedByFocus = true
                ducked = false
                focusUi.value = FocusUi.INTERRUPTED
                MetronomeEngine.logFocus("focus LOSS_TRANSIENT -> muted (auto resume on GAIN)")
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                mutedByFocus = false
                focusUi.value = FocusUi.DUCKED
                MetronomeEngine.logFocus("focus LOSS_CAN_DUCK -> ducked to 30%")
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mutedByFocus = false
                ducked = false
                focusHeld = true
                focusUi.value = FocusUi.ACTIVE
                MetronomeEngine.logFocus("focus GAIN -> sound restored")
                updateNotification()
            }
        }
    }

    private fun requestFocusIfNeeded() {
        val need = serviceAlive && sessionActive() && MetronomeEngine.soundOn.value
        if (!need) {
            if (focusHeld) abandonAudioFocusRequest()
            if (!sessionActive()) focusUi.value = FocusUi.NONE
            return
        }
        if (focusHeld) {
            focusUi.value = if (ducked) FocusUi.DUCKED else FocusUi.ACTIVE
            return
        }
        val am = getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        audioFocusRequest = req
        val granted = try {
            am.requestAudioFocus(req)
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        when (granted) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                focusHeld = true
                mutedByFocus = false
                focusUi.value = FocusUi.ACTIVE
            }
            else -> {
                focusHeld = false
                mutedByFocus = true
                focusUi.value = FocusUi.FAILED
            }
        }
        MetronomeEngine.logFocus("request MAY_DUCK -> granted=$granted state=${focusUi.value}")
    }

    private fun abandonAudioFocusRequest() {
        audioFocusRequest?.let { req ->
            try {
                getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
            } catch (_: Exception) {}
        }
        audioFocusRequest = null
        focusHeld = false
    }

    private fun abandonFocusAndClearState() {
        abandonAudioFocusRequest()
        mutedByFocus = false
        ducked = false
        if (!sessionActive()) focusUi.value = FocusUi.NONE
    }

    // ------------------------------------------------------------ WakeLock
    private fun acquireCpuLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.metronome.app:clock")
        }
        try { wakeLock?.acquire(12 * 60 * 60 * 1000L) } catch (_: Exception) {}
    }

    private fun releaseCpuLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MetronomeEngine.init(this)
        StepTracker.init(this)

        ctlThread = HandlerThread("metronome-ctl").also { it.start() }
        ctlHandler = Handler(ctlThread.looper)

        vibrator = getSystemService(Vibrator::class.java)
        vibThread = HandlerThread("metronome-vib").also { it.start() }
        vibHandler = Handler(vibThread!!.looper)

        createChannel()
        setupMediaSession()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        MetronomeEngine.running.value = true
        serviceAlive = true
        clockThread = ClockThread().also { it.start() }

        // 设置变化 → 焦点/通知刷新（控制线程）
        scope.launch {
            combine(
                MetronomeEngine.soundOn,
                MetronomeEngine.beatVolume,
                MetronomeEngine.channelMode,
                MetronomeEngine.vibrateOn,
                MetronomeEngine.alarmEnabled,
            ) { _, _, _, _, _ -> }.collect {
                ctlHandler.post {
                    requestFocusIfNeeded()
                    updateNotification()
                }
            }
        }
        MetronomeEngine.logState("service-created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START   // 旧入口：无 action 视为启动会话
        val delta = intent?.getIntExtra("delta", 0) ?: 0
        when (action) {
            ACTION_START -> ctlHandler.post { cmdStart() }
            ACTION_PAUSE -> ctlHandler.post { cmdPause() }
            ACTION_RESUME -> ctlHandler.post { cmdResume() }
            ACTION_STOP -> ctlHandler.post { cmdStopAndSelf() }
            ACTION_ADJUST -> ctlHandler.post { cmdAdjustTarget(delta) }
            ACTION_RECOVER_FOCUS -> ctlHandler.post { cmdRecoverFocus() }
        }
        return START_NOT_STICKY   // 系统回收后不复活半死会话；用户重新开始
    }

    override fun onDestroy() {
        serviceAlive = false
        renderActive = false
        // 音频线程在 finally 中自行 stop/release track（单一所有者，无并发释放）
        clockThread?.let { t ->
            try { t.join(2500) } catch (_: InterruptedException) {}
        }
        clockThread = null
        vibThread?.quitSafely()
        try { vibThread?.join(500) } catch (_: InterruptedException) {}
        vibThread = null
        releaseCpuLock()
        abandonAudioFocusRequest()
        try { mediaSession?.isActive = false } catch (_: Exception) {}
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        session.cancel()
        MetronomeEngine.sessionState.value = SessionStateMachine.State.IDLE
        MetronomeEngine.clockRunning.value = false
        MetronomeEngine.running.value = false
        MetronomeEngine.beatCount.value = 0
        MetronomeEngine.sessionPos.value = null
        MetronomeEngine.logState("service-destroyed")
        scope.cancel()
        ctlThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 会话命令（控制线程）

    private fun sessionActive(): Boolean = when (session.state) {
        SessionStateMachine.State.PREPARING, SessionStateMachine.State.RUNNING -> true
        else -> false
    }

    private fun cmdStart() {
        if (sessionActive()) return   // 幂等：重复启动忽略
        val config = MetronomeEngine.trainingConfig.value
        if (!session.start(config)) {
            Log.w(TAG, "invalid training config, fallback to plain: ${config.validate()}")
            session.start(com.metronome.app.core.TrainingConfig())
        }
        MetronomeEngine.beatCount.value = 0
        lastSegmentKey = null
        renderer.reset()
        // 分段计划：首段目标立即生效；普通/定时模式沿用手动目标。
        // 会话期间目标随段切换并写回 engine（结束后保留最后有效目标）。
        if (session.config.segmentMode) {
            session.config.segments.firstOrNull()?.let { MetronomeEngine.setTargetSpm(it.targetSpm) }
        }
        currentTargetSpm = MetronomeEngine.targetSpm.value
        renderActive = true
        finishChimePending = false
        acquireCpuLock()
        requestFocusIfNeeded()
        StepTracker.coreNotifySessionReset()   // 目标变化 → 报警累计重置 + 适应期
        startTick()
        publishSession()
        updateNotification()
        MetronomeEngine.logState("session-start")
    }

    private fun cmdPause() {
        if (!session.pause()) return
        renderActive = false          // 音频线程将 pause 轨道并清理声部
        releaseCpuLock()
        abandonFocusAndClearState()   // 暂停时让音乐恢复原音量
        publishSession()
        updateNotification()
        MetronomeEngine.logState("session-paused")
    }

    private fun cmdResume() {
        if (!session.resume()) return
        renderer.reset()
        currentTargetSpm = MetronomeEngine.targetSpm.value
        renderActive = true
        acquireCpuLock()
        requestFocusIfNeeded()
        StepTracker.coreNotifySessionReset()
        startTick()
        publishSession()
        updateNotification()
        MetronomeEngine.logState("session-resumed")
    }

    private fun cmdStopAndSelf() {
        session.cancel()
        renderActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cmdAdjustTarget(delta: Int) {
        if (delta == 0) return
        val base = if (sessionActive()) currentTargetSpm else MetronomeEngine.targetSpm.value
        val nv = (base + delta).coerceIn(MetronomeEngine.SPM_MIN, MetronomeEngine.SPM_MAX)
        MetronomeEngine.setTargetSpm(nv)
        currentTargetSpm = nv
        // 手动调整目标 → 报警累计重置 + 适应期（由 StepTracker 监听 targetSpm 流完成）
        updateNotification()
    }

    private fun cmdRecoverFocus() {
        if (!sessionActive() || !MetronomeEngine.soundOn.value) return
        MetronomeEngine.logFocus("user requests focus recovery")
        mutedByFocus = false
        requestFocusIfNeeded()
        updateNotification()
    }

    private fun cmdAudioError(reason: String) {
        session.fail()
        renderActive = false
        releaseCpuLock()
        abandonFocusAndClearState()
        MetronomeEngine.sessionState.value = SessionStateMachine.State.ERROR
        publishSession()
        updateNotification()
        Log.e(TAG, "audio error: $reason")
        MetronomeEngine.logState("audio-error $reason")
    }

    private fun handleFinish() {
        releaseCpuLock()
        abandonFocusAndClearState()
        MetronomeEngine.sessionState.value = SessionStateMachine.State.FINISHED
        MetronomeEngine.clockRunning.value = false
        // 结束提示一次：由音频线程排队提示音（走正式混音/音量路径），
        // 播完自动转入空闲；不继续输出周期节拍或振动。
        finishChimePending = true
        chimeRequest = true
        currentTargetSpm = 0
        renderActive = true
        MetronomeEngine.logState("session-finished byCap=${session.finishedByCap}")
    }

    private fun publishSession() {
        val st = session.state
        MetronomeEngine.sessionState.value = st
        MetronomeEngine.clockRunning.value = sessionActive()
        MetronomeEngine.sessionPos.value = session.position()
    }

    // ------------------------------------------------------------ 会话 tick

    private var tickScheduled = false
    private var tickCount = 0

    private fun startTick() {
        if (tickScheduled) return
        tickScheduled = true
        ctlHandler.postDelayed({ tick() }, TICK_MS)
    }

    private fun tick() {
        tickScheduled = false
        if (!serviceAlive) return
        val transition = session.tick()

        if (transition.changed && transition.to == SessionStateMachine.State.FINISHED) {
            handleFinish()
            publishSession()
            updateNotification()
            return
        }

        if (!sessionActive()) {
            publishSession()
            return
        }

        // 段/轮变化 → 目标随段切换（写回 engine，UI 与检测同步）+ 报警累计重置
        val pos = session.position()
        if (pos != null && session.config.segmentMode && pos.segmentIndex >= 0) {
            val key = pos.segmentIndex to pos.round
            if (key != lastSegmentKey) {
                lastSegmentKey = key
                MetronomeEngine.setTargetSpm(pos.segmentTargetSpm)
                currentTargetSpm = pos.segmentTargetSpm
                StepTracker.coreNotifySessionReset()
                MetronomeEngine.logState("segment-enter seg=${pos.segmentIndex} round=${pos.round}")
            }
        }

        startTick()
        publishSession()
        tickCount++
        if (tickCount % 8 == 0) updateNotification()   // 约 2 秒刷新通知内容
    }

    // ------------------------------------------------------------ MediaSession

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "Metronome").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { ctlHandler.post { cmdResume() } }
                override fun onPause() { ctlHandler.post { cmdPause() } }
                override fun onStop() { ctlHandler.post { cmdStopAndSelf() } }
                override fun onSkipToNext() { ctlHandler.post { cmdAdjustTarget(5) } }
                override fun onSkipToPrevious() { ctlHandler.post { cmdAdjustTarget(-5) } }
                override fun onFastForward() { ctlHandler.post { cmdAdjustTarget(5) } }
                override fun onRewind() { ctlHandler.post { cmdAdjustTarget(-5) } }
            }, Handler(ctlThread.looper))
            setPlaybackState(buildPlaybackState())
            isActive = true
        }
    }

    private fun buildPlaybackState(): PlaybackState {
        val st = MetronomeEngine.sessionState.value
        val posMs = MetronomeEngine.sessionPos.value?.trainingElapsedMs ?: 0L
        val state = when (st) {
            SessionStateMachine.State.RUNNING, SessionStateMachine.State.PREPARING ->
                PlaybackState.STATE_PLAYING
            SessionStateMachine.State.PAUSED -> PlaybackState.STATE_PAUSED
            SessionStateMachine.State.FINISHED -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_NONE
        }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
        val pb = PlaybackState.Builder().setActions(actions).setState(
            state, posMs, if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
            SystemClock.elapsedRealtime()
        )
        return pb.build()
    }

    // ------------------------------------------------------------ 音频渲染

    private val renderer = BlockRenderer(
        BLOCK_FRAMES, MetronomeEngine.RATE, stereo = true,
        sounds = object : BlockRenderer.SoundResolver {
            override fun beatPcm(foot: BlockRenderer.Foot): ShortArray? =
                SoundBank.resolveBeat(foot)
            override fun promptPcm(): ShortArray? = SoundBank.promptPcm()
        },
    )

    /** 音频线程逐 block 组装配置快照（无锁读 volatile/StateFlow.value） */
    private fun buildSnapshot(): BlockRenderer.Snapshot {
        val alarmLatched = StepTracker.snapshot.value.alarm
        val style = MetronomeEngine.alarmConfig.value.style
        val volBase = MetronomeEngine.beatVolume.value / 100f
        val vol = volBase * if (ducked) DUCK_VOLUME_FACTOR else 1f
        val gateOpen = MetronomeEngine.soundOn.value && !mutedByFocus
        return BlockRenderer.Snapshot(
            targetSpm = currentTargetSpm,
            soundEnabled = MetronomeEngine.soundOn.value,
            gateOpen = gateOpen,
            volume = vol,
            channelMode = MetronomeEngine.channelMode.value,
            alarmToneActive = alarmLatched && style == com.metronome.app.core.AlarmStyle.LONG_TONE,
            promptActive = alarmLatched && style == com.metronome.app.core.AlarmStyle.OVERLAY,
            promptIntervalFrames = MetronomeEngine.alarmConfig.value.repeatSec * MetronomeEngine.RATE.toLong(),
        )
    }

    private class PendingBeat(val num: Long, val frame: Long, val foot: BlockRenderer.Foot)

    private inner class ClockThread : Thread("metronome-clock") {
        val pending = ConcurrentLinkedQueue<PendingBeat>()
        val markerLock = Any()
        @Volatile var markerArmed = false

        private var track: AudioTrack? = null
        private var trackBase = 0L        // 当前 track 创建时的内容帧号（marker 基准换算）
        private var streamFrames = 0L     // 内容时间线：已渲染并成功写入的帧数
        private var rebuilds = 0
        @Volatile private var trackNeedsPlay = true   // 播放状态自持（新建/暂停后需 play）
        private val block = ShortArray(BLOCK_FRAMES * 2)  // 立体声交错

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (serviceAlive) {
                    if (!renderActive) {
                        idleDown()
                        sleepQuiet(20)
                        continue
                    }
                    if (!renderOneBlock()) {
                        // 不可恢复错误：上报控制线程，音频转空闲
                        ctlHandler.post { cmdAudioError("track write/render failed") }
                        renderActive = false
                        continue
                    }
                }
            } finally {
                releaseTrack()
                synchronized(markerLock) { markerArmed = false }
                pending.clear()
            }
        }

        private fun idleDown() {
            track?.let { t ->
                try { t.pause() } catch (_: IllegalStateException) {}
            }
            trackNeedsPlay = true
        }

        private fun ensureTrack(): AudioTrack? {
            val t = track
            if (t != null) return t
            val nt = buildTrack()
            track = nt
            trackBase = streamFrames
            trackNeedsPlay = true
            return nt
        }

        private fun buildTrack(): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(
                MetronomeEngine.RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(minBuf, BLOCK_FRAMES * 2 * 8)
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(MetronomeEngine.RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
                .build()
            at.setPlaybackPositionUpdateListener(beatListener, vibHandler)
            return at
        }

        private fun releaseTrack() {
            track?.let { t ->
                try { t.stop() } catch (_: IllegalStateException) {}
                try { t.release() } catch (_: Exception) {}
            }
            track = null
        }

        private fun rebuildTrack(): Boolean {
            releaseTrack()
            pending.clear()
            synchronized(markerLock) { markerArmed = false }
            renderer.reset()
            // 内容时间线保留；新轨道基准在 ensureTrack 时设置
            rebuilds++
            return rebuilds <= MAX_TRACK_REBUILDS
        }

        /** 渲染并写入一个 block；返回 false 表示不可恢复错误 */
        private fun renderOneBlock(): Boolean {
            val t = ensureTrack() ?: return false
            try {
                if (trackNeedsPlay) {
                    t.play()
                    trackNeedsPlay = false
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "track play failed", e)
                trackNeedsPlay = true
                return if (rebuildTrack()) true else false
            }

            if (chimeRequest) {   // 消费结束提示请求（音频线程内排队，无跨线程竞争）
                chimeRequest = false
                renderer.queueOneShot(SoundBank.finishPcm())
            }

            val frameBase = streamFrames
            val snap = buildSnapshot()
            val result = renderer.renderBlock(frameBase, snap, block)

            // 挂起本 block 内将可听到的拍（marker 驱动振动与计数）
            for (b in result.beats) {
                pending.offer(PendingBeat(b.num, b.frame, b.foot))
            }

            // 部分写入处理：保留尚未写完的 PCM 继续写，不重渲染、不虚增帧数；
            // 立体声 write 返回的是 short 数量，除以声道数才是帧
            var offset = 0
            var zeroStreak = 0
            while (offset < block.size) {
                val n = try {
                    t.write(block, offset, block.size - offset, AudioTrack.WRITE_BLOCKING)
                } catch (e: Exception) {
                    Log.e(TAG, "audio write exception", e)
                    return if (rebuildTrack()) true else false
                }
                if (n < 0) {
                    Log.e(TAG, "audio write failed: $n")
                    val dead = n == AudioTrack.ERROR_DEAD_OBJECT
                    return if (dead && rebuildTrack()) true else false
                }
                if (n == 0) {
                    if (++zeroStreak > 50) {
                        Log.e(TAG, "audio write stuck (zero x50)")
                        return if (rebuildTrack()) true else false
                    }
                    sleepQuiet(5)
                    continue
                }
                zeroStreak = 0
                offset += n
            }
            streamFrames += BLOCK_FRAMES
            rebuilds = 0   // 成功写入一块即认为轨道健康，重建额度重新计

            armMarker(t)

            // 结束提示播完（无节拍、无残余声部、无待播 oneshot）→ 音频转空闲
            if (finishChimePending && currentTargetSpm == 0 && !renderer.hasActiveSound()) {
                finishChimePending = false
                renderActive = false
                renderer.clearOneShots()
            }
            return true
        }

        private fun armMarker(t: AudioTrack) {
            synchronized(markerLock) {
                if (markerArmed) return
                val head = pending.peek() ?: return
                val rel = (head.frame - trackBase).toInt()
                if (rel < 0) {   // 旧轨道遗留（重建后基准变化），丢弃
                    pending.poll()
                    return
                }
                t.setNotificationMarkerPosition(rel)
                markerArmed = true
            }
        }

        private fun sleepQuiet(ms: Long) {
            try { sleep(ms) } catch (_: InterruptedException) {
                // 中断即退出循环（serviceAlive 检查会处理）
            }
        }
    }

    private val beatListener = object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack?) {
            val ct = clockThread ?: return
            val b = synchronized(ct.markerLock) { ct.markerArmed = false; ct.pending.poll() }
                ?: return
            MetronomeEngine.beatCount.value = b.num + 1
            if (MetronomeEngine.vibrateOn.value) fireVibration()
            if (BuildConfig.DEBUG) {
                val audioMs = b.frame * 1000.0 / MetronomeEngine.RATE
                Log.i(
                    MetronomeEngine.TAG_BEAT,
                    "beat=%d frame=%d foot=%s audioMs=%.1f wall=%d vibrate=%b".format(
                        b.num + 1, b.frame, b.foot, audioMs,
                        SystemClock.elapsedRealtime(), MetronomeEngine.vibrateOn.value
                    )
                )
            }
        }

        override fun onPeriodicNotification(track: AudioTrack?) {}
    }

    /** 按用户设置的强度（1..100%）逐拍触发振动（必须在振动线程调用） */
    private fun fireVibration() {
        val v = vibrator ?: return
        val s = MetronomeEngine.vibrateStrength.value.coerceIn(1, 100)
        val timings: LongArray
        val amplitudes: IntArray
        if (v.hasAmplitudeControl()) {
            val amp = (40 + 2.15 * s).roundToInt().coerceIn(1, 255)
            val onMs = (70 + 0.5 * s).roundToInt()
            timings = longArrayOf(0, onMs.toLong())
            amplitudes = intArrayOf(0, amp)
        } else {
            val onMs = (70 + 1.1 * s).roundToInt()
            if (s >= 60) {
                timings = longArrayOf(0, onMs.toLong(), 50, onMs.toLong())
                amplitudes = intArrayOf(0, -1, 0, -1)
            } else {
                timings = longArrayOf(0, onMs.toLong())
                amplitudes = intArrayOf(0, -1)
            }
        }
        try {
            v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun pi(action: String, code: Int, extra: Pair<String, Int>? = null): PendingIntent {
        val intent = Intent(this, MetronomeService::class.java).setAction(action)
        extra?.let { intent.putExtra(it.first, it.second) }
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification {
        val st = MetronomeEngine.sessionState.value
        val spm = currentTargetSpm
        val vol = MetronomeEngine.beatVolume.value
        val focusText = when (focusUi.value) {
            FocusUi.DUCKED -> " · 音乐压低中"
            FocusUi.INTERRUPTED -> " · 声音被临时中断"
            FocusUi.LOST -> " · 声音被其他应用占用"
            FocusUi.FAILED -> " · 焦点申请失败（静音）"
            else -> ""
        }
        val pos = MetronomeEngine.sessionPos.value
        val text = when (st) {
            SessionStateMachine.State.RUNNING -> {
                val rem = pos?.totalRemainingMs
                val seg = if (session.config.segmentMode && pos != null && pos.segmentIndex >= 0)
                    " · 段${pos.segmentIndex + 1}/${session.config.segments.size} 轮${pos.round}" else ""
                val remText = rem?.let { " · 剩余 ${it / 60000}分${(it % 60000) / 1000}秒" } ?: ""
                "$spm SPM · 音量$vol%$seg$remText$focusText"
            }
            SessionStateMachine.State.PREPARING ->
                "准备开始 ${session.prepareRemainingMs() / 1000}s · $spm SPM$focusText"
            SessionStateMachine.State.PAUSED -> "已暂停 · 进度保留$focusText"
            SessionStateMachine.State.FINISHED ->
                if (session.finishedByCap) "时间到 · 训练完成（${session.finishElapsedMs / 1000}s）"
                else "训练完成（${session.finishElapsedMs / 1000}s）"
            SessionStateMachine.State.ERROR -> "音频错误 · 请停止后重新开始"
            else -> "就绪 · $spm SPM"
        }
        val contentPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentPI)
        when (st) {
            SessionStateMachine.State.RUNNING, SessionStateMachine.State.PREPARING -> {
                b.addAction(0, getString(R.string.notif_pause), pi(ACTION_PAUSE, 2))
                b.addAction(0, "-5", pi(ACTION_ADJUST, 4, "delta" to -5))
                b.addAction(0, "+5", pi(ACTION_ADJUST, 5, "delta" to 5))
                b.addAction(0, getString(R.string.stop_action), pi(ACTION_STOP, 1))
                if (focusUi.value == FocusUi.LOST) {
                    b.addAction(0, getString(R.string.notif_recover_sound), pi(ACTION_RECOVER_FOCUS, 6))
                }
            }
            SessionStateMachine.State.PAUSED -> {
                b.addAction(0, getString(R.string.notif_resume), pi(ACTION_RESUME, 3))
                b.addAction(0, getString(R.string.stop_action), pi(ACTION_STOP, 1))
            }
            else -> {
                b.addAction(0, getString(R.string.start), pi(ACTION_START, 7))
                b.addAction(0, getString(R.string.stop_action), pi(ACTION_STOP, 1))
            }
        }
        return b.build()
    }

    private fun updateNotification() {
        if (!serviceAlive) return
        mediaSession?.setPlaybackState(buildPlaybackState())
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }
}
