package com.voicechanger.app.processing.female

import com.voicechanger.app.core.audio.PcmUtils
import com.voicechanger.app.domain.AudioStandard
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import com.voicechanger.app.processing.ProcessorEngine
import com.voicechanger.app.processing.dsp.FloatSoftClip
import com.voicechanger.app.processing.dsp.Presence
import com.voicechanger.app.processing.dsp.SmbPitchShifter
import com.voicechanger.app.processing.dsp.TiltEq
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 男声 → 女声转换引擎（本地实时，无需任何模型文件）。
 *
 * 设计要点（男→女必须同时改两件事，缺一不可）：
 *  1. **基频上移**：相位声码器把 F0 抬进女声区间（默认自适应到 ~210 Hz）；
 *  2. **共振峰上移**：只搬移频谱包络（声道长度变短），谐波位置不动，
 *     避免"捏嗓子假声"听感（[SmbPitchShifter.formantRatio]）。
 *
 * 链：
 * ```text
 * 输入 → (每 ~100ms) 自相关 F0 估计 → 自适应半音数
 *      → SMB 相位声码器（变调 + 共振峰搬移）→ 明亮度 TiltEq → 软限幅 → 输出
 * ```
 *
 * - 用户显式设置「音调」滑杆（≠0）时关闭自适应，按用户值执行；
 * - 输入已是女声（F0 ≥ 180 Hz）时自动收敛到最小提升，不会越变越尖；
 * - 全程帧内零分配，可在实时线程运行。
 */
class FemaleVoiceProcessor : ProcessorEngine {

    companion object {
        private const val TAG = "VC/Female"

        /** 目标基频（女声典型中位值）。 */
        const val TARGET_F0_HZ = 210f

        /** 自适应提升的上下限（半音）。 */
        const val MIN_SHIFT_SEMITONES = 2f
        const val MAX_SHIFT_SEMITONES = 8f

        /** 默认提升量（用户未调整音调时）。 */
        const val DEFAULT_SHIFT_SEMITONES = 5f

        /** F0 低于该值视为男声，需要完整提升。 */
        const val MALE_F0_HZ = 130f

        /** F0 高于该值视为女声，不再提升。 */
        const val FEMALE_F0_HZ = 180f

        // ---- F0 估计参数（8 kHz 降采样域）----
        private const val F0_RATE = 8000
        private const val F0_WIN = 256          // 32 ms
        private const val F0_MIN_HZ = 70
        private const val F0_MAX_HZ = 350
        private const val F0_PERIOD_FRAMES = 10 // 每 10 个 10ms 帧（100ms）估计一次
    }

    @Volatile
    private var params: EffectParams = EffectParams()

    private val shifter = SmbPitchShifter(
        fftFrameSize = 512,
        osamp = 4,
        sampleRate = AudioStandard.SAMPLE_RATE,
    )
    private val tiltEq = TiltEq(AudioStandard.SAMPLE_RATE)

    // 会话 #9 增强：临场感 + 输出润色
    private val presence = Presence(AudioStandard.SAMPLE_RATE)

    /** F0 平滑系数（0..1）：越大跟随越快；每 100ms 更新一次，平滑避免音高跳变。 */
    private val f0Smoothing = 0.35f
    private var smoothShift = DEFAULT_SHIFT_SEMITONES

    private val floatIn = FloatArray(AudioStandard.SAMPLES_PER_FRAME)
    private val floatOut = FloatArray(AudioStandard.SAMPLES_PER_FRAME)
    private val f0Window = FloatArray(F0_WIN)

    /** 当前生效的提升量（半音）。 */
    @Volatile
    var currentShiftSemitones: Float = DEFAULT_SHIFT_SEMITONES
        private set

    /** 最近一次估计的输入基频（Hz，0 = 未检测到有声段）。 */
    @Volatile
    var lastF0Hz: Float = 0f
        private set

