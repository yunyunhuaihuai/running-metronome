package com.metronome.app

import android.content.Context
import android.net.Uri
import android.util.Log
import com.metronome.app.core.BlockRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 两只脚的导入请求独立编号；切回内置时使该脚所有在途结果失效。 */
internal class ImportGeneration {
    private val left = AtomicLong(0)
    private val right = AtomicLong(0)

    private fun counter(foot: BlockRenderer.Foot): AtomicLong = when (foot) {
        BlockRenderer.Foot.LEFT -> left
        BlockRenderer.Foot.RIGHT -> right
    }

    fun next(foot: BlockRenderer.Foot): Long = counter(foot).incrementAndGet()
    fun invalidate(foot: BlockRenderer.Foot) { counter(foot).incrementAndGet() }
    fun isCurrent(foot: BlockRenderer.Foot, generation: Long): Boolean =
        counter(foot).get() == generation
}

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
    private val generations = ImportGeneration()
    private val guard = Any()
    private val jobs = HashMap<BlockRenderer.Foot, Job>()

    /**
     * 导入音频到指定脚（LEFT/RIGHT）。pickUri 为 SAF 选择结果。
     * 完成后自动启用该脚自定义。
     */
    fun import(context: Context, foot: BlockRenderer.Foot, uri: Uri, displayName: String?) {
        val app = context.applicationContext
        synchronized(guard) {
            jobs[foot]?.cancel()
            val mySeq = generations.next(foot)
            setLoad(foot, MetronomeEngine.CustomLoadState(MetronomeEngine.LoadStatus.LOADING))
            jobs[foot] = scope.launch {
                importSelected(app, foot, uri, displayName, mySeq)
            }
        }
    }

    /** 用户切回内置音色时废弃该脚尚未完成的导入。 */
    fun cancelPending(foot: BlockRenderer.Foot) {
        synchronized(guard) {
            generations.invalidate(foot)
            jobs.remove(foot)?.cancel()
            val load = if (foot == BlockRenderer.Foot.LEFT)
                MetronomeEngine.leftLoad.value else MetronomeEngine.rightLoad.value
            if (load.status == MetronomeEngine.LoadStatus.LOADING) {
                setLoad(foot, MetronomeEngine.CustomLoadState())
            }
        }
    }

    private suspend fun importSelected(
        app: Context,
        foot: BlockRenderer.Foot,
        uri: Uri,
        displayName: String?,
        mySeq: Long,
    ) {
        val result = withContext(Dispatchers.IO) {
            try {
                SoundBank.decodeCustom(app, uri)
            } catch (e: Exception) {
                Log.w(TAG, "decode cancelled/failed", e)
                null
            }
        }
        if (!generations.isCurrent(foot, mySeq)) return
        if (result == null || result.isEmpty()) {
            synchronized(guard) {
                if (generations.isCurrent(foot, mySeq)) setLoad(
                    foot, MetronomeEngine.CustomLoadState(
                        MetronomeEngine.LoadStatus.FAILED, "无法解码该音频文件（格式不支持或数据为空）"
                    )
                )
            }
            return
        }
        if (peakLevel(result) < 10) {
            synchronized(guard) {
                if (generations.isCurrent(foot, mySeq)) setLoad(
                    foot, MetronomeEngine.CustomLoadState(
                        MetronomeEngine.LoadStatus.FAILED, "该音频几乎是静音，未采用"
                    )
                )
            }
            return
        }
        val id = "aud_" + foot.name.lowercase() + "_" +
            System.currentTimeMillis().toString(36) + "_" + mySeq.toString(36)
        val name = displayName ?: uri.lastPathSegment ?: "自定义音频"
        var applied = false
        try {
            val saved = withContext(Dispatchers.IO) { AudioAssets.savePcm(app, id, result) }
            if (!saved) {
                synchronized(guard) {
                    if (generations.isCurrent(foot, mySeq)) setLoad(
                        foot, MetronomeEngine.CustomLoadState(
                            MetronomeEngine.LoadStatus.FAILED, "保存音频失败"
                        )
                    )
                }
                return
            }
            synchronized(guard) {
                if (generations.isCurrent(foot, mySeq)) {
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
                    applied = true
                }
            }
        } finally {
            // savePcm 可能在取消后仍完成；凡未发布为有效资源的文件都清理。
            if (!applied) withContext(NonCancellable + Dispatchers.IO) {
                AudioAssets.delete(app, id)
            }
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
        val leftGeneration: Long
        val rightGeneration: Long
        synchronized(guard) {
            leftGeneration = generations.next(BlockRenderer.Foot.LEFT)
            rightGeneration = generations.next(BlockRenderer.Foot.RIGHT)
        }
        scope.launch(Dispatchers.IO) {
            suspend fun restore(foot: BlockRenderer.Foot, generation: Long) {
                val asset = when (foot) {
                    BlockRenderer.Foot.LEFT -> MetronomeEngine.leftCustom.value
                    BlockRenderer.Foot.RIGHT -> MetronomeEngine.rightCustom.value
                } ?: return
                val pcm = AudioAssets.loadPcm(app, asset.id)
                synchronized(guard) {
                    val current = when (foot) {
                        BlockRenderer.Foot.LEFT -> MetronomeEngine.leftCustom.value
                        BlockRenderer.Foot.RIGHT -> MetronomeEngine.rightCustom.value
                    }
                    if (!generations.isCurrent(foot, generation) || current?.id != asset.id) return@synchronized
                    if (pcm != null) {
                        if (foot == BlockRenderer.Foot.LEFT) SoundBank.leftPcm = pcm
                        else SoundBank.rightPcm = pcm
                    } else {
                        Log.w(TAG, "saved custom audio missing: foot=$foot id=${asset.id}")
                        if (foot == BlockRenderer.Foot.LEFT) {
                            SoundBank.leftPcm = null
                            MetronomeEngine.setLeftUseCustom(false)
                            MetronomeEngine.setLeftCustom(null)
                        } else {
                            SoundBank.rightPcm = null
                            MetronomeEngine.setRightUseCustom(false)
                            MetronomeEngine.setRightCustom(null)
                        }
                        setLoad(foot, MetronomeEngine.CustomLoadState(
                            MetronomeEngine.LoadStatus.FAILED,
                            "自定义音频已丢失，请重新导入",
                        ))
                    }
                }
            }
            restore(BlockRenderer.Foot.LEFT, leftGeneration)
            restore(BlockRenderer.Foot.RIGHT, rightGeneration)
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
