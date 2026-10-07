package com.metronome.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.metronome.app.core.AlarmStyle
import com.metronome.app.core.BlockRenderer
import com.metronome.app.core.DetectorCore
import com.metronome.app.core.LoopSpec
import com.metronome.app.core.SessionStateMachine
import com.metronome.app.core.TrainingSegment
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // ------------------------------------------------------------ 视图
    private lateinit var bpmText: TextView
    private lateinit var statusText: TextView
    private lateinit var sessionInfo: TextView
    private lateinit var beatCounter: TextView
    private lateinit var slider: Slider
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var swSound: MaterialSwitch
    private lateinit var beatVolumeText: TextView
    private lateinit var sliderBeatVolume: Slider
    private lateinit var swVibrate: MaterialSwitch
    private lateinit var vibStrengthBox: View
    private lateinit var vibStrengthText: TextView
    private lateinit var sliderVibStrength: Slider
    private lateinit var spinLeft: Spinner
    private lateinit var spinRight: Spinner
    private lateinit var swLCustom: MaterialSwitch
    private lateinit var swRCustom: MaterialSwitch
    private lateinit var customNameL: TextView
    private lateinit var customNameR: TextView
    private lateinit var spinChannel: Spinner
    private lateinit var swDetect: MaterialSwitch
    private lateinit var swAlarm: MaterialSwitch
    private lateinit var alarmBox: View
    private lateinit var spinAlarmStyle: Spinner
    private lateinit var alarmAfterText: TextView
    private lateinit var sliderAlarmAfter: Slider
    private lateinit var slowMarginText: TextView
    private lateinit var sliderSlowMargin: Slider
    private lateinit var recoverText: TextView
    private lateinit var sliderRecover: Slider
    private lateinit var repeatText: TextView
    private lateinit var sliderRepeat: Slider
    private lateinit var swTimer: MaterialSwitch
    private lateinit var timerMinutes: EditText
    private lateinit var prepareSeconds: EditText
    private lateinit var swSegments: MaterialSwitch
    private lateinit var btnAddSeg: MaterialButton
    private lateinit var segList: LinearLayout
    private lateinit var loopBox: View
    private lateinit var loopFrom: EditText
    private lateinit var loopTo: EditText
    private lateinit var loopRounds: EditText
    private lateinit var planSummary: TextView
    private lateinit var spinPreset: Spinner
    private lateinit var btnPresetSave: MaterialButton
    private lateinit var btnPresetApply: MaterialButton
    private lateinit var btnPresetUpdate: MaterialButton
    private lateinit var btnPresetDelete: MaterialButton
    private lateinit var beatDot: View
    private lateinit var cadenceText: TextView
    private lateinit var alarmStatusText: TextView

    private var updatingUi = false

    // 分段编辑草稿（编辑态与运行快照分离：会话启动时取快照，运行中编辑不影响）
    private data class SegDraft(var name: String, var dur: String, var spm: String)

    private val segDrafts = mutableListOf<SegDraft>()

    private var pendingFoot: BlockRenderer.Foot = BlockRenderer.Foot.LEFT

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAudioPicked(pendingFoot, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MetronomeEngine.init(this)
        StepTracker.init(this)
        setContentView(R.layout.activity_main)
        bindViews()
        wireViews()
        requestNotificationPermissionIfNeeded()
        observe()
    }

    override fun onResume() {
        super.onResume()
        StepTracker.setActivityVisible(true)
    }

    override fun onPause() {
        super.onPause()
        StepTracker.setActivityVisible(false)
    }

    private fun bindViews() {
        bpmText = findViewById(R.id.bpmText)
        statusText = findViewById(R.id.statusText)
        sessionInfo = findViewById(R.id.sessionInfo)
        beatCounter = findViewById(R.id.beatCounter)
        slider = findViewById(R.id.bpmSlider)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        swSound = findViewById(R.id.swSound)
        beatVolumeText = findViewById(R.id.beatVolumeText)
        sliderBeatVolume = findViewById(R.id.sliderBeatVolume)
        swVibrate = findViewById(R.id.swVibrate)
        vibStrengthBox = findViewById(R.id.vibStrengthBox)
        vibStrengthText = findViewById(R.id.vibStrengthText)
        sliderVibStrength = findViewById(R.id.sliderVibStrength)
        spinLeft = findViewById(R.id.spinLeft)
        spinRight = findViewById(R.id.spinRight)
        swLCustom = findViewById(R.id.swLCustom)
        swRCustom = findViewById(R.id.swRCustom)
        customNameL = findViewById(R.id.customNameL)
        customNameR = findViewById(R.id.customNameR)
        spinChannel = findViewById(R.id.spinChannel)
        swDetect = findViewById(R.id.swDetect)
        swAlarm = findViewById(R.id.swAlarm)
        alarmBox = findViewById(R.id.alarmBox)
        spinAlarmStyle = findViewById(R.id.spinAlarmStyle)
        alarmAfterText = findViewById(R.id.alarmAfterText)
        sliderAlarmAfter = findViewById(R.id.sliderAlarmAfter)
        slowMarginText = findViewById(R.id.slowMarginText)
        sliderSlowMargin = findViewById(R.id.sliderSlowMargin)
        recoverText = findViewById(R.id.recoverText)
        sliderRecover = findViewById(R.id.sliderRecover)
        repeatText = findViewById(R.id.repeatText)
        sliderRepeat = findViewById(R.id.sliderRepeat)
        swTimer = findViewById(R.id.swTimer)
        timerMinutes = findViewById(R.id.timerMinutes)
        prepareSeconds = findViewById(R.id.prepareSeconds)
        swSegments = findViewById(R.id.swSegments)
        btnAddSeg = findViewById(R.id.btnAddSeg)
        segList = findViewById(R.id.segList)
        loopBox = findViewById(R.id.loopBox)
        loopFrom = findViewById(R.id.loopFrom)
        loopTo = findViewById(R.id.loopTo)
        loopRounds = findViewById(R.id.loopRounds)
        planSummary = findViewById(R.id.planSummary)
        spinPreset = findViewById(R.id.spinPreset)
        btnPresetSave = findViewById(R.id.btnPresetSave)
        btnPresetApply = findViewById(R.id.btnPresetApply)
        btnPresetUpdate = findViewById(R.id.btnPresetUpdate)
        btnPresetDelete = findViewById(R.id.btnPresetDelete)
        beatDot = findViewById(R.id.beatDot)
        cadenceText = findViewById(R.id.cadenceText)
        alarmStatusText = findViewById(R.id.alarmStatusText)
    }

    // ------------------------------------------------------------ 交互

    private fun wireViews() {
        // 目标步频：直接输入（带校验）
        bpmText.setOnClickListener { showSpmInputDialog() }
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) MetronomeEngine.setTargetSpm(value.roundToInt())
        }
        findViewById<MaterialButton>(R.id.btnMinus5).setOnClickListener {
            MetronomeEngine.setTargetSpm(MetronomeEngine.targetSpm.value - 5)
        }
        findViewById<MaterialButton>(R.id.btnMinus1).setOnClickListener {
            MetronomeEngine.setTargetSpm(MetronomeEngine.targetSpm.value - 1)
        }
        findViewById<MaterialButton>(R.id.btnPlus1).setOnClickListener {
            MetronomeEngine.setTargetSpm(MetronomeEngine.targetSpm.value + 1)
        }
        findViewById<MaterialButton>(R.id.btnPlus5).setOnClickListener {
            MetronomeEngine.setTargetSpm(MetronomeEngine.targetSpm.value + 5)
        }

        // 会话控制：开始 / 暂停 / 继续 与 停止 是不同动作
        btnPlay.setOnClickListener {
            when (MetronomeEngine.sessionState.value) {
                SessionStateMachine.State.RUNNING,
                SessionStateMachine.State.PREPARING -> MetronomeService.pause(this)
                SessionStateMachine.State.PAUSED -> MetronomeService.resume(this)
                else -> MetronomeService.start(this)   // IDLE / FINISHED / ERROR：新会话
            }
        }
        btnStop.setOnClickListener { MetronomeService.stop(this) }

        swSound.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) MetronomeEngine.setSoundOn(checked)
        }
        sliderBeatVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) MetronomeEngine.setBeatVolume(value.roundToInt())
        }
        swVibrate.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) MetronomeEngine.setVibrateOn(checked)
        }
        sliderVibStrength.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) MetronomeEngine.setVibrateStrength(value.roundToInt())
        }

        // 双脚音色与自定义音频
        fun timbreAdapter() = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SoundBank.BUILT_IN
        )
        spinLeft.adapter = timbreAdapter()
        spinRight.adapter = timbreAdapter()
        spinLeft.onItemSelectedListener = spinnerGuard { MetronomeEngine.setLeftTimbre(it) }
        spinRight.onItemSelectedListener = spinnerGuard { MetronomeEngine.setRightTimbre(it) }

        swLCustom.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked && MetronomeEngine.leftCustom.value == null) {
                // 没有可用自定义资源 → 引导导入，不开空开关
                swLCustom.isChecked = false
                pendingFoot = BlockRenderer.Foot.LEFT
                pickAudio.launch(arrayOf("audio/*"))
            } else {
                MetronomeEngine.setLeftUseCustom(checked)
            }
        }
        swRCustom.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked && MetronomeEngine.rightCustom.value == null) {
                swRCustom.isChecked = false
                pendingFoot = BlockRenderer.Foot.RIGHT
                pickAudio.launch(arrayOf("audio/*"))
            } else {
                MetronomeEngine.setRightUseCustom(checked)
            }
        }
        findViewById<MaterialButton>(R.id.btnPickL).setOnClickListener {
            pendingFoot = BlockRenderer.Foot.LEFT
            pickAudio.launch(arrayOf("audio/*"))
        }
        findViewById<MaterialButton>(R.id.btnPickR).setOnClickListener {
            pendingFoot = BlockRenderer.Foot.RIGHT
            pickAudio.launch(arrayOf("audio/*"))
        }

        // 声道模式
        spinChannel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.chan_center), getString(R.string.chan_alternate))
        )
        spinChannel.onItemSelectedListener = spinnerGuard { pos ->
            MetronomeEngine.setChannelMode(
                if (pos == 1) BlockRenderer.ChannelMode.ALTERNATE_LR
                else BlockRenderer.ChannelMode.CENTER
            )
        }

        // 试听：与正式播放同一套音色处理与音量逻辑；不改会话、不加拍
        findViewById<MaterialButton>(R.id.btnPreviewL).setOnClickListener {
            PreviewManager.preview(this, BlockRenderer.Foot.LEFT)
        }
        findViewById<MaterialButton>(R.id.btnPreviewR).setOnClickListener {
            PreviewManager.preview(this, BlockRenderer.Foot.RIGHT)
        }

        // 检测 / 报警
        swDetect.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) MetronomeEngine.setDetectionEnabled(checked)
        }
        swAlarm.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) MetronomeEngine.setAlarmEnabled(checked)
        }
        spinAlarmStyle.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.alarm_style_overlay), getString(R.string.alarm_style_long))
        )
        spinAlarmStyle.onItemSelectedListener = spinnerGuard { pos ->
            if (!updatingUi) {
                MetronomeEngine.setAlarmConfig(
                    MetronomeEngine.alarmConfig.value.copy(
                        style = if (pos == 1) AlarmStyle.LONG_TONE else AlarmStyle.OVERLAY
                    )
                )
            }
        }
        sliderAlarmAfter.addOnChangeListener { _, v, fromUser ->
            if (fromUser && !updatingUi) {
                MetronomeEngine.setAlarmConfig(
                    MetronomeEngine.alarmConfig.value.copy(alarmAfterSec = v.roundToInt())
                )
            }
        }
        sliderSlowMargin.addOnChangeListener { _, v, fromUser ->
            if (fromUser && !updatingUi) {
                MetronomeEngine.setAlarmConfig(
                    MetronomeEngine.alarmConfig.value.copy(slowMarginSpm = v.roundToInt())
                )
            }
        }
        sliderRecover.addOnChangeListener { _, v, fromUser ->
            if (fromUser && !updatingUi) {
                MetronomeEngine.setAlarmConfig(
                    MetronomeEngine.alarmConfig.value.copy(recoverMs = (v * 1000).toLong())
                )
            }
        }
        sliderRepeat.addOnChangeListener { _, v, fromUser ->
            if (fromUser && !updatingUi) {
                MetronomeEngine.setAlarmConfig(
                    MetronomeEngine.alarmConfig.value.copy(repeatSec = v.roundToInt())
                )
            }
        }

        // 训练配置
        swTimer.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) updateTraining { it.copy(timerEnabled = checked) }
        }
        timerMinutes.setOnEditorActionListener { _, _, _ -> true }
        timerMinutes.addTextChangedListener(simpleWatcher {
            if (!updatingUi) updateTraining { cfg ->
                val min = it.toLongOrNull() ?: return@updateTraining cfg
                cfg.copy(totalDurationSec = (min * 60).coerceIn(0, 12 * 3600).toInt())
            }
        })
        prepareSeconds.addTextChangedListener(simpleWatcher {
            if (!updatingUi) updateTraining { cfg ->
                val s = it.toIntOrNull() ?: return@updateTraining cfg
                cfg.copy(prepareCountdownSec = s.coerceIn(0, 60))
            }
        })
        swSegments.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                if (checked && segDrafts.isEmpty()) {
                    segDrafts += SegDraft("段1", "60", MetronomeEngine.targetSpm.value.toString())
                    segDrafts += SegDraft("段2", "60", MetronomeEngine.targetSpm.value.toString())
                }
                renderSegRows()
                updateTraining { it.copy(segments = draftsToSegments(), loop = draftsToLoop()) }
            }
        }
        btnAddSeg.setOnClickListener {
            if (segDrafts.size >= 16) {
                Toast.makeText(this, "段数已达上限 16", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            segDrafts += SegDraft("段${segDrafts.size + 1}", "60", "120")
            renderSegRows()
            updateTraining { it.copy(segments = draftsToSegments(), loop = draftsToLoop()) }
        }
        loopFrom.addTextChangedListener(simpleWatcher {
            if (!updatingUi) updateTraining { it.copy(loop = draftsToLoop()) }
        })
        loopTo.addTextChangedListener(simpleWatcher {
            if (!updatingUi) updateTraining { it.copy(loop = draftsToLoop()) }
        })
        loopRounds.addTextChangedListener(simpleWatcher {
            if (!updatingUi) updateTraining { it.copy(loop = draftsToLoop()) }
        })

        // 预设
        btnPresetSave.setOnClickListener { showPresetSaveDialog() }
        btnPresetApply.setOnClickListener {
            selectedPreset()?.let { p ->
                MetronomeEngine.applyPreset(p)
                restorePresetAudio(p)
                Toast.makeText(this, "已应用「${p.name}」（不会自动开始训练）", Toast.LENGTH_SHORT).show()
            }
        }
        btnPresetUpdate.setOnClickListener {
            selectedPreset()?.let { p ->
                MetronomeEngine.upsertPreset(MetronomeEngine.currentAsPreset(p.id, p.name))
                Toast.makeText(this, "已用当前设置更新「${p.name}」", Toast.LENGTH_SHORT).show()
            }
        }
        btnPresetDelete.setOnClickListener {
            selectedPreset()?.let { p ->
                MetronomeEngine.deletePreset(p.id)
                Toast.makeText(this, "已删除「${p.name}」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun spinnerGuard(onSelected: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!updatingUi) onSelected(pos)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    private fun simpleWatcher(block: (String) -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                block(s?.toString() ?: "")
            }
        }

    private fun draftsToSegments(): List<TrainingSegment> = segDrafts.mapNotNull { d ->
        val dur = d.dur.toIntOrNull() ?: return@mapNotNull null
        val spm = d.spm.toIntOrNull() ?: return@mapNotNull null
        TrainingSegment(d.name, dur.coerceIn(1, 4 * 3600), spm.coerceIn(20, 200))
    }

    private fun draftsToLoop(): LoopSpec? {
        val s = loopFrom.text.toString().toIntOrNull() ?: return null
        val e = loopTo.text.toString().toIntOrNull() ?: return null
        val r = loopRounds.text.toString().toIntOrNull() ?: return null
        val n = segDrafts.size
        if (n == 0 || r < 1) return null
        val s0 = (s - 1).coerceIn(0, n - 1)
        val e0 = (e - 1).coerceIn(s0, n - 1)
        return LoopSpec(s0, e0, r)
    }

    private fun updateTraining(transform: (com.metronome.app.core.TrainingConfig) -> com.metronome.app.core.TrainingConfig) {
        val cfg = transform(MetronomeEngine.trainingConfig.value)
        MetronomeEngine.setTrainingConfig(cfg)
        showPlanSummary(cfg)
    }

    private fun showPlanSummary(cfg: com.metronome.app.core.TrainingConfig) {
        val errs = cfg.validate()
        val text = when {
            errs.isNotEmpty() -> "⚠ " + errs.joinToString("；")
            cfg.segmentMode -> {
                val natural = cfg.naturalTotalMs() / 1000
                val cap = cfg.effectiveCapMs()?.div(1000)
                buildString {
                    append("计划总时长 ${natural}s")
                    cap?.let { append(" · 定时上限 ${it}s（到点即止）") }
                    cfg.loop?.let {
                        append(" · 循环段 ${it.startIndex + 1}-${it.endIndexInclusive + 1} × ${it.rounds} 轮")
                    }
                }
            }
            cfg.timerValid -> "总时长 ${cfg.totalDurationSec}s，到点结束"
            else -> "普通正计时（不自动结束）"
        }
        planSummary.text = text
    }

    // ------------------------------------------------------------ 分段行渲染

    private fun renderSegRows() {
        val segMode = MetronomeEngine.trainingConfig.value.segmentMode
        segList.visibility = if (segMode) View.VISIBLE else View.GONE
        loopBox.visibility = if (segMode && segDrafts.size >= 1) View.VISIBLE else View.GONE
        if (!segMode) return
        segList.removeAllViews()
        segDrafts.forEachIndexed { idx, d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }
            val label = TextView(this).apply {
                text = "${idx + 1}."
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }
            val dur = EditText(this).apply {
                hint = getString(R.string.seg_duration)
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(d.dur)
                textSize = 14f
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(simpleWatcher { t ->
                    d.dur = t
                    if (!updatingUi) updateTraining { it.copy(segments = draftsToSegments()) }
                })
            }
            val spm = EditText(this).apply {
                hint = getString(R.string.seg_spm)
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(d.spm)
                textSize = 14f
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(simpleWatcher { t ->
                    d.spm = t
                    if (!updatingUi) updateTraining { it.copy(segments = draftsToSegments()) }
                })
            }
            val del = MaterialButton(this, null, attr_materialOutlined).apply {
                text = "✕"
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                setOnClickListener {
                    segDrafts.removeAt(idx)
                    segDrafts.forEachIndexed { i, dd -> dd.name = "段${i + 1}" }
                    renderSegRows()
                    updateTraining { it.copy(segments = draftsToSegments(), loop = draftsToLoop()) }
                }
            }
            row.addView(label)
            row.addView(dur)
            row.addView(spm)
            if (segDrafts.size > 1) row.addView(del)
            segList.addView(row)
        }
    }

    private val attr_materialOutlined: Int
        get() = com.google.android.material.R.attr.materialButtonOutlinedStyle

    // ------------------------------------------------------------ 对话框

    private fun showSpmInputDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(MetronomeEngine.targetSpm.value.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.dialog_input_spm,
                    MetronomeEngine.SPM_MIN, MetronomeEngine.SPM_MAX
                )
            )
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < MetronomeEngine.SPM_MIN || v > MetronomeEngine.SPM_MAX) {
                    Toast.makeText(this, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                } else {
                    MetronomeEngine.setTargetSpm(v)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPresetSaveDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.preset_name_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.preset_save)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "预设" }
                MetronomeEngine.upsertPreset(
                    MetronomeEngine.currentAsPreset(UUID.randomUUID().toString(), name)
                )
                Toast.makeText(this, "已保存「$name」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectedPreset(): com.metronome.app.core.Preset? {
        val list = MetronomeEngine.presets.value
        val pos = spinPreset.selectedItemPosition
        if (list.isEmpty() || pos !in list.indices) {
            Toast.makeText(this, "没有可用的预设", Toast.LENGTH_SHORT).show()
            return null
        }
        return list[pos]
    }

    /** 应用预设后恢复其引用的自定义音频 PCM；缺失则提示并回退内置 */
    private fun restorePresetAudio(p: com.metronome.app.core.Preset) {
        lifecycleScope.launch {
            suspend fun ensure(
                foot: BlockRenderer.Foot,
                asset: com.metronome.app.core.AudioAsset?,
            ): Boolean {
                if (asset == null) return false
                val pcm = AudioImporter.loadAssetPcm(this@MainActivity, foot, asset)
                if (pcm == null) return false
                if (foot == BlockRenderer.Foot.LEFT) SoundBank.leftPcm = pcm
                else SoundBank.rightPcm = pcm
                return true
            }
            val lOk = ensure(BlockRenderer.Foot.LEFT, p.leftCustom)
            val rOk = ensure(BlockRenderer.Foot.RIGHT, p.rightCustom)
            if (p.leftUseCustom && !lOk) {
                MetronomeEngine.setLeftUseCustom(false)
                Toast.makeText(this@MainActivity, "左脚自定义音频缺失，已回退内置音色", Toast.LENGTH_LONG).show()
            }
            if (p.rightUseCustom && !rOk) {
                MetronomeEngine.setRightUseCustom(false)
                Toast.makeText(this@MainActivity, "右脚自定义音频缺失，已回退内置音色", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ 导入

    private fun onAudioPicked(foot: BlockRenderer.Foot, uri: Uri) {
        val name = SoundBank.displayName(this, uri)
        AudioImporter.import(this, foot, uri, name)
    }

    // ------------------------------------------------------------ 权限

    private fun requestNotificationPermissionIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 100)
        }
    }

    // ------------------------------------------------------------ 观察

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MetronomeEngine.targetSpm.collect { v ->
                        updatingUi = true
                        bpmText.text = v.toString()
                        val sv = v.coerceIn(30, 200)
                        if (slider.value != sv.toFloat()) slider.value = sv.toFloat()
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.sessionState.collect { st ->
                        updatingUi = true
                        btnPlay.text = when (st) {
                            SessionStateMachine.State.RUNNING,
                            SessionStateMachine.State.PREPARING -> getString(R.string.pause)
                            SessionStateMachine.State.PAUSED -> getString(R.string.resume)
                            else -> getString(R.string.start)
                        }
                        btnStop.isEnabled = st != SessionStateMachine.State.IDLE ||
                            MetronomeEngine.running.value
                        updatingUi = false
                        updateStatus(st)
                    }
                }
                launch {
                    MetronomeEngine.sessionPos.collect { updateSessionInfo(it) }
                }
                launch {
                    MetronomeEngine.beatCount.collect { n ->
                        beatCounter.text =
                            if (n == 0L) getString(R.string.beats_zero)
                            else getString(R.string.beats_fmt, n)
                        if (n > 0) flashDot()
                    }
                }
                launch {
                    MetronomeEngine.soundOn.collect { v ->
                        updatingUi = true
                        swSound.isChecked = v
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.beatVolume.collect { v ->
                        updatingUi = true
                        if (sliderBeatVolume.value != v.toFloat()) sliderBeatVolume.value = v.toFloat()
                        beatVolumeText.text = "$v%"
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.vibrateOn.collect { v ->
                        updatingUi = true
                        swVibrate.isChecked = v
                        vibStrengthBox.alpha = if (v) 1f else 0.4f
                        sliderVibStrength.isEnabled = v
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.vibrateStrength.collect { s ->
                        updatingUi = true
                        if (sliderVibStrength.value != s.toFloat()) {
                            sliderVibStrength.value = s.toFloat()
                        }
                        vibStrengthText.text = if (s >= 100) getString(R.string.vib_strength_max)
                        else getString(R.string.vib_strength_fmt, s)
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.channelMode.collect { m ->
                        updatingUi = true
                        val pos = if (m == BlockRenderer.ChannelMode.ALTERNATE_LR) 1 else 0
                        if (spinChannel.selectedItemPosition != pos) spinChannel.setSelection(pos, false)
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.leftTimbre.collect { idx ->
                        updatingUi = true
                        if (spinLeft.selectedItemPosition != idx) spinLeft.setSelection(idx, false)
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.rightTimbre.collect { idx ->
                        updatingUi = true
                        if (spinRight.selectedItemPosition != idx) spinRight.setSelection(idx, false)
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.leftCustom.collect { a ->
                        customNameL.text = a?.displayName ?: ""
                        if (a == null && MetronomeEngine.leftUseCustom.value) {
                            MetronomeEngine.setLeftUseCustom(false)
                        }
                    }
                }
                launch {
                    MetronomeEngine.rightCustom.collect { a ->
                        customNameR.text = a?.displayName ?: ""
                        if (a == null && MetronomeEngine.rightUseCustom.value) {
                            MetronomeEngine.setRightUseCustom(false)
                        }
                    }
                }
                launch {
                    MetronomeEngine.leftLoad.collect { st ->
                        when (st.status) {
                            MetronomeEngine.LoadStatus.LOADING -> customNameL.text = "加载中…"
                            MetronomeEngine.LoadStatus.FAILED -> {
                                customNameL.text = "导入失败"
                                Toast.makeText(this@MainActivity, st.message ?: "", Toast.LENGTH_LONG).show()
                            }
                            MetronomeEngine.LoadStatus.READY ->
                                Toast.makeText(this@MainActivity, st.message ?: "", Toast.LENGTH_SHORT).show()
                            else -> {}
                        }
                    }
                }
                launch {
                    MetronomeEngine.rightLoad.collect { st ->
                        when (st.status) {
                            MetronomeEngine.LoadStatus.LOADING -> customNameR.text = "加载中…"
                            MetronomeEngine.LoadStatus.FAILED -> {
                                customNameR.text = "导入失败"
                                Toast.makeText(this@MainActivity, st.message ?: "", Toast.LENGTH_LONG).show()
                            }
                            MetronomeEngine.LoadStatus.READY ->
                                Toast.makeText(this@MainActivity, st.message ?: "", Toast.LENGTH_SHORT).show()
                            else -> {}
                        }
                    }
                }
                launch {
                    MetronomeEngine.leftUseCustom.collect { v ->
                        updatingUi = true
                        swLCustom.isChecked = v
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.rightUseCustom.collect { v ->
                        updatingUi = true
                        swRCustom.isChecked = v
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.detectionEnabled.collect { v ->
                        updatingUi = true
                        swDetect.isChecked = v
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.alarmEnabled.collect { v ->
                        updatingUi = true
                        swAlarm.isChecked = v
                        alarmBox.alpha = if (v) 1f else 0.4f
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.alarmConfig.collect { c ->
                        updatingUi = true
                        val stylePos = if (c.style == AlarmStyle.LONG_TONE) 1 else 0
                        if (spinAlarmStyle.selectedItemPosition != stylePos) {
                            spinAlarmStyle.setSelection(stylePos, false)
                        }
                        if (sliderAlarmAfter.value != c.alarmAfterSec.toFloat()) {
                            sliderAlarmAfter.value = c.alarmAfterSec.toFloat()
                        }
                        if (sliderSlowMargin.value != c.slowMarginSpm.toFloat()) {
                            sliderSlowMargin.value = c.slowMarginSpm.toFloat()
                        }
                        val rec = (c.recoverMs / 1000f).coerceIn(0.2f, 3f)
                        if (abs(sliderRecover.value - rec) > 0.049f) sliderRecover.value = rec
                        if (sliderRepeat.value != c.repeatSec.toFloat()) {
                            sliderRepeat.value = c.repeatSec.toFloat()
                        }
                        alarmAfterText.text = getString(R.string.alarm_after) + "：${c.alarmAfterSec}s"
                        slowMarginText.text =
                            getString(R.string.alarm_slow_margin) + "：-${c.slowMarginSpm} SPM"
                        recoverText.text =
                            getString(R.string.alarm_recover) + "：${c.recoverMs / 1000.0}s"
                        repeatText.text = getString(R.string.alarm_repeat) + "：${c.repeatSec}s"
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.trainingConfig.collect { c ->
                        updatingUi = true
                        swTimer.isChecked = c.timerEnabled
                        if (timerMinutes.text.toString()
                            != (c.totalDurationSec / 60).toString()
                        ) timerMinutes.setText((c.totalDurationSec / 60).toString())
                        if (prepareSeconds.text.toString() != c.prepareCountdownSec.toString()) {
                            prepareSeconds.setText(c.prepareCountdownSec.toString())
                        }
                        swSegments.isChecked = c.segmentMode
                        // 草稿与配置同步（首启/应用预设后）
                        if (segDrafts.size != c.segments.size) {
                            segDrafts.clear()
                            c.segments.forEachIndexed { i, s ->
                                segDrafts += SegDraft("段${i + 1}", "${s.durationSec}", "${s.targetSpm}")
                            }
                            renderSegRows()
                        }
                        c.loop?.let { l ->
                            if (loopFrom.text.toString() != (l.startIndex + 1).toString()) {
                                loopFrom.setText((l.startIndex + 1).toString())
                            }
                            if (loopTo.text.toString() != (l.endIndexInclusive + 1).toString()) {
                                loopTo.setText((l.endIndexInclusive + 1).toString())
                            }
                            if (loopRounds.text.toString() != l.rounds.toString()) {
                                loopRounds.setText(l.rounds.toString())
                            }
                        }
                        showPlanSummary(c)
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.presets.collect { list ->
                        val names = if (list.isEmpty()) listOf("（无预设）") else list.map { it.name }
                        updatingUi = true
                        spinPreset.adapter = ArrayAdapter(
                            this@MainActivity,
                            android.R.layout.simple_spinner_dropdown_item, names
                        )
                        spinPreset.isEnabled = list.isNotEmpty()
                        btnPresetApply.isEnabled = list.isNotEmpty()
                        btnPresetUpdate.isEnabled = list.isNotEmpty()
                        btnPresetDelete.isEnabled = list.isNotEmpty()
                        updatingUi = false
                    }
                }
                launch {
                    // 统一检测快照：状态/数值任一变化都会刷新显示
                    StepTracker.snapshot.collect { updateCadenceUi(it) }
                }
                launch {
                    MetronomeEngine.presetAppliedTick.collect { refreshAllFromEngine() }
                }
            }
        }
    }

    private fun refreshAllFromEngine() {
        // 应用预设后各 Flow 会自动推送新值；这里只补分段草稿的显示同步
        val cfg = MetronomeEngine.trainingConfig.value
        segDrafts.clear()
        cfg.segments.forEachIndexed { i, s -> segDrafts += SegDraft("段${i + 1}", "${s.durationSec}", "${s.targetSpm}") }
        renderSegRows()
    }

    private fun updateStatus(st: SessionStateMachine.State) {
        statusText.text = when (st) {
            SessionStateMachine.State.PREPARING -> getString(R.string.status_preparing)
            SessionStateMachine.State.RUNNING -> getString(R.string.status_running)
            SessionStateMachine.State.PAUSED -> getString(R.string.status_paused)
            SessionStateMachine.State.FINISHED -> getString(R.string.status_finished)
            SessionStateMachine.State.ERROR -> "音频错误"
            else -> getString(R.string.status_idle)
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun updateSessionInfo(pos: com.metronome.app.core.TrainingTimeline.Position?) {
        sessionInfo.text = when {
            pos == null -> ""
            pos.phase == com.metronome.app.core.TrainingTimeline.Phase.PREPARE ->
                "准备倒计时 ${pos.segmentRemainingMs / 1000}s"
            pos.finished ->
                buildString {
                    append("完成 · 有效训练 ")
                    append(fmtMs(pos.trainingElapsedMs))
                    if (pos.finishedByCap) append("（总时长到点）")
                }
            else -> buildString {
                append("已用 ")
                append(fmtMs(pos.trainingElapsedMs))
                pos.totalRemainingMs?.let { append(" · 总剩余 ").append(fmtMs(it)) }
                if (pos.segmentIndex >= 0) {
                    append("\n段${pos.segmentIndex + 1}")
                    if (sessionRounds() > 1) append(" · 第${pos.round}轮")
                    append(" · 段剩 ").append(fmtMs(pos.segmentRemainingMs))
                    if (pos.nextSegmentIndex >= 0) {
                        val next = MetronomeEngine.trainingConfig.value.segments.getOrNull(pos.nextSegmentIndex)
                        next?.let { append(" · 下一段: ${it.targetSpm} SPM") }
                    }
                }
            }
        }
    }

    private fun sessionRounds(): Int =
        MetronomeEngine.trainingConfig.value.loop?.rounds ?: 1

    private fun updateCadenceUi(s: StepTracker.Snapshot) {
        cadenceText.text = when {
            !s.detectionEnabled -> "检测步频：检测已关闭"
            s.availability != StepTracker.Availability.OK ->
                "检测步频：不可用（" + when (s.availability) {
                    StepTracker.Availability.NO_PERMISSION -> "缺活动识别权限"
                    StepTracker.Availability.NO_SENSOR -> "无计步传感器"
                    StepTracker.Availability.REGISTER_FAILED -> "传感器注册失败"
                    else -> "未知"
                } + "）"
            !s.displayValid -> "检测步频: --"
            else -> "检测步频: ${s.displaySpm.roundToInt()} SPM"
        }
        updateAlarmStatus(s)
    }

    /**
     * 报警/匹配文案从统一快照派生（差值 × 阈值），不依赖报警进度是否为 0；
     * 报警进度与匹配状态分开显示。
     */
    private fun updateAlarmStatus(s: StepTracker.Snapshot) {
        if (!s.detectionEnabled) {
            alarmStatusText.text = if (s.alarmEnabled) "报警需开启步频检测" else "检测与报警均关闭"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            return
        }
        if (s.availability != StepTracker.Availability.OK) {
            alarmStatusText.text = "步频检测不可用"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            return
        }
        if (s.alarm) {
            alarmStatusText.text = "⚠️ 偏慢报警中"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            return
        }
        if (s.state == DetectorCore.State.STALE) {
            alarmStatusText.text = "步伐停止/严重掉速"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            return
        }
        if (s.state == DetectorCore.State.WARMING_UP) {
            alarmStatusText.text = if (s.sessionActive) "等待步伐数据…" else "就绪"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            return
        }
        val target = s.targetSpm
        val cfg = MetronomeEngine.alarmConfig.value
        val diff = s.displaySpm - target
        when {
            !s.alarmEnabled -> {
                alarmStatusText.text = "报警关闭 · 检测正常"
                alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
            s.displayValid && diff < -cfg.slowMarginSpm -> {
                val sec = s.progressMs / 1000.0
                alarmStatusText.text = "偏慢 ${abs(diff).roundToInt()} SPM（累计 ${"%.1f".format(sec)}/${cfg.alarmAfterSec}s）"
                alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            }
            s.displayValid && diff < -cfg.fastMarginSpm -> {
                alarmStatusText.text = "略慢 ${abs(diff).roundToInt()} SPM"
                alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
            s.displayValid -> {
                alarmStatusText.text = "步频匹配（${"%+.0f".format(diff)}）"
                alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            }
            else -> {
                alarmStatusText.text = "就绪"
                alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
        }
    }

    private fun flashDot() {
        beatDot.animate().cancel()
        beatDot.scaleX = 1f
        beatDot.scaleY = 1f
        beatDot.animate().scaleX(1.7f).scaleY(1.7f).setDuration(60).withEndAction {
            beatDot.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }.start()
    }
}
