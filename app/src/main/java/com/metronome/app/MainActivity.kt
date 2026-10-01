package com.metronome.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var bpmText: TextView
    private lateinit var statusText: TextView
    private lateinit var beatCounter: TextView
    private lateinit var customName: TextView
    private lateinit var slider: Slider
    private lateinit var btnPlay: MaterialButton
    private lateinit var swSound: MaterialSwitch
    private lateinit var swVibrate: MaterialSwitch
    private lateinit var vibStrengthBox: View
    private lateinit var vibStrengthText: TextView
    private lateinit var sliderVibStrength: Slider
    private lateinit var swCustom: MaterialSwitch
    private lateinit var spinner: Spinner
    private lateinit var beatDot: View
    private lateinit var cadenceText: TextView
    private lateinit var alarmStatusText: TextView

    private var updatingUi = false

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAudioPicked(uri)
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
        StepTracker.start()
    }

    override fun onPause() {
        super.onPause()
        if (!MetronomeEngine.running.value) {
            StepTracker.stop()
        }
    }

    private fun bindViews() {
        bpmText = findViewById(R.id.bpmText)
        statusText = findViewById(R.id.statusText)
        beatCounter = findViewById(R.id.beatCounter)
        customName = findViewById(R.id.customName)
        slider = findViewById(R.id.bpmSlider)
        btnPlay = findViewById(R.id.btnPlay)
        swSound = findViewById(R.id.swSound)
        swVibrate = findViewById(R.id.swVibrate)
        vibStrengthBox = findViewById(R.id.vibStrengthBox)
        vibStrengthText = findViewById(R.id.vibStrengthText)
        sliderVibStrength = findViewById(R.id.sliderVibStrength)
        swCustom = findViewById(R.id.swCustom)
        spinner = findViewById(R.id.spinnerTimbre)
        beatDot = findViewById(R.id.beatDot)
        cadenceText = findViewById(R.id.cadenceText)
        alarmStatusText = findViewById(R.id.alarmStatusText)
    }

    private fun wireViews() {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) MetronomeEngine.setBpm(value.roundToInt())
        }

        findViewById<MaterialButton>(R.id.btnMinus5).setOnClickListener {
            MetronomeEngine.setBpm(MetronomeEngine.bpm.value - 5)
        }
        findViewById<MaterialButton>(R.id.btnMinus1).setOnClickListener {
            MetronomeEngine.setBpm(MetronomeEngine.bpm.value - 1)
        }
        findViewById<MaterialButton>(R.id.btnPlus1).setOnClickListener {
            MetronomeEngine.setBpm(MetronomeEngine.bpm.value + 1)
        }
        findViewById<MaterialButton>(R.id.btnPlus5).setOnClickListener {
            MetronomeEngine.setBpm(MetronomeEngine.bpm.value + 5)
        }

        btnPlay.setOnClickListener {
            if (MetronomeEngine.running.value) MetronomeService.stop(this)
            else MetronomeService.start(this)
        }

        swSound.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) MetronomeEngine.setSoundOn(checked)
        }
        swVibrate.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) MetronomeEngine.setVibrateOn(checked)
        }
        sliderVibStrength.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingUi) MetronomeEngine.setVibrateStrength(value.roundToInt())
        }
        swCustom.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) {
                if (SoundBank.customPcm != null || SoundBank.customUriString != null) {
                    MetronomeEngine.setUseCustom(true)
                } else {
                    swCustom.isChecked = false
                    pickAudio.launch(arrayOf("audio/*"))
                }
            } else {
                MetronomeEngine.setUseCustom(false)
            }
        }

        findViewById<MaterialButton>(R.id.btnPick).setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SoundBank.BUILT_IN
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!updatingUi) MetronomeEngine.setTimbre(pos)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

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

    private fun onAudioPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        lifecycleScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                SoundBank.decodeCustom(this@MainActivity, uri)
            }
            if (pcm == null) {
                Toast.makeText(this@MainActivity, "无法解码该音频文件", Toast.LENGTH_LONG).show()
                return@launch
            }
            SoundBank.customPcm = pcm
            val name = SoundBank.displayName(this@MainActivity, uri)
                ?: uri.lastPathSegment ?: "自定义音频"
            MetronomeEngine.setCustomSound(name, uri.toString())
            MetronomeEngine.setUseCustom(true)
            Toast.makeText(
                this@MainActivity,
                "已加载：$name（取前 ${SoundBank.MAX_CUSTOM_SECONDS.toInt()} 秒）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MetronomeEngine.bpm.collect { b ->
                        updatingUi = true
                        bpmText.text = if (b == 0) "停止" else b.toString()
                        if (slider.value != b.toFloat()) slider.value = b.toFloat()
                        updatingUi = false
                        updateStatus()
                    }
                }
                launch {
                    MetronomeEngine.running.collect { running ->
                        btnPlay.text = getString(if (running) R.string.stop else R.string.start)
                        updateStatus()
                    }
                }
                launch {
                    MetronomeEngine.clockRunning.collect { updateStatus() }
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
                    MetronomeEngine.useCustomSound.collect { v ->
                        updatingUi = true
                        swCustom.isChecked = v
                        spinner.isEnabled = !v
                        spinner.alpha = if (v) 0.4f else 1f
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.timbreIndex.collect { idx ->
                        updatingUi = true
                        if (spinner.selectedItemPosition != idx) spinner.setSelection(idx, false)
                        updatingUi = false
                    }
                }
                launch {
                    MetronomeEngine.customSoundName.collect { name ->
                        customName.text = name ?: ""
                    }
                }
                launch {
                    StepTracker.currentCadence.collect { spm ->
                        cadenceText.text = if (spm == 0) "实时步频: 0 SPM" else "实时步频: $spm SPM"
                    }
                }
                launch {
                    StepTracker.isSlowForLongTime.collect { updateAlarmStatus() }
                }
                launch {
                    StepTracker.alarmProgressSec.collect { updateAlarmStatus() }
                }
                launch {
                    MetronomeEngine.running.collect { updateAlarmStatus() }
                }
                launch {
                    MetronomeEngine.bpm.collect { updateAlarmStatus() }
                }
            }
        }
    }

    private fun updateAlarmStatus() {
        val target = MetronomeEngine.bpm.value
        val isSlow = StepTracker.isSlowForLongTime.value
        val sec = StepTracker.alarmProgressSec.value
        val running = MetronomeEngine.running.value

        if (!running || target <= 0) {
            alarmStatusText.text = "就绪"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        } else if (isSlow) {
            alarmStatusText.text = "⚠️ 偏慢报警中 (长音)"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        } else if (sec > 0) {
            alarmStatusText.text = "偏慢警告 (${sec}s/5s)"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        } else {
            alarmStatusText.text = "步频匹配"
            alarmStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        }
    }

    private fun updateStatus() {
        statusText.text = when {
            !MetronomeEngine.running.value -> getString(R.string.status_idle)
            !MetronomeEngine.clockRunning.value -> getString(R.string.status_paused)
            else -> getString(R.string.status_running) + " · " + MetronomeEngine.bpm.value + " BPM"
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
