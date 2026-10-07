package com.metronome.app

import android.content.Context
import android.net.Uri
import android.util.Log
import com.metronome.app.core.BlockRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 自定义音频导入管线（每只脚独立）：
 *  - 请求序号防竞态：多次快速导入只有最后一次生效，迟到的旧解码结果被丢弃；
 *    切回内置后迟到的解码也不会重新启用自定义；
 *  - 加载中/成功/失败状态通过 [MetronomeEngine.leftLoad/rightLoad] 发布，
 *    失败给出可理解提示，绝不"显示自定义已启用却静默播内置"；
 *  - 解码结果落为 App 私有目录稳定资源（[AudioAssets]），重启后仍可用；
 *  - 检查解码输出（空数据/异常静音）并限制耗时（协程取消 + 帧上限）。
 */
object AudioImporter {
    private const val TAG = "AudioImporter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seq = AtomicLong(0)
    private val jobs = HashMap<String, Job>()

    /**
     * 导入音频到指定脚（LEFT/RIGHT）。pickUri 为 SAF 选择结果。
     * 完成后自动启用该脚自定义。
     */
    fun import(context: Context, foot: BlockRenderer.Foot, uri: Uri, displayName: String?) {
        val app = context.applicationContext
        val key = foot.name
        val mySeq = seq.incrementAndGet()
        jobs[key]?.cancel()
        setLoad(foot, MetronomeEngine.CustomLoadState(MetronomeEngine.LoadStatus.LOADING))
        jobs[key] = scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    SoundBank.decodeCustom(app, uri)
                } catch (e: Exception) {
                    Log.w(TAG, "decode cancelled/failed", e)
                    null
                }
            }
            if (seq.get() != mySeq) return@launch   // 已有更新的导入请求，丢弃
            if (result == null || result.isEmpty()) {
                setLoad(
                    foot, MetronomeEngine.CustomLoadState(
                        MetronomeEngine.LoadStatus.FAILED, "无法解码该音频文件（格式不支持或数据为空）"
                    )
                )
                return@launch
            }
            if (peakLevel(result) < 10) {
                setLoad(
                    foot, MetronomeEngine.CustomLoadState(
                        MetronomeEngine.LoadStatus.FAILED, "该音频几乎是静音，未采用"
                    )
                )
                return@launch
            }
            val id = "aud_" + System.currentTimeMillis().toString(36) + "_" + mySeq.toString(36)
            val name = displayName ?: uri.lastPathSegment ?: "自定义音频"
            val saved = withContext(Dispatchers.IO) { AudioAssets.savePcm(app, id, result) }
            if (!saved) {
                setLoad(
                    foot, MetronomeEngine.CustomLoadState(
                        MetronomeEngine.LoadStatus.FAILED, "保存音频失败"
                    )
                )
                return@launch
            }
            if (seq.get() != mySeq) {
                AudioAssets.delete(app, id)   // 迟到结果：不生效，清理资源
                return@launch
            }
            val asset = com.metronome.app.core.AudioAsset(id, name)
            when (foot) {
                BlockRenderer.Foot.LEFT -> {
                    SoundBank.leftPcm = result
                    MetronomeEngine.setLeftCustom(asset)
                    MetronomeEngine.setLeftUseCustom(true)
                }
                BlockRenderer.Foot.RIGHT -> {
                    SoundBank.rightPcm = result
                    MetronomeEngine.setRightCustom(asset)
                    MetronomeEngine.setRightUseCustom(true)
                }
            }
            setLoad(
                foot, MetronomeEngine.CustomLoadState(
                    MetronomeEngine.LoadStatus.READY,
                    "已加载：$name（取前 ${SoundBank.MAX_CUSTOM_SECONDS.toInt()} 秒）"
                )
            )
        }
    }

    /** 旧版（v1.1）单自定义音频迁移：解码后作为双脚共用资源 */
    fun migrateLegacy(context: Context, uri: String, name: String?) {
        val app = context.applicationContext
        scope.launch {
            val pcm = withContext(Dispatchers.IO) {
                try { SoundBank.decodeCustom(app, Uri.parse(uri)) } catch (_: Exception) { null }
            }
            if (pcm == null || peakLevel(pcm) < 10) {
                Log.w(TAG, "legacy custom audio migration failed; keep built-in")
                return@launch
            }
            val id = "aud_legacy_" + System.currentTimeMillis().toString(36)
            if (!withContext(Dispatchers.IO) { AudioAssets.savePcm(app, id, pcm) }) return@launch
            val asset = com.metronome.app.core.AudioAsset(id, name ?: "自定义音频")
            SoundBank.leftPcm = pcm
            SoundBank.rightPcm = pcm
            MetronomeEngine.setLeftCustom(asset)
            MetronomeEngine.setRightCustom(asset)
            MetronomeEngine.logState("legacy-custom-migrated id=$id")
        }
    }

    /** 启动时从私有目录恢复双脚自定义 PCM（存在且启用才恢复） */
    fun restoreFromAssets(context: Context) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            MetronomeEngine.leftCustom.value?.let { a ->
                AudioAssets.loadPcm(app, a.id)?.let { SoundBank.leftPcm = it }
            }
            MetronomeEngine.rightCustom.value?.let { a ->
                AudioAssets.loadPcm(app, a.id)?.let { SoundBank.rightPcm = it }
            }
        }
    }

    /** 应用预设后按资源 id 恢复某脚 PCM；返回是否成功（失败 UI 提示回退内置） */
    suspend fun loadAssetPcm(
        context: Context,
        foot: BlockRenderer.Foot,
        asset: com.metronome.app.core.AudioAsset,
    ): ShortArray? = withContext(Dispatchers.IO) {
        AudioAssets.loadPcm(context.applicationContext, asset.id)
    }

    private fun setLoad(foot: BlockRenderer.Foot, st: MetronomeEngine.CustomLoadState) {
        when (foot) {
            BlockRenderer.Foot.LEFT -> MetronomeEngine.leftLoad.value = st
            BlockRenderer.Foot.RIGHT -> MetronomeEngine.rightLoad.value = st
        }
    }

    fun peakLevel(pcm: ShortArray): Int {
        var peak = 0
        var acc = 0L
        for (s in pcm) {
            val a = (if (s < 0) -s else s).toInt()
            if (a > peak) peak = a
            acc += a.toLong() * a
        }
        // RMS 也考虑：全峰值极窄的爆音文件给提示
        val rms = sqrt(acc / pcm.size.coerceAtLeast(1).toDouble()).roundToInt()
        return if (peak > 100 && rms < 3) 0 else peak
    }
}
