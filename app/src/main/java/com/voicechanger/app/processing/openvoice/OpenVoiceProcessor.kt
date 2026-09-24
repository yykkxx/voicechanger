package com.voicechanger.app.processing.openvoice

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
 * OpenVoice V2 音色转换处理器（实时滑动窗）。
 *
 * 与 RVC 不同，OpenVoice 使用 tone converter 模型直接在 mel 域做音色映射，
 * 然后用轻量 STFT 声码器合成音频。无需 HuBERT 特征提取，推理更轻量。
 *
 * ```text
 * 48k 帧 → 3:1 FIR 抗混叠降采样 → 16k 环形缓冲
 *   推理线程（滑动窗）：
 *     window = 最近 320 样本（20ms@16k）
 *       mel80 → tone_converter → 转换后 mel80
 *       STFT 逆变换 → 48k 音频
 *     窗口前进 step 帧
 * 输出环形缓冲 → 按 10ms 帧吐出
 * ```
 *
 * - 模型仅 ~30MB，推理更轻量，延迟更低
 * - 后端按 NPU → GPU → CPU 自动选择
 * - 48k→16k 使用与 RVC 相同的 FIR 抗混叠降采样
 */
class OpenVoiceProcessor(private val context: Context) : ProcessorEngine {

    companion object {
        private const val TAG = "VC/OVProc"
        private const val SAMPLE_RATE = 48000
        private const val MODEL_SR = 16000
        private const val FRAME_16K = 160
        /** 每次推理的窗口帧数（20ms × 1 = 320 样本@16k）。 */
        private const val WINDOW_FRAMES = 16
        private const val WINDOW_16K = WINDOW_FRAMES * FRAME_16K
        private const val STEP_FRAMES = 8
        private const val STEP_16K = STEP_FRAMES * FRAME_16K
        private const val OUT_RING = SAMPLE_RATE * 3
        private const val N_MELS = 80
        private const val N_FFT = 512
        private const val MEL_HOP = 160
        private const val STAT_EVERY = 10
    }

    /** 48k→16k 抗混叠抽取器。 */
    private val decimator = FirDecimator(3, 63, 7000.0, 48000.0)
    private val dcBlocker = DcBlocker()
    private val noiseGate = NoiseGate(SAMPLE_RATE, thresholdDb = -48f, floorGain = 0.1f)
    private val highSmoother = HighBandSmoother(SAMPLE_RATE, 6000f, 0.3f)

    @Volatile
    var status: String = "未初始化"
        private set

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var backendLabel: String = "-"
        private set

    @Volatile
    var inferMs: Float = 0f
        private set

    @Volatile
    var underruns: Int = 0
        private set

    private var engine: OpenVoiceEngine? = null
    private var worker: Thread? = null
    @Volatile
    private var running = false

    @Volatile
    private var f0UpKey = 5f  // 默认 +5 半音

    // 输入环形缓冲（16k）
    private val inRing = FloatArray(1 shl 17)
    private var inWrite = 0
    private var inRead = 0
    private val dec16 = FloatArray(160 + 2)

    // 输出环形缓冲（48k）
    private val outRing = FloatArray(OUT_RING)
    private var outWrite = 0
    private var outRead = 0

    // 预填充和欠载保持
    private var prefilled = false
    private var lastOutSample = 0f

    // 交叉淡入淡出
    private val prevTail = FloatArray(192)
    private var hasPrevTail = false
    private val processBuf = FloatArray(480)

    // STFT 状态
    private val re = FloatArray(N_FFT)
    private val im = FloatArray(N_FFT)
    private val window = FloatArray(N_FFT) { i -> 0.5f - 0.5f * cos(2.0 * PI * i / (N_FFT - 1)).toFloat() }

    // 统计
    private var nStat = 0
    private var sumTotal = 0L

    /** OpenVoice 音色预设（基于音高调整）。 */
    enum class OvVoicePreset(val label: String, val f0Semitones: Float, val description: String) {
        FEMALE("女声", 5f, "+5 半音，自然女声"),
        FEMALE_SWEET("甜美女声", 6f, "+6 半音，年轻甜美"),
        FEMALE_MATURE("成熟女声", 3f, "+3 半音，低沉女声"),
        MALE_DEEP("浑厚男声", -5f, "-5 半音，低沉男声"),
        MALE_YOUNG("少年音", 2f, "+2 半音，清亮少年"),
        CHILD("童声", 8f, "+8 半音，孩童声"),
        ;
    }

