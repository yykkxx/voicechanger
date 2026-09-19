package com.voicechanger.app.processing.rvc

import android.content.Context
import android.util.Log
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import com.voicechanger.app.processing.ProcessorEngine
import java.io.File
import kotlin.math.pow

/**
 * RVC AI 声线转换处理器（男→女），低延迟滑动窗设计。
 *
 * ```text
 * 48k 帧 → 3:1 降采样 → 16k 环形缓冲
 *   推理线程（滑动窗）：
 *     window = 最近 L 帧（160·L 样本）
 *       mel128 → RMVPE → f0 ×2^(n/12) → pitch/nsff0
 *       HuBERT(50Hz×768) → ×2 上采样 → phone[L,768]
 *       net_g48k → L·480 样本（48k）
 *     只取「尾部 step 帧」作为输出（含左侧上下文，边界连续）
 *     窗口前进 step 帧
 * 输出环形缓冲 → 按 10 ms 帧吐出
 * ```
 *
 * - 延迟 ≈ (L - step)·10ms + 单次推理耗时；L=32/step=16 → ~160ms + 推理；
 * - 推理在后臺线程执行，不阻塞音频线程；欠载时输出静音；
 * - 后端按 NPU → GPU → CPU 自动选择（见 [RvcBackend]），并记录每级耗时。
 */
class RvcProcessor(private val context: Context) : ProcessorEngine {

    companion object {
        private const val TAG = "VC/RvcProc"
        private const val OUT_RING = Rvc.SAMPLE_RATE * 3
        /** 男→女默认升调（八度）：男声 ~110 Hz → 女声 ~220 Hz。 */
        const val DEFAULT_F0_UP_SEMITONES = 12f
        private const val STAT_EVERY = 5
    }

    @Volatile
    var status: String = "未初始化"
        private set

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var backendLabel: String = "-"
        private set

    /** 单次推理平均耗时（ms），供 UI/日志展示。 */
    @Volatile
    var inferMs: Float = 0f
        private set

    private var engine: RvcEngine? = null
    private var variant: Rvc.Variant? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var f0UpKey = DEFAULT_F0_UP_SEMITONES

    @Volatile
    private var sid = 0L

    /** 自适应步长（帧）：以「单次推理耗时」为基准调整，避免重叠计算浪费；上限 = 窗口长度。 */
    @Volatile
    private var dynStep: Int = 0
        set(value) {
            field = value
            currentStep = value
        }

    /** 当前实际步长（帧），供日志/UI。 */
    @Volatile
    var currentStep: Int = 0
        private set

    private val inRing = FloatArray(1 shl 17)     // 16k 样本，约 8 s
    private var inWrite = 0
    private var inRead = 0
    private var decimCount = 0

    private val outRing = FloatArray(OUT_RING)
    private var outWrite = 0
    private var outRead = 0

