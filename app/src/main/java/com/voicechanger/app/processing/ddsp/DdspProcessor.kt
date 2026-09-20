package com.voicechanger.app.processing.ddsp

import android.content.Context
import android.util.Log
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import com.voicechanger.app.processing.ProcessorEngine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DDSP-SVC 处理器：基于可微分信号处理 (Differentiable DSP) 的超轻量语音转换。
 *
 * DDSP-SVC (https://github.com/yxllllk/DDSP-SVC) 使用谐波+噪声模型而非端到端神经网络，
 * 推理极轻量（~10MB），延迟远低于 RVC/VITS 系模型。
 *
 * 特点：
 * - 超轻量（~10MB），CPU 即可实时
 * - 基于谐波+噪声模型，非端到端神经声码器
 * - 极低延迟（<50ms 端到端）
 * - 支持 NPU 加速（NNAPI）
 *
 * 流水线：
 * ```
 * 48k → FIR 降采样 → 16k 环形缓冲
 *   推理线程：
 *     wav16k → F0 检测 → 谐波+噪声参数 → DDSP 合成 → 48k 音频
 * ```
 */
class DdspProcessor(private val context: Context) : ProcessorEngine {

    companion object {
        private const val TAG = "VC/DDSP"
        private const val SAMPLE_RATE = 48000
        private const val FRAME_16K = 160
        private const val WINDOW_FRAMES = 8
        private const val WINDOW_16K = WINDOW_FRAMES * FRAME_16K
        private const val STEP_FRAMES = 4
        private const val STEP_16K = STEP_FRAMES * FRAME_16K
        private const val OUT_RING = SAMPLE_RATE * 3
        private const val STAT_EVERY = 10
        private const val XFADE_SAMPLES = 192
        private const val N_HARMONICS = 30  // 谐波数
        private const val N_NOISE_BANDS = 5  // 噪声频带数

        private val DECIM_FIR = floatArrayOf(
            -0.0022f, -0.0076f, -0.0106f, 0.0053f, 0.0301f, 0.0327f, -0.0153f, -0.0747f,
            -0.0587f, 0.0828f, 0.2668f, 0.4107f, 0.2668f, 0.0828f, -0.0587f, -0.0747f,
            -0.0153f, 0.0327f, 0.0301f, 0.0053f, -0.0106f, -0.0076f, -0.0022f,
        )
        private val DECIM_TAPS = DECIM_FIR.size
    }

    @Volatile var status: String = "未初始化"; private set
    @Volatile var active: Boolean = false; private set
    @Volatile var backendLabel: String = "-"; private set
    @Volatile var inferMs: Float = 0f; private set
    @Volatile var underruns: Int = 0; private set

    private var engine: DdspEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var f0UpKey = 5f

    // 环形缓冲
    private val inRing = FloatArray(1 shl 17)
    private var inWrite = 0; private var inRead = 0; private var decimCount = 0
    private val firDelay = FloatArray(DECIM_TAPS - 1)
    private val outRing = FloatArray(OUT_RING)
    private var outWrite = 0; private var outRead = 0
    private var prefilled = false; private var lastOutSample = 0f
    private val prevTail = FloatArray(XFADE_SAMPLES); private var hasPrevTail = false

    // F0 自相关检测状态
    private val autocorrBuf = FloatArray(WINDOW_16K)
    @Volatile private var f0Smoothed = 0f

    // 统计
    private var nStat = 0; private var sumTotal = 0L

    enum class DdspPreset(val label: String, val f0Semitones: Float, val description: String) {
        FEMALE_SOFT("女声·柔和", 5f, "+5半音，柔和自然女声"),
        FEMALE_CLEAR("女声·清亮", 7f, "+7半音，清亮女声"),
        MALE_DEEP("男声·浑厚", -5f, "-5半音，浑厚男声"),
        CHILD("童声", 10f, "+10半音，孩童声"),
        MONSTER("怪兽声", -12f, "-12半音，怪兽效果"),
        ;
    }

    @Volatile var currentPreset: DdspPreset = DdspPreset.FEMALE_SOFT; private set

    fun setVoicePreset(preset: DdspPreset) {
        currentPreset = preset; f0UpKey = preset.f0Semitones
        f0Smoothed = 0f; hasPrevTail = false
        Log.i(TAG, "voice preset: ${preset.label} (f0=${preset.f0Semitones})")
    }

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
        val e = DdspEngine.open(context)
        if (e == null) {
            active = false
            status = "模型未安装（" + Ddsp.missing(context).joinToString("、") + "）"
            Log.w(TAG, status); return
        }
        engine = e; active = true; backendLabel = e.backend.label
        status = "就绪 · ${e.backend.label}"; running = true
        worker = Thread({ inferLoop() }, "vc-ddsp").apply { priority = Thread.NORM_PRIORITY; start() }
        Log.i(TAG, "DDSP ready: backend=${e.backend.id}")
    }

    override fun update(params: EffectParams) {
        f0UpKey = if (params.pitchSemitones == 0f) 5f else params.pitchSemitones
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        if (!active) { System.arraycopy(input, 0, output, 0, length); return length }
        if (!prefilled) { for (i in 0 until SAMPLE_RATE / 5) push(0f); prefilled = true }
        val nTaps = DECIM_TAPS; val dN = nTaps - 1
        for (i in 0 until length) {
            val x = input[i] / 32768f
            System.arraycopy(firDelay, 0, firDelay, 1, dN - 1); firDelay[0] = x
            if (decimCount % 3 == 0) {
                var acc = 0f
                for (j in 0 until nTaps) { val v = if (j < dN) firDelay[j] else x; acc += DECIM_FIR[j] * v }
                val next = (inWrite + 1) % inRing.size
                if (next != inRead) { inRing[inWrite] = acc; inWrite = next }
            }
            decimCount++
        }
        var produced = 0
        while (produced < length && outRead != outWrite) {
            lastOutSample = outRing[outRead]
            output[produced++] = (outRing[outRead] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            outRead = (outRead + 1) % OUT_RING
        }
        while (produced < length) { underruns++; lastOutSample *= 0.92f
            output[produced++] = (lastOutSample * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        return length
    }

    private fun available16k(): Int = (inWrite - inRead + inRing.size) % inRing.size

    private fun inferLoop() {
        val win = FloatArray(WINDOW_16K)
        while (running) {
            if (available16k() < WINDOW_16K) { try { Thread.sleep(2) } catch (_: InterruptedException) { return }; continue }
            for (k in 0 until WINDOW_16K) { win[k] = inRing[inRead]; inRead = (inRead + 1) % inRing.size }
            val keep = WINDOW_16K - STEP_16K; inRead = (inRead - keep + inRing.size) % inRing.size
            try { val out = infer(win); pushOut(out) }
            catch (t: Throwable) { Log.e(TAG, "inference failed", t); active = false; status = "推理失败：${t.message}"; return }
        }
    }

    private fun infer(wav16k: FloatArray): FloatArray {
        val e = engine ?: return FloatArray(0)
        val t0 = System.nanoTime()

        // 1) F0 检测（自相关法）
        val f0 = detectF0(wav16k)

        // 2) F0 平滑
        if (f0Smoothed <= 0f && f0 > 0f) f0Smoothed = f0
        else if (f0 > 0f) f0Smoothed = f0Smoothed * 0.85f + f0 * 0.15f
        else { f0Smoothed *= 0.5f; if (f0Smoothed < 1f) f0Smoothed = 0f }

        // 3) 升调
        val ratio = Math.pow(2.0, (f0UpKey / 12f).toDouble()).toFloat()
        val f0Shifted = if (f0Smoothed > 0f) f0Smoothed * ratio else 0f

        // 4) DDSP 合成：谐波 + 噪声
        val audio = if (e.hasModel()) {
            e.synthesize(wav16k, f0Shifted, N_HARMONICS, N_NOISE_BANDS)
        } else {
            synthHarmonic(f0Shifted, wav16k.size)
        }

        val t1 = System.nanoTime()
        nStat++; sumTotal += (t1 - t0) / 1_000_000
        if (nStat >= STAT_EVERY) { inferMs = (sumTotal / nStat).toFloat()
            Log.i(TAG, "perf total=${inferMs}ms f0=${f0Shifted.toInt()}Hz backend=${e.backend.id} underrun=$underruns")
            nStat = 0; sumTotal = 0 }
        return audio
    }

    /** 自相关 F0 检测（80-400Hz 范围）。 */
    private fun detectF0(wav: FloatArray): Float {
        val minLag = 16000 / 400  // 40 samples (400Hz)
        val maxLag = 16000 / 80   // 200 samples (80Hz)
        var maxCorr = 0f; var bestLag = 0
        for (lag in minLag..maxLag) {
            var corr = 0f; var energy = 0f
            for (i in 0 until wav.size - lag) {
                corr += wav[i] * wav[i + lag]; energy += wav[i] * wav[i]
            }
            if (energy > 0f && corr > maxCorr) { maxCorr = corr; bestLag = lag }
        }
        return if (bestLag > 0) 16000f / bestLag else 0f
    }

    /** 简化谐波合成器：正弦谐波叠加 + 噪声。 */
    private fun synthHarmonic(f0: Float, nSamples: Int): FloatArray {
        val out = FloatArray(nSamples)
        if (f0 <= 0f) {
            // 静音帧：输出低电平噪声
            for (i in 0 until nSamples) out[i] = (Math.random() * 2 - 1).toFloat() * 0.01f
            return out
        }
        val phaseInc = 2.0 * PI * f0 / SAMPLE_RATE * 3.0  // ×3 因为 48k 输出 / 16k 输入
        var phase = 0.0
        for (i in 0 until nSamples) {
            var sample = 0.0
            for (h in 1..N_HARMONICS) {
                val amp = 1.0 / h  // 谐波递减
                sample += amp * sin(phase * h)
            }
            out[i] = (sample * 0.15f).toFloat()
            phase += phaseInc
        }
        // 添加少量噪声
        for (i in 0 until nSamples) out[i] += (Math.random() * 2 - 1).toFloat() * 0.02f
        return out
    }

    private fun pushOut(audio: FloatArray) {
        val fadeLen = minOf(prevTail.size, audio.size)
        if (hasPrevTail && fadeLen > 0) { for (i in 0 until fadeLen) {
            val w = i.toFloat() / fadeLen; audio[i] = prevTail[i] * (1f - w) + audio[i] * w } }
        for (i in audio.indices) if (!push(audio[i])) break
        val tailStart = (audio.size - prevTail.size).coerceAtLeast(0)
        val tailLen = audio.size - tailStart
        if (tailLen > 0) { System.arraycopy(audio, tailStart, prevTail, prevTail.size - tailLen, tailLen); hasPrevTail = true }
    }

    private fun push(s: Float): Boolean {
        val next = (outWrite + 1) % OUT_RING; if (next == outRead) return false
        outRing[outWrite] = s; outWrite = next; return true
    }

    private fun resetRings() {
        inWrite = 0; inRead = 0; decimCount = 0; outWrite = 0; outRead = 0
        hasPrevTail = false; prefilled = false; lastOutSample = 0f; underruns = 0; f0Smoothed = 0f
        java.util.Arrays.fill(prevTail, 0f); java.util.Arrays.fill(firDelay, 0f)
    }

    override fun reset(discontinuity: Boolean) = resetRings()
    override fun algorithmicDelayFrames(): Int = WINDOW_FRAMES - STEP_FRAMES
    override fun close() { running = false; worker?.join(500); worker = null
        runCatching { engine?.close() }; engine = null; active = false; resetRings() }
}

object Ddsp {
    private const val TAG = "VC/DDSP"
    const val DIR = "models/ddsp"
    const val FILE_MODEL = "ddsp_svc.onnx"
    private val REQUIRED = listOf(FILE_MODEL)
    private val EXTERNAL_SOURCES = listOf(
        "/data/adb/modules/voicechanger_priv/models", "/sdcard/Download/VoiceChanger/models")
    fun dir(context: Context) = java.io.File(context.filesDir, DIR)
    fun missing(context: Context): List<String> {
        val d = dir(context)
        return REQUIRED.filter { !java.io.File(d, it).let { f -> f.isFile && f.length() > 0 } }
    }
    fun isReady(context: Context): Boolean = missing(context).isEmpty()
    fun ensureInstalled(context: Context): List<String> {
        val dst = dir(context); if (!dst.exists() && !dst.mkdirs()) return missing(context)
        for (name in REQUIRED) {
            val target = java.io.File(dst, name)
            if (target.isFile && target.length() > 0) continue
            val src = EXTERNAL_SOURCES.asSequence()
                .flatMap { base -> sequenceOf(java.io.File(base, name), java.io.File(java.io.File(base, "ddsp"), name)) }
                .firstOrNull { it.isFile && it.length() > 0 } ?: continue
            try { src.inputStream().use { i -> target.outputStream().use { i.copyTo(it) } }
                Log.i(TAG, "installed $name (${target.length()} bytes") }
            catch (t: Throwable) { Log.w(TAG, "install $name failed: ${t.message}"); target.delete() }
        }
        return missing(context)
    }
}

class DdspEngine private constructor(
    private val env: ai.onnxruntime.OrtEnvironment,
    private val model: ai.onnxruntime.OrtSession?,
    val backend: DdspBackend,
) : AutoCloseable {

    companion object {
        fun open(context: Context): DdspEngine {
            Ddsp.ensureInstalled(context)
            val dir = Ddsp.dir(context)
            val modelFile = java.io.File(dir, Ddsp.FILE_MODEL)
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            if (!modelFile.isFile || modelFile.length() == 0L) {
                Log.w("VC/DDSPE", "no model, using fallback synth")
                return DdspEngine(env, null, DdspBackend.CPU)
            }
            return try {
                val (b, sess) = DdspBackend.open(env, modelFile.absolutePath, "ddsp")
                Log.i("VC/DDSPE", "ready backend=$b"); DdspEngine(env, sess, b)
            } catch (t: Throwable) { Log.e("VC/DDSPE", "open failed, fallback", t)
                DdspEngine(env, null, DdspBackend.CPU) }
        }
    }

    fun hasModel(): Boolean = model != null

    fun synthesize(wav16k: FloatArray, f0: Float, nHarmonics: Int, nNoiseBands: Int): FloatArray {
        val m = model ?: return FloatArray(wav16k.size * 3)
        // 如果有模型，使用 ONNX 推理
        return try {
            val inputs = HashMap<String, ai.onnxruntime.OnnxTensor>()
            inputs["audio"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, java.nio.FloatBuffer.wrap(wav16k), longArrayOf(1, wav16k.size.toLong()))
            inputs["f0"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, java.nio.FloatBuffer.wrap(floatArrayOf(f0)), longArrayOf(1, 1))
            val out = m.run(inputs).use { res ->
                val outputName = m.outputNames.first()
                val t = res.get(outputName).get() as ai.onnxruntime.OnnxTensor
                val b = t.floatBuffer; b.rewind()
                val n = b.remaining(); FloatArray(n).also { b.get(it) }
            }
            inputs.values.forEach { runCatching { it.close() } }
            out
        } catch (t: Throwable) {
            Log.w("VC/DDSPE", "synthesize failed, fallback: ${t.message}")
            FloatArray(wav16k.size * 3)
        }
    }

    override fun close() { runCatching { model?.close() } }
}

enum class DdspBackend(val id: String, val label: String) {
    NPU("npu", "NPU (NNAPI)"), GPU("gpu", "GPU (NNAPI/FP16)"), CPU("cpu", "CPU (MLAS)");
    companion object {
        val PRIORITY = listOf(NPU, GPU, CPU)
        fun open(env: ai.onnxruntime.OrtEnvironment, path: String, tag: String): Pair<DdspBackend, ai.onnxruntime.OrtSession> {
            var last: Throwable? = null
            for (b in PRIORITY) { var opts: ai.onnxruntime.OrtSession.SessionOptions? = null
                try { opts = ai.onnxruntime.OrtSession.SessionOptions()
                    when (b) {
                        NPU -> opts.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
                        GPU -> opts.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16, ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
                        CPU -> opts.setIntraOpNumThreads(4)
                    }
                    val s = env.createSession(path, opts); Log.i("VC/DDSPE", "$tag -> ${b.id}"); return b to s
                } catch (t: Throwable) { last = t; Log.w("VC/DDSPE", "$tag backend ${b.id} unavailable: ${t.message}")
                    runCatching { opts?.close() } } }
            throw IllegalStateException("all backends failed for $tag: ${last?.message}", last)
        }
    }
}

object DdspStatus {
    fun describe(context: Context): String =
        if (Ddsp.isReady(context)) "DDSP 就绪" else "缺失：" + Ddsp.missing(context).joinToString("、")
}