    private var f0DecimCount = 0
    private var framesSinceF0 = 0

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
        currentShiftSemitones = effectiveShift(params)
        smoothShift = currentShiftSemitones
        applyShift(currentShiftSemitones)
    }

    override fun update(params: EffectParams) {
        this.params = params
        tiltEq.tiltGain = PcmUtils.dbToLinear(if (params.eqTiltDb == 0f) 2f else params.eqTiltDb)
        // 女声引擎默认轻度临场感提升（3kHz），人声更清晰自然
        presence.gainDb = if (params.presenceDb == 0f) 2.5f else params.presenceDb
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        for (i in 0 until length) floatIn[i] = input[i] / 32768f

        // 1) 周期性 F0 估计（8 kHz 域的稀疏抽样，代价极低）
        if (++framesSinceF0 >= F0_PERIOD_FRAMES) {
            framesSinceF0 = 0
            estimateF0(floatIn, length)
            if (params.pitchSemitones == 0f) {
                val s = effectiveShift(params)
                // F0 时间平滑：避免男声/无声段切换时音高跳变（会话 #9 增强）
                smoothShift += (s - smoothShift) * f0Smoothing
                if (abs(smoothShift - currentShiftSemitones) > 0.05f) {
                    currentShiftSemitones = smoothShift
                    applyShift(currentShiftSemitones)
                }
            }
        }

        // 2) 变调 + 共振峰搬移
        val ratio = semitonesToRatio(currentShiftSemitones)
        if (abs(ratio - 1f) > 0.001f) {
            shifter.pitchRatio = ratio
            shifter.formantRatio = formantFor(params, currentShiftSemitones)
            shifter.process(floatIn, floatOut, length)
        } else {
            System.arraycopy(floatIn, 0, floatOut, 0, length)
        }

        // 3) 明亮度 + 临场感（会话 #9：女声更清晰靠前）
        tiltEq.process(floatOut, length)
        presence.process(floatOut, length)

        // 4) 输出增益 + 软饱和润色（轻微 tanh 让人声更暖，避免数码感）
        val gain = PcmUtils.dbToLinear(params.outputGainDb)
        if (gain != 1f) for (i in 0 until length) floatOut[i] *= gain
        if (params.limiterEnabled) {
            FloatSoftClip.process(floatOut, length)
        } else {
            // 无硬限幅时也做轻度软饱和（阈值 0.95），保护扬声器
            for (i in 0 until length) {
                val x = floatOut[i]
                val ax = abs(x)
                if (ax > 0.95f) floatOut[i] = if (x < 0) -0.95f - (ax - 0.95f) * 0.3f else 0.95f + (ax - 0.95f) * 0.3f
            }
        }

        for (i in 0 until length) {
            val v = (floatOut[i] * 32767f).toInt()
            output[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return length
    }

    /** 共振峰搬移比例：≈1.25 @ +5 半音（女声声道更短）。 */
    private fun formantFor(p: EffectParams, shift: Float): Float =
        if (p.formantRatio != 1f) p.formantRatio
        else (1f + 0.05f * shift).coerceIn(1f, 1.45f)

    private fun applyShift(semitones: Float) {
        shifter.pitchRatio = semitonesToRatio(semitones)
        shifter.formantRatio = formantFor(params, semitones)
    }

    /**
     * 自适应提升量：
     * - 用户设了音调 → 直接用；
     * - 男声（F0 ≤ [MALE_F0_HZ]）→ 抬到 [TARGET_F0_HZ]；
     * - 女声（F0 ≥ [FEMALE_F0_HZ]）→ 最小提升；
     * - 中间线性过渡。
     */
    private fun effectiveShift(p: EffectParams): Float {
        if (p.pitchSemitones != 0f) return p.pitchSemitones.coerceIn(-12f, 12f)
        val f0 = lastF0Hz
        if (f0 < 60f) return DEFAULT_SHIFT_SEMITONES   // 无声/未检测到 → 默认
        if (f0 <= MALE_F0_HZ) return MAX_SHIFT_SEMITONES.coerceAtLeast(DEFAULT_SHIFT_SEMITONES)
        if (f0 >= FEMALE_F0_HZ) return MIN_SHIFT_SEMITONES
        // 130..180 Hz：线性从 +8 收敛到 +2
        val t = (f0 - MALE_F0_HZ) / (FEMALE_F0_HZ - MALE_F0_HZ)
        return (MAX_SHIFT_SEMITONES + (MIN_SHIFT_SEMITONES - MAX_SHIFT_SEMITONES) * t)
            .coerceIn(MIN_SHIFT_SEMITONES, MAX_SHIFT_SEMITONES)
    }

    // ---------------- F0 估计（归一化自相关） ----------------

    private fun estimateF0(frame: FloatArray, length: Int) {
        // 48k → 8k：每 6 点抽样一次，边抽边写入环形窗口
        var i = 0
        while (i < length) {
            if (f0DecimCount % 6 == 0) {
                System.arraycopy(f0Window, 1, f0Window, 0, F0_WIN - 1)
                f0Window[F0_WIN - 1] = frame[i]
            }
            f0DecimCount++
            i++
        }

        var energy = 0f
        for (k in 0 until F0_WIN) energy += f0Window[k] * f0Window[k]
        if (energy < 1e-3f) {
            lastF0Hz = 0f
            return
        }

        val minLag = F0_RATE / F0_MAX_HZ
        val maxLag = (F0_RATE / F0_MIN_HZ).coerceAtMost(F0_WIN / 2)
        var bestLag = 0
        var bestVal = 0f
        for (lag in minLag..maxLag) {
            var acc = 0f
            var e0 = 0f
            var e1 = 0f
            val n = F0_WIN - lag
            for (k in 0 until n) {
                val a = f0Window[k]
                val b = f0Window[k + lag]
                acc += a * b
                e0 += a * a
                e1 += b * b
            }
            val norm = acc / (sqrt(e0 * e1) + 1e-9f)
            if (norm > bestVal) {
                bestVal = norm
                bestLag = lag
            }
        }
        lastF0Hz = if (bestVal < 0.3f || bestLag <= 0) 0f else F0_RATE.toFloat() / bestLag
    }

    override fun reset(discontinuity: Boolean) {
        shifter.reset()
        tiltEq.reset()
        presence.reset()
        f0Window.fill(0f)
        f0DecimCount = 0
        framesSinceF0 = 0
        lastF0Hz = 0f
        smoothShift = currentShiftSemitones
    }

    override fun algorithmicDelayFrames(): Int = 2   // ≈21ms（512/4 声码器）

    private fun semitonesToRatio(semitones: Float): Float = 2f.pow(semitones / 12f)
}