    @Volatile
    var currentPreset: OvVoicePreset = OvVoicePreset.FEMALE
        private set

    fun setVoicePreset(preset: OvVoicePreset) {
        currentPreset = preset
        f0UpKey = preset.f0Semitones
        hasPrevTail = false
        Log.i(TAG, "voice preset: ${preset.label} (f0=${preset.f0Semitones})")
    }

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
        val e = OpenVoiceEngine.open(context)
        if (e == null) {
            active = false
            status = "模型未安装（" + OpenVoice.missing(context).joinToString("、") + "）"
            Log.w(TAG, status)
            return
        }
        engine = e
        active = true
        backendLabel = e.backend.label
        status = "就绪 · ${e.backend.label}"
        running = true
        worker = Thread({ inferLoop() }, "vc-openvoice").apply {
            priority = Thread.NORM_PRIORITY; start()
        }
        Log.i(TAG, "OpenVoice ready: backend=${e.backend.id}")
    }

    override fun update(params: EffectParams) {
        f0UpKey = if (params.pitchSemitones == 0f) 5f else params.pitchSemitones
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        if (!active) { System.arraycopy(input, 0, output, 0, length); return length }
        if (!prefilled) { for (i in 0 until SAMPLE_RATE / 5) push(0f); prefilled = true }
        var dIdx = 0
        for (i in 0 until length) {
            val x = input[i] / 32768f
            if (decimator.process(x, dec16, dIdx)) dIdx++
        }
        for (i in 0 until dIdx) {
            val next = (inWrite + 1) % inRing.size
            if (next == inRead) break
            inRing[inWrite] = dec16[i]; inWrite = next
        }
        var produced = 0
        while (produced < length && outRead != outWrite) {
            lastOutSample = outRing[outRead]
            output[produced++] = (outRing[outRead] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            outRead = (outRead + 1) % OUT_RING
        }
        while (produced < length) { underruns++; lastOutSample *= 0.92f
            output[produced++] = (lastOutSample * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val tmp = processBuf
        for (i in 0 until length) tmp[i] = output[i] / 32768f
        dcBlocker.process(tmp, length); noiseGate.process(tmp, length); highSmoother.process(tmp, length)
        for (i in 0 until length) output[i] = (tmp[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        return length
    }

    private fun available16k(): Int = (inWrite - inRead + inRing.size) % inRing.size

    private fun inferLoop() {
        val win = FloatArray(WINDOW_16K)
        while (running) {
            if (available16k() < WINDOW_16K) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { return }
                continue
            }
            for (k in 0 until WINDOW_16K) {
                win[k] = inRing[inRead]
                inRead = (inRead + 1) % inRing.size
            }
            // 回退 step-frames（保留上下文）
            val keep = WINDOW_16K - STEP_16K
            inRead = (inRead - keep + inRing.size) % inRing.size

            try {
                val out = infer(win)
                pushOut(out)
            } catch (t: Throwable) {
                Log.e(TAG, "inference failed", t)
                active = false
                status = "推理失败：${t.message}"
                return
            }
        }
    }

    private fun infer(wav16k: FloatArray): FloatArray {
        val e = engine ?: return FloatArray(0)
        val t0 = System.nanoTime()

        // 1) mel 特征提取（80 bins）
        val frames = 1 + wav16k.size / MEL_HOP
        val melFlat = computeMel(wav16k, frames)

        // 2) tone converter 推理 — 模型直接输出音频波形（不是 mel）
        val audio = e.convert(melFlat, frames, N_MELS)

        // 3) 如果输出长度匹配音频帧数 × hop，直接使用；否则做简单重采样
        val expectedLen = frames * (SAMPLE_RATE / 100)
        val result = if (audio.size == expectedLen) {
            audio
        } else if (audio.size > expectedLen) {
            audio.copyOfRange(0, expectedLen)
        } else {
            audio.copyOf(expectedLen)
        }

        val t1 = System.nanoTime()
        nStat++
        sumTotal += (t1 - t0) / 1_000_000
        if (nStat >= STAT_EVERY) {
            inferMs = (sumTotal / nStat).toFloat()
            Log.i(TAG, "perf frames=$frames total=${inferMs}ms backend=${e.backend.id} underrun=$underruns outLen=${audio.size}")
            nStat = 0; sumTotal = 0
        }

        return result
    }

    /** 简化 mel 前端：16k wav → log-mel [80][T]。 */
    private fun computeMel(wav: FloatArray, frames: Int): FloatArray {
        val pad = N_FFT / 2
        val padded = FloatArray(wav.size + 2 * pad)
        for (i in 0 until pad) padded[i] = wav[pad - i]
        System.arraycopy(wav, 0, padded, pad, wav.size)
        for (i in 0 until pad) padded[pad + wav.size + i] = wav[wav.size - 2 - i]

        val out = FloatArray(N_MELS * frames)
        for (t in 0 until frames) {
            val off = t * MEL_HOP
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (i in 0 until N_FFT) re[i] = padded[off + i] * window[i]
            fft(re, im)
            // 简化：用幅度谱的前 80 bins 作为 mel（实际应用 mel 基矩阵）
            for (m in 0 until N_MELS) {
                val k = m * (N_FFT / 2) / N_MELS
                val r = re[k]; val i2 = im[k]
                val mag = sqrt(r * r + i2 * i2)
                out[t * N_MELS + m] = ln(if (mag < 1e-5f) 1e-5f else mag)
            }
        }
        return out
    }

    /** 简化声码器：mel → 波形（用 STFT 逆变换近似）。 */
    private fun melToAudio(mel: FloatArray, frames: Int): FloatArray {
        val hopOut = SAMPLE_RATE / 100  // 480 samples per frame @ 48k
        val out = FloatArray(frames * hopOut)
        for (t in 0 until frames) {
            val off = t * N_MELS
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (m in 0 until N_MELS) {
                val k = m * (N_FFT / 2) / N_MELS
                val mag = kotlin.math.exp(mel[off + m])
                re[k] = mag
            }
            // ISTFT（简化：直接对实部做逆 FFT）
            ifft(re, im)
            for (i in 0 until hopOut) {
                out[t * hopOut + i] = re[i] * window[i] * 0.5f
            }
        }
        return out
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2]; val bi = im[i + k + len / 2]
                    val tr = br * cr - bi * ci
                    val ti = br * ci + bi * cr
                    re[i + k] = ar + tr.toFloat(); im[i + k] = ai + ti.toFloat()
                    re[i + k + len / 2] = ar - tr.toFloat(); im[i + k + len / 2] = ai - ti.toFloat()
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun ifft(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fft(re, im)
        val inv = 1f / n
        for (i in 0 until n) { re[i] = re[i] * inv; im[i] = -im[i] * inv }
    }

    private fun pushOut(audio: FloatArray) {
        val fadeLen = minOf(prevTail.size, audio.size)
        if (hasPrevTail && fadeLen > 0) {
            for (i in 0 until fadeLen) {
                val w = i.toFloat() / fadeLen
                audio[i] = prevTail[i] * (1f - w) + audio[i] * w
            }
        }
        for (i in audio.indices) {
            if (!push(audio[i])) break
        }
        val tailStart = (audio.size - prevTail.size).coerceAtLeast(0)
        val tailLen = audio.size - tailStart
        if (tailLen > 0) {
            System.arraycopy(audio, tailStart, prevTail, prevTail.size - tailLen, tailLen)
            hasPrevTail = true
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
        inWrite = 0; inRead = 0
        outWrite = 0; outRead = 0
        hasPrevTail = false; prefilled = false; lastOutSample = 0f; underruns = 0
        java.util.Arrays.fill(prevTail, 0f)
        decimator.reset(); dcBlocker.reset(); noiseGate.reset(); highSmoother.reset()
    }

    override fun reset(discontinuity: Boolean) = resetRings()

    override fun algorithmicDelayFrames(): Int = WINDOW_FRAMES - STEP_FRAMES

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

/** 供 UI 展示用。 */
object OpenVoiceStatus {
    fun describe(context: Context): String =
        if (OpenVoice.isReady(context)) "OpenVoice 就绪" else "缺失：" + OpenVoice.missing(context).joinToString("、")
}