    // 统计
    private var nStat = 0
    private var sumMel = 0L
    private var sumHubert = 0L
    private var sumRmvpe = 0L
    private var sumNetG = 0L
    private var sumTotal = 0L

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
        val v = Rvc.resolveVariant(context)
        if (v == null) {
            active = false
            status = "模型未安装（" + Rvc.missing(context).joinToString("、") + "）"
            Log.w(TAG, status)
            return
        }
        val e = RvcEngine.open(context, v)
        if (e == null) {
            active = false
            status = "模型加载失败"
            return
        }
        variant = v
        engine = e
        e.setSpeaker(sid)
        active = true
        backendLabel = e.backend.label
        status = "就绪 · L=${v.frames} · ${e.backend.label}"
        running = true
        worker = Thread({ inferLoop() }, "vc-rvc").apply { priority = Thread.NORM_PRIORITY; start() }
        Log.i(TAG, "RVC ready: variant L=${v.frames} step=${v.stepFrames} backend=${e.backend.id} " +
            "files=${File(context.filesDir, Rvc.DIR).list()?.joinToString()}")
    }

    override fun update(params: EffectParams) {
        f0UpKey = if (params.pitchSemitones == 0f) DEFAULT_F0_UP_SEMITONES else params.pitchSemitones
    }

    fun setSpeaker(id: Long) {
        sid = id
        engine?.setSpeaker(id)
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        if (!active) {
            System.arraycopy(input, 0, output, 0, length)
            return length
        }
        var i = 0
        while (i < length) {
            if (decimCount % 3 == 0) {
                val v = (input[i] + input[minOf(i + 1, length - 1)] + input[minOf(i + 2, length - 1)]) / 98304f
                val next = (inWrite + 1) % inRing.size
                if (next != inRead) {
                    inRing[inWrite] = v
                    inWrite = next
                }
            }
            decimCount++
            i++
        }
        var produced = 0
        while (produced < length && outRead != outWrite) {
            val v = outRing[outRead] * 32767f
            output[produced++] = v.coerceIn(-32768f, 32767f).toInt().toShort()
            outRead = (outRead + 1) % OUT_RING
        }
        while (produced < length) output[produced++] = 0
        return length
    }

    private fun available16k(): Int = (inWrite - inRead + inRing.size) % inRing.size

    /** 推理线程：滑动窗，每前进 step 帧跑一次。 */
    private fun inferLoop() {
        val v = variant ?: return
        val win = FloatArray(v.window16k)
        while (running) {
            if (available16k() < v.window16k) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            for (k in 0 until v.window16k) {
                win[k] = inRing[inRead]
                inRead = (inRead + 1) % inRing.size
            }
            // 窗口前进 step 帧：把剩余上下文放回环首
            val step = if (dynStep <= 0) v.stepFrames else dynStep.coerceIn(v.stepFrames, v.frames)
            val keep = v.window16k - step * Rvc.FRAME_16K
            inRead = (inRead - keep + inRing.size) % inRing.size

            try {
                val out = infer(win, v, step)
                pushOut(out, v.frames - step, v)
            } catch (t: Throwable) {
                Log.e(TAG, "inference failed", t)
                active = false
                status = "推理失败：${t.message}"
                return
            }
        }
    }

    private fun infer(wav16k: FloatArray, v: Rvc.Variant, step: Int): FloatArray {
        val e = engine ?: return FloatArray(0)
        val t0 = System.nanoTime()

        // 1) RMVPE: mel → f0（取前 L 帧）
        val mel = e.melFrontend().compute(wav16k)
        val t0a = System.nanoTime()
        val melL = Array(Rvc.N_MELS) { m -> mel[m].copyOf(v.frames) }
        val hidden = e.rmvpeHidden(melL)
        val f0raw = Rvc.hiddenToF0(hidden)
        val f0 = FloatArray(v.frames) { i -> if (i < f0raw.size) f0raw[i] else 0f }
        val t1 = System.nanoTime()

        // 2) 升调（男→女）
        val ratio = 2f.pow(f0UpKey / 12f)
        for (i in f0.indices) if (f0[i] > 0f) f0[i] *= ratio
        val pitch = Rvc.f0ToCoarse(f0)

        // 3) HuBERT → ×2 上采样到 L 帧
        val feats = e.hubertFeatures(wav16k)
        val phone = FloatArray(v.frames * 768)
        for (i in 0 until v.frames) {
            val src = feats[(i / 2).coerceAtMost(feats.size - 1)]
            System.arraycopy(src, 0, phone, i * 768, 768)
        }
        val t2 = System.nanoTime()

        // 4) net_g
        val audio = e.synthesize(phone, pitch, f0)
        val t3 = System.nanoTime()

        nStat++
        sumMel += (t0a - t0) / 1_000_000
        sumRmvpe += (t1 - t0a) / 1_000_000
        sumHubert += (t2 - t1) / 1_000_000
        sumNetG += (t3 - t2) / 1_000_000
        sumTotal += (t3 - t0) / 1_000_000
        if (nStat >= STAT_EVERY) {
            val n = nStat.toFloat()
            inferMs = sumTotal / n
            Log.i(TAG, "perf L=${v.frames} step=${currentStep} mel=${sumMel / n}ms rmvpe=${sumRmvpe / n}ms " +
                "hubert=${sumHubert / n}ms net_g=${sumNetG / n}ms total=${sumTotal / n}ms " +
                "backend=${engine?.backend?.id} budget=${currentStep * 10}ms " +
                "rtf=${String.format("%.2f", (sumTotal / n) / (currentStep * 10f))}")
            nStat = 0; sumMel = 0; sumRmvpe = 0; sumHubert = 0; sumNetG = 0; sumTotal = 0
        }
        // 自适应步长：单次耗时换算成帧数（+1 帧余量）；慢时升、快时缓降
        val needFrames = (inferMs / 10f).toInt() + 1
        val target = needFrames.coerceIn(v.stepFrames, v.frames)
        dynStep = if (target >= (if (dynStep <= 0) v.stepFrames else dynStep)) {
            target
        } else {
            (((if (dynStep <= 0) v.stepFrames else dynStep) * 7 + target * 3) / 10).coerceAtLeast(v.stepFrames)
        }
        return audio
    }

    /** 输出尾部 [fromFrames] 之后的帧；32k 模型（320 样本/帧）线性重采样到 48k（480 样本/帧）。 */
    private fun pushOut(audio: FloatArray, fromFrames: Int, v: Rvc.Variant) {
        val spf = v.samplesPerFrame
        if (spf == Rvc.HOP) {
            var i = (fromFrames * spf).coerceAtLeast(0)
            while (i < audio.size) {
                if (!push(audio[i])) return
                i++
            }
            return
        }
        val ratio = spf.toFloat() / Rvc.HOP
        for (f in fromFrames.coerceAtLeast(0) until v.frames) {
            val base = f * spf
            if (base + spf > audio.size) break
            for (k in 0 until Rvc.HOP) {
                val pos = k * ratio
                val i0 = pos.toInt()
                val i1 = (i0 + 1).coerceAtMost(spf - 1)
                val w = pos - i0
                val s = audio[base + i0] + (audio[base + i1] - audio[base + i0]) * w
                if (!push(s)) return
            }
        }
    }

    private fun push(s: Float): Boolean {
        val next = (outWrite + 1) % OUT_RING
        if (next == outRead) return false
        outRing[outWrite] = s
        outWrite = next
        return true
    }

    private fun resetRings() {
        inWrite = 0; inRead = 0; decimCount = 0
        outWrite = 0; outRead = 0
    }

    override fun reset(discontinuity: Boolean) = resetRings()

    override fun algorithmicDelayFrames(): Int = variant?.let { it.frames - it.stepFrames } ?: 100

    override fun close() {
        running = false
        worker?.join(500)
        worker = null
        runCatching { engine?.close() }
        engine = null
        active = false
        resetRings()
    }
}

/** 供 UI 展示用：当前可用模型信息。 */
object RvcStatus {
    fun describe(context: Context): String {
        val v = Rvc.resolveVariant(context)
        return if (v != null) "RVC 就绪（L=${v.frames}）" else "缺失：" + Rvc.missing(context).joinToString("、")
    }
}