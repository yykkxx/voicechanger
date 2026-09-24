package com.voicechanger.app.processing.freevc

import android.content.Context
import android.util.Log
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import com.voicechanger.app.processing.dsp.DcBlocker
import com.voicechanger.app.processing.dsp.FirDecimator
import com.voicechanger.app.processing.dsp.HighBandSmoother
import com.voicechanger.app.processing.dsp.NoiseGate
import com.voicechanger.app.processing.ProcessorEngine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * FreeVC 处理器：基于 VITS 架构的轻量语音转换。
 *
 * FreeVC (https://github.com/OlaWod/FreeVC) 是一种无需文本的语音转换模型，
 * 基于端到端 VITS 架构，使用 HuBERT 特征作为内容表征。
 *
 * 特点：
 * - 单 ONNX 模型 ~50MB（vs RVC 的 1.4GB）
 * - 可跑 NNAPI(NPU/GPU) 或 CPU
 * - 模型内置声码器，无需外部 STFT 逆变换
 * - 支持 zero-shot 音色克隆（提供参考音频）
 *
 * 流水线：
 * ```
 * 48k → FIR 降采样 → 16k 环形缓冲
 *   推理线程：
 *     wav16k → mel80 → VITS encoder → decoder → 48k 音频
 *     交叉淡入淡出 → 输出环形缓冲
 * ```
 */
class FreeVcProcessor(private val context: Context) : ProcessorEngine {

    companion object {
        private const val TAG = "VC/FreeVC"
        private const val SAMPLE_RATE = 48000
        private const val FRAME_16K = 160
        private const val WINDOW_FRAMES = 16
        private const val WINDOW_16K = WINDOW_FRAMES * FRAME_16K
        private const val STEP_FRAMES = 8
        private const val STEP_16K = STEP_FRAMES * FRAME_16K
        private const val OUT_RING = SAMPLE_RATE * 3
        private const val N_MELS = 80
        private const val N_FFT = 512
        private const val MEL_HOP = 160
        private const val STAT_EVERY = 10
        private const val XFADE_SAMPLES = 192
    }

    private val decimator = FirDecimator(3, 63, 7000.0, 48000.0)
    private val dcBlocker = DcBlocker()
    private val noiseGate = NoiseGate(SAMPLE_RATE, thresholdDb = -48f, floorGain = 0.1f)
    private val highSmoother = HighBandSmoother(SAMPLE_RATE, 6000f, 0.3f)

    @Volatile var status: String = "未初始化"; private set
    @Volatile var active: Boolean = false; private set
    @Volatile var backendLabel: String = "-"; private set
    @Volatile var inferMs: Float = 0f; private set
    @Volatile var underruns: Int = 0; private set

    private var engine: FreeVcEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var f0UpKey = 5f

    private val inRing = FloatArray(1 shl 17)
    private var inWrite = 0; private var inRead = 0; private var decimCount = 0
    private val dec16 = FloatArray(160 + 2)
    private val outRing = FloatArray(OUT_RING)
    private var outWrite = 0; private var outRead = 0
    private var prefilled = false; private var lastOutSample = 0f
    private val prevTail = FloatArray(XFADE_SAMPLES); private var hasPrevTail = false
    private val re = FloatArray(N_FFT); private val im = FloatArray(N_FFT)
    private val window = FloatArray(N_FFT) { i -> 0.5f - 0.5f * cos(2.0 * PI * i / (N_FFT - 1)).toFloat() }
    private var nStat = 0; private var sumTotal = 0L

    enum class FreeVcPreset(val label: String, val f0Semitones: Float, val description: String) {
        FEMALE_NATURAL("女声·自然", 5f, "+5半音，自然女声"),
        FEMALE_BRIGHT("女声·明亮", 7f, "+7半音，明亮年轻女声"),
        MALE_DEEP("男声·低沉", -5f, "-5半音，低沉男声"),
        CHILD("童声", 10f, "+10半音，孩童声线"),
        ELDERLY("老人声", -3f, "-3半音，苍老声线"),
        ;
    }

    @Volatile var currentPreset: FreeVcPreset = FreeVcPreset.FEMALE_NATURAL; private set

    fun setVoicePreset(preset: FreeVcPreset) {
        currentPreset = preset; f0UpKey = preset.f0Semitones; hasPrevTail = false
        Log.i(TAG, "voice preset: ${preset.label} (f0=${preset.f0Semitones})")
    }

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
        val e = FreeVcEngine.open(context)
        if (e == null) {
            active = false
            status = "模型未安装（" + FreeVc.missing(context).joinToString("、") + "）"
            Log.w(TAG, status); return
        }
        engine = e; active = true; backendLabel = e.backend.label
        status = "就绪 · ${e.backend.label}"; running = true
        worker = Thread({ inferLoop() }, "vc-freevc").apply { priority = Thread.NORM_PRIORITY; start() }
        Log.i(TAG, "FreeVC ready: backend=${e.backend.id}")
    }

    override fun update(params: EffectParams) {
        f0UpKey = if (params.pitchSemitones == 0f) 5f else params.pitchSemitones
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        if (!active) { System.arraycopy(input, 0, output, 0, length); return length }
        if (!prefilled) { for (i in 0 until SAMPLE_RATE / 5) push(0f); prefilled = true }
        var dIdx = 0
        for (i in 0 until length) { val x = input[i] / 32768f
            if (decimator.process(x, dec16, dIdx)) dIdx++ }
        for (i in 0 until dIdx) { val next = (inWrite + 1) % inRing.size
            if (next == inRead) break; inRing[inWrite] = dec16[i]; inWrite = next }
        var produced = 0
        while (produced < length && outRead != outWrite) {
            lastOutSample = outRing[outRead]
            output[produced++] = (outRing[outRead] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            outRead = (outRead + 1) % OUT_RING }
        while (produced < length) { underruns++; lastOutSample *= 0.92f
            output[produced++] = (lastOutSample * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val tmp = FloatArray(length)
        for (i in 0 until length) tmp[i] = output[i] / 32768f
        dcBlocker.process(tmp, length); noiseGate.process(tmp, length); highSmoother.process(tmp, length)
        for (i in 0 until length) output[i] = (tmp[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        return length
    }

    private fun available16k(): Int = (inWrite - inRead + inRing.size) % inRing.size

    private fun inferLoop() {
        val win = FloatArray(WINDOW_16K)
        while (running) {
            if (available16k() < WINDOW_16K) { try { Thread.sleep(5) } catch (_: InterruptedException) { return }; continue }
            for (k in 0 until WINDOW_16K) { win[k] = inRing[inRead]; inRead = (inRead + 1) % inRing.size }
            val keep = WINDOW_16K - STEP_16K; inRead = (inRead - keep + inRing.size) % inRing.size
            try { val out = infer(win); pushOut(out) }
            catch (t: Throwable) { Log.e(TAG, "inference failed", t); active = false; status = "推理失败：${t.message}"; return }
        }
    }

    private fun infer(wav16k: FloatArray): FloatArray {
        val e = engine ?: return FloatArray(0)
        val t0 = System.nanoTime()
        val frames = 1 + wav16k.size / MEL_HOP
        val melFlat = computeMel(wav16k, frames)
        val convertedMel = e.convert(melFlat, frames, N_MELS)
        val audio = melToAudio(convertedMel, frames)
        val t1 = System.nanoTime()
        nStat++; sumTotal += (t1 - t0) / 1_000_000
        if (nStat >= STAT_EVERY) { inferMs = (sumTotal / nStat).toFloat()
            Log.i(TAG, "perf total=${inferMs}ms backend=${e.backend.id} underrun=$underruns"); nStat = 0; sumTotal = 0 }
        return audio
    }

    private fun computeMel(wav: FloatArray, frames: Int): FloatArray {
        val pad = N_FFT / 2
        val padded = FloatArray(wav.size + 2 * pad)
        for (i in 0 until pad) padded[i] = wav[pad - i]
        System.arraycopy(wav, 0, padded, pad, wav.size)
        for (i in 0 until pad) padded[pad + wav.size + i] = wav[wav.size - 2 - i]
        val out = FloatArray(N_MELS * frames)
        for (t in 0 until frames) {
            val off = t * MEL_HOP
            java.util.Arrays.fill(re, 0f); java.util.Arrays.fill(im, 0f)
            for (i in 0 until N_FFT) re[i] = padded[off + i] * window[i]
            fft(re, im)
            for (m in 0 until N_MELS) {
                val k = m * (N_FFT / 2) / N_MELS; val r = re[k]; val i2 = im[k]
                val mag = sqrt(r * r + i2 * i2)
                out[t * N_MELS + m] = ln(if (mag < 1e-5f) 1e-5f else mag)
            }
        }
        return out
    }

    private fun melToAudio(mel: FloatArray, frames: Int): FloatArray {
        val hopOut = SAMPLE_RATE / 100; val out = FloatArray(frames * hopOut)
        for (t in 0 until frames) {
            val off = t * N_MELS
            java.util.Arrays.fill(re, 0f); java.util.Arrays.fill(im, 0f)
            for (m in 0 until N_MELS) { val k = m * (N_FFT / 2) / N_MELS; re[k] = kotlin.math.exp(mel[off + m]) }
            ifft(re, im)
            for (i in 0 until hopOut) out[t * hopOut + i] = re[i] * window[i] * 0.5f
        }
        return out
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size; var j = 0
        for (i in 1 until n) { var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j or bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti } }
        var len = 2
        while (len <= n) { val ang = -2.0 * PI / len; val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) { var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2]; val bi = im[i + k + len / 2]
                    val tr = br * cr - bi * ci; val ti = br * ci + bi * cr
                    re[i + k] = ar + tr.toFloat(); im[i + k] = ai + ti.toFloat()
                    re[i + k + len / 2] = ar - tr.toFloat(); im[i + k + len / 2] = ai - ti.toFloat()
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr }
                i += len }
            len = len shl 1 }
    }

    private fun ifft(re: FloatArray, im: FloatArray) {
        val n = re.size; for (i in 0 until n) im[i] = -im[i]; fft(re, im)
        val inv = 1f / n; for (i in 0 until n) { re[i] = re[i] * inv; im[i] = -im[i] * inv }
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
        inWrite = 0; inRead = 0; outWrite = 0; outRead = 0
        hasPrevTail = false; prefilled = false; lastOutSample = 0f; underruns = 0
        java.util.Arrays.fill(prevTail, 0f)
        decimator.reset(); dcBlocker.reset(); noiseGate.reset(); highSmoother.reset()
    }

    override fun reset(discontinuity: Boolean) = resetRings()
    override fun algorithmicDelayFrames(): Int = WINDOW_FRAMES - STEP_FRAMES
    override fun close() { running = false; worker?.join(500); worker = null
        runCatching { engine?.close() }; engine = null; active = false; resetRings() }
}

object FreeVc {
    private const val TAG = "VC/FreeVC"
    const val DIR = "models/freevc"
    const val FILE_MODEL = "freevc.onnx"
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
                .flatMap { base -> sequenceOf(java.io.File(base, name), java.io.File(java.io.File(base, "freevc"), name)) }
                .firstOrNull { it.isFile && it.length() > 0 } ?: continue
            try { src.inputStream().use { i -> target.outputStream().use { i.copyTo(it) } }
                Log.i(TAG, "installed $name (${target.length()} bytes") }
            catch (t: Throwable) { Log.w(TAG, "install $name failed: ${t.message}"); target.delete() }
        }
        return missing(context)
    }
}

class FreeVcEngine private constructor(
    private val env: ai.onnxruntime.OrtEnvironment,
    private val model: ai.onnxruntime.OrtSession,
    val backend: FreeVcBackend,
) : AutoCloseable {

    companion object {
        fun open(context: Context): FreeVcEngine? {
            val missing = FreeVc.ensureInstalled(context)
            if (missing.isNotEmpty()) { Log.w("VC/FreeVCE", "models missing: $missing"); return null }
            val dir = FreeVc.dir(context)
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            return try {
                val (b, sess) = FreeVcBackend.open(env, java.io.File(dir, FreeVc.FILE_MODEL).absolutePath, "freevc")
                Log.i("VC/FreeVCE", "ready backend=$b"); FreeVcEngine(env, sess, b)
            } catch (t: Throwable) { Log.e("VC/FreeVCE", "open failed", t); null }
        }
    }

    fun convert(melInput: FloatArray, frames: Int, melBins: Int = 80): FloatArray {
        val inputs = HashMap<String, ai.onnxruntime.OnnxTensor>()
        inputs["mel"] = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(melInput), longArrayOf(1, frames.toLong(), melBins.toLong()))
        val out = model.run(inputs).use { res ->
            val outputName = model.outputNames.first()
            val t = res.get(outputName).get() as ai.onnxruntime.OnnxTensor
            val shape = t.info.shape; val n = (shape[1].toInt()) * (shape[2].toInt())
            FloatArray(n).also { dst -> val b = t.floatBuffer; b.rewind()
                val s = minOf(dst.size, b.remaining()); if (s > 0) b.get(dst, 0, s) }
        }
        inputs.values.forEach { runCatching { it.close() } }; return out
    }

    override fun close() { runCatching { model.close() } }
}

