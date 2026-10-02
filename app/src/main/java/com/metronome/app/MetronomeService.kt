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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 前台服务（mediaPlayback）：节拍由 AudioTrack 的 PCM 采样时钟驱动。
 *
 * 计时原理：节拍目标位置以"流内采样帧号"用 double 绝对累加定位
 * （nextBeat += 60 * RATE / BPM），写入时按帧号精确落点混入 PCM，
 * 因此节拍间隔与硬件采样时钟完全一致，不存在 Handler 定时的漂移。
 * BPM=0 时暂停音频轨（整个流静默、不振动）。
 *
 * 振动 / UI 计数不依赖写入时机，而是由 AudioTrack 的 marker 回调在
 * "播放头真正到达该拍"时触发，与听到的声音对齐。
 *
 * 声音混入由 [PcmMixer] 完成：每个 beat 的整段 PCM 作为 voice 跨 block
 * 连续播放，不会被 10ms block 边界截断。
 */
class MetronomeService : Service() {

    companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "metronome"
        const val ACTION_STOP = "com.metronome.app.action.STOP"

        private const val BLOCK_FRAMES = 480   // 每次写入 10ms @48kHz
        private const val LEAD_FRAMES = 4800   // 启动/恢复后第一拍延迟 100ms

        // 掉速长音：相位连续 + 短淡入淡出，进出报警无 click
        private const val ALARM_FREQ = 440.0
        private const val ALARM_GAIN = 0.3
        private const val ALARM_FADE_SEC = 0.004

        private const val TAG = "MetronomeService"

        fun start(context: Context) =
            ContextCompat.startForegroundService(
                context, Intent(context, MetronomeService::class.java)
            )

        fun stop(context: Context) =
            context.stopService(Intent(context, MetronomeService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var track: AudioTrack? = null
    private var clockThread: ClockThread? = null
    private var vibThread: HandlerThread? = null

    @Volatile
    private var serviceAlive = false

    private lateinit var vibHandler: Handler
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null

    /**
     * 向系统声明"媒体正在播放"（音乐类 App 标准做法）。
     * 实测 ColorOS 深夜的睡眠待机会无视 wakelock 挂起音频管线，
     * 但会尊重 MediaSession 的 PLAYING 状态。
     */
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "Metronome").apply {
            setCallback(object : MediaSession.Callback() {})
            setPlaybackState(buildPlaybackState(true))
            isActive = true
        }
    }

    private fun buildPlaybackState(playing: Boolean): PlaybackState =
        PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                0, 1f
            )
            .build()

    private fun setMediaPlaying(playing: Boolean) {
        mediaSession?.setPlaybackState(buildPlaybackState(playing))
    }

    // ---------------------------------------------------------------- 音频焦点
    // 策略（针对"跑步时音乐 + 节拍器共存"设计）：
    //  - 仅振动（soundOn=false）时从不申请焦点；
    //  - 需要声音时申请 GAIN_TRANSIENT_MAY_DUCK（导航提示音同款语义）：
    //    其他音乐 App 压低音量继续播放而不是被暂停，节拍声叠加其上；
    //  - 申请失败或焦点被抢占（来电/其他独占音频）期间静音节拍声音
    //    （时钟、振动、计数不受影响），收到 GAIN 即恢复；
    //  - BPM=0 暂停或服务销毁时主动放弃焦点。
    // 焦点被永久抢占（LOSS）后保持静音直到下一次节拍重启或开关切换，
    // 不自动抢回焦点，避免与正在独占的应用来回拉锯。

    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var audioMutedByFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> audioMutedByFocus = true
            AudioManager.AUDIOFOCUS_GAIN -> audioMutedByFocus = false
        }
    }

    /** 声音是否此刻可出（用户开关 × 焦点状态）；音频线程逐 block 读取 */
    private val playSoundAllowed: Boolean
        get() = MetronomeEngine.soundOn.value && !audioMutedByFocus

    /** 按当前状态申请/维持/放弃焦点；幂等，可在任意线程调用 */
    private fun updateAudioFocus() {
        val need = serviceAlive && MetronomeEngine.clockRunning.value && MetronomeEngine.soundOn.value
        if (!need) {
            abandonAudioFocus()
            audioMutedByFocus = false
            return
        }
        if (audioFocusRequest != null) return
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
            null
        }
        // 非 Granted（Failed/Delayed）期间不出声，等 GAIN 回调再恢复
        audioMutedByFocus = granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { req ->
            try { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) } catch (_: Exception) {}
        }
        audioFocusRequest = null
    }

    /** 锁屏/待机时防止 CPU 休眠导致节拍中断（ColorOS 实测会冻结进程） */
    private fun acquireCpuLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.metronome.app:clock")
        }
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 小时上限兜底，正常由 pause/destroy 释放
    }

    private fun releaseCpuLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MetronomeEngine.init(this)
        StepTracker.init(this)
        StepTracker.start()
        MetronomeEngine.running.value = true
        MetronomeEngine.beatCount.value = 0
        vibrator = getSystemService(Vibrator::class.java)
        vibThread = HandlerThread("metronome-vib").also { it.start() }
        vibHandler = Handler(vibThread!!.looper)

        createChannel()
        setupMediaSession()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        // 上次选过自定义音频则后台重载解码
        val uriStr = SoundBank.customUriString
        if (SoundBank.customPcm == null && uriStr != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    SoundBank.customPcm =
                        SoundBank.decodeCustom(this@MetronomeService, Uri.parse(uriStr))
                    MetronomeEngine.logState("custom-loaded")
                } catch (e: Exception) {
                    Log.w(TAG, "自定义音频加载失败", e)
                }
            }
        }

        track = buildTrack()
        serviceAlive = true
        clockThread = ClockThread().also { it.start() }

        scope.launch {
            combine(
                MetronomeEngine.bpm,
                MetronomeEngine.soundOn,
                MetronomeEngine.vibrateOn,
                MetronomeEngine.vibrateStrength,
                MetronomeEngine.clockRunning
            ) { _, _, _, _, _ -> }.collect {
                updateAudioFocus()
                updateNotification()
            }
        }
        MetronomeEngine.logState("service-created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceAlive = false
        // 先停时钟线程，避免它与随后的 track 释放并发使用 AudioTrack
        clockThread?.let { t ->
            try { t.join(1000) } catch (_: InterruptedException) {}
        }
        clockThread = null
        try { track?.stop() } catch (_: IllegalStateException) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        // 音频轨已释放，不会再有新的 marker 回调投递；
        // 退出振动线程并丢弃队列中未处理的回调，防止其访问已释放的资源
        vibThread?.quitSafely()
        try { vibThread?.join(500) } catch (_: InterruptedException) {}
        vibThread = null
        releaseCpuLock()
        abandonAudioFocus()
        try { mediaSession?.isActive = false } catch (_: Exception) {}
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        StepTracker.stop()
        MetronomeEngine.running.value = false
        MetronomeEngine.clockRunning.value = false
        MetronomeEngine.beatCount.value = 0
        MetronomeEngine.logState("service-destroyed")
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 音频

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            MetronomeEngine.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufBytes)
            .build()
        // marker 回调直接投递到振动线程：回调里先发振动再计数，延迟最小
        at.setPlaybackPositionUpdateListener(beatListener, vibHandler)
        return at
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
                    "beat=%d frame=%d audioMs=%.1f wall=%d vibrate=%b".format(
                        b.num + 1, b.frame, audioMs,
                        SystemClock.elapsedRealtime(), MetronomeEngine.vibrateOn.value
                    )
                )
            }
        }

        override fun onPeriodicNotification(track: AudioTrack?) {}
    }

    /**
     * 按用户设置的强度（1..100%）逐拍触发振动。
     * 支持振幅控制（hasAmplitudeControl，OnePlus 13 属此类）：强度映射为振幅 40..255，
     * 脉宽 70..120ms——100% 时即为硬件能达到的最强单脉冲。
     * 不支持振幅控制的设备振幅恒定，只能用脉宽（70..180ms）以及在高档位
     * 叠加一次间隔 50ms 的双脉冲来增强体感。
     *
     * 必须在振动线程（vibHandler 对应 looper）上调用——marker 回调本身
     * 就运行在该线程，直接振动即可，无需再 post 一次引入额外排队延迟。
     */
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

    // -------------------------------------------------------------- 时钟线程

    private class PendingBeat(val num: Long, val frame: Long)

    private inner class ClockThread : Thread("metronome-clock") {
        val pending = ConcurrentLinkedQueue<PendingBeat>()
        val markerLock = Any()
        val mixer = PcmMixer(BLOCK_FRAMES)
        var markerArmed = false
        // 报警长音的连续相位与增益状态（跨 block 保持，避免相位跳变 click）
        var alarmPhase = 0.0
        var alarmGain = 0.0

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val t = track ?: return
            try {
                t.play()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "audio track play failed", e)
                return
            }
            val block = ShortArray(BLOCK_FRAMES)
            var written = 0L      // 流内已写入到的帧号（流的"末尾"）
            var nextBeat = -1.0   // 下一拍目标帧号（double 绝对定位）
            var beatNum = 0L
            var blockCount = 0
            var soundedSinceLog = false

            while (serviceAlive) {
                val bpmNow = MetronomeEngine.bpm.value
                if (bpmNow <= 0) { // 0 = 停止
                    if (nextBeat >= 0.0) {
                        try { t.pause() } catch (_: IllegalStateException) {}
                        nextBeat = -1.0
                        pending.clear()
                        mixer.clear()
                        synchronized(markerLock) { markerArmed = false }
                        releaseCpuLock()
                        setMediaPlaying(false)
                        MetronomeEngine.clockRunning.value = false
                        updateAudioFocus() // 不再需要声音 → 本线程同步放弃焦点
                        MetronomeEngine.logState("clock-paused(bpm=0)")
                    }
                    try { sleep(20) } catch (_: InterruptedException) { return }
                    continue
                }
                if (nextBeat < 0.0) { // 启动/恢复
                    try { t.play() } catch (e: IllegalStateException) {
                        Log.e(TAG, "audio track resume failed", e)
                        return
                    }
                    nextBeat = (written + LEAD_FRAMES).toDouble()
                    acquireCpuLock()
                    setMediaPlaying(true)
                    MetronomeEngine.clockRunning.value = true
                    updateAudioFocus() // 仅当需要声音时申请焦点；失败期间静音（第一拍前同步完成）
                    MetronomeEngine.logState("clock-start bpm=$bpmNow")
                }

                java.util.Arrays.fill(block, 0)

                val playSound = playSoundAllowed
                val alarmActive = StepTracker.isSlowForLongTime.value

                // 排布本 block 内的节拍：无条件进入 pending（marker 驱动振动与计数），
                // 声音部分整段 PCM 入 mixer，跨 block 连续播放
                var beat = nextBeat
                val framesPerBeat = 60.0 * MetronomeEngine.RATE / bpmNow
                while (beat < written + BLOCK_FRAMES) {
                    val startF = Math.round(beat)
                    if (startF >= written) {
                        if (!alarmActive && playSound) {
                            val pcm = SoundBank.currentSound(
                                MetronomeEngine.timbreIndex.value,
                                MetronomeEngine.useCustomSound.value
                            )
                            mixer.addVoice(pcm, (startF - written).toInt())
                            soundedSinceLog = true
                        }
                        pending.offer(PendingBeat(beatNum, startF))
                        beatNum++
                    }
                    beat += framesPerBeat
                }
                nextBeat = beat

                // 偏慢长音：目标增益 0/ALARM_GAIN 平滑过渡（4ms 淡入淡出），相位连续
                val toneTarget = if (alarmActive && playSound) ALARM_GAIN else 0.0
                if (toneTarget > 0.0 || alarmGain > 0.0) {
                    val fadeStep = ALARM_GAIN / (ALARM_FADE_SEC * MetronomeEngine.RATE)
                    for (i in 0 until BLOCK_FRAMES) {
                        alarmPhase += 2.0 * PI * ALARM_FREQ / MetronomeEngine.RATE
                        if (alarmPhase >= 2.0 * PI) alarmPhase -= 2.0 * PI
                        alarmGain = when {
                            alarmGain < toneTarget -> min(toneTarget, alarmGain + fadeStep)
                            alarmGain > toneTarget -> max(0.0, alarmGain - fadeStep)
                            else -> alarmGain
                        }
                        block[i] = (sin(alarmPhase) * alarmGain * 32700.0)
                            .toInt().coerceIn(-32768, 32767).toShort()
                    }
                }

                mixer.mixInto(block)

                val n = try {
                    t.write(block, 0, BLOCK_FRAMES, AudioTrack.WRITE_BLOCKING)
                } catch (e: Exception) {
                    // onDestroy 释放 track 与本线程 write 并发时可能抛出；退出而非崩溃
                    Log.e(TAG, "audio write exception", e)
                    break
                }
                if (n < 0) {
                    Log.e(TAG, "audio write failed: $n")
                    break
                }
                written += BLOCK_FRAMES

                synchronized(markerLock) {
                    if (!markerArmed) {
                        val head = pending.peek()
                        if (head != null) {
                            t.setNotificationMarkerPosition(head.frame.toInt())
                            markerArmed = true
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    blockCount++
                    if (blockCount % 200 == 0) { // 约每 2 秒记录一次输出电平
                        var acc = 0L
                        for (s in block) acc += s * s
                        val rms = sqrt(acc / BLOCK_FRAMES.toDouble()) / 32768.0
                        Log.i(
                            "MetroSnd", "rms=%.3f sounded=%b sound=%b".format(
                                rms, soundedSinceLog, MetronomeEngine.soundOn.value
                            )
                        )
                        soundedSinceLog = false
                    }
                }
            }
            try { t.stop() } catch (_: IllegalStateException) {}
        }
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val b = MetronomeEngine.bpm.value
        val s = if (MetronomeEngine.soundOn.value) "开" else "关"
        val v = if (MetronomeEngine.vibrateOn.value)
            "开·${MetronomeEngine.vibrateStrength.value}%"
        else "关"
        val contentPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (b == 0) "已停止（BPM=0）" else "$b BPM · 声音:$s · 振动:$v"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentPI)
            .addAction(0, getString(R.string.stop_action), stopPI)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }
}