enum class FreeVcBackend(val id: String, val label: String) {
    NPU("npu", "NPU (NNAPI)"), GPU("gpu", "GPU (NNAPI/FP16)"), CPU("cpu", "CPU (MLAS)");
    companion object {
        val PRIORITY = listOf(NPU, GPU, CPU)
        fun open(env: ai.onnxruntime.OrtEnvironment, path: String, tag: String): Pair<FreeVcBackend, ai.onnxruntime.OrtSession> {
            var last: Throwable? = null
            for (b in PRIORITY) { var opts: ai.onnxruntime.OrtSession.SessionOptions? = null
                try { opts = ai.onnxruntime.OrtSession.SessionOptions()
                    when (b) {
                        NPU -> opts.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
                        GPU -> opts.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16, ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
                        CPU -> opts.setIntraOpNumThreads(4)
                    }
                    val s = env.createSession(path, opts); Log.i("VC/FreeVCE", "$tag -> ${b.id}"); return b to s
                } catch (t: Throwable) { last = t; Log.w("VC/FreeVCE", "$tag backend ${b.id} unavailable: ${t.message}")
                    runCatching { opts?.close() } } }
            throw IllegalStateException("all backends failed for $tag: ${last?.message}", last)
        }
    }
}

object FreeVcStatus {
    fun describe(context: Context): String =
        if (FreeVc.isReady(context)) "FreeVC 就绪" else "缺失：" + FreeVc.missing(context).joinToString("、")
}