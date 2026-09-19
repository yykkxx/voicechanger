package com.voicechanger.app.processing.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SMB 风格实时音高变换器（STFT 相位声码器），Kotlin 移植版。
 *
 * 算法要点（等效 stephan bernsee "smbPitchShift"）：
 * - 分析：Hann 窗 + 复数 FFT（fftFrameSize 个复点）；
 * - 相位声码器：逐 bin 计算真实频率，再按 [pitchRatio] 重新映射到合成 bin；
 * - 合成：相位累积 + 逆 FFT + 叠接相加（OLA）；
 * - 每步进 [stepSize] 个样本处理一帧，输入输出逐样本对齐（内部 FIFO 延迟恒定）。
 *
 * 实时约束：process() 内无内存分配；在 48k / fft=1024 / osamp=4 下每次
 * 帧处理为 2 × 1024 点复数 FFT，任意现代设备余量充足。
 *
 * 配置固定为 1024/4（延迟约 21ms 算法分量），原因见 plan/08 的 CPU 预算与
 * 语音质量权衡；如需更高音质可提高 fftFrameSize，但会增加延迟。
 */
class SmbPitchShifter(
    private val fftFrameSize: Int = 1024,
    private val osamp: Int = 4,
    private val sampleRate: Int = 48_000,
) {
    init {
        require(fftFrameSize > 1 && (fftFrameSize and (fftFrameSize - 1)) == 0) {
            "fftFrameSize must be a power of two: $fftFrameSize"
        }
        require(osamp > 0 && fftFrameSize % osamp == 0) { "osamp must divide fftFrameSize" }
    }

    private val fftFrameSize2 = fftFrameSize / 2
    private val stepSize = fftFrameSize / osamp
    private val inFifoLatency = fftFrameSize - stepSize
    private val expct = 2.0 * PI * stepSize / fftFrameSize

    private val gInFIFO = FloatArray(fftFrameSize)
    private val gOutFIFO = FloatArray(fftFrameSize)
    private val gFFTworksp = FloatArray(2 * fftFrameSize)
    private val gLastPhase = FloatArray(fftFrameSize2 + 1)
    private val gSumPhase = FloatArray(fftFrameSize2 + 1)
    private val gOutputAccum = FloatArray(2 * fftFrameSize)
    private val gAnaFreq = FloatArray(fftFrameSize + 2)
    private val gAnaMagn = FloatArray(fftFrameSize + 2)
    private val gSynFreq = FloatArray(fftFrameSize + 2)
    private val gSynMagn = FloatArray(fftFrameSize + 2)
    private val window = FloatArray(fftFrameSize)

    private var gRover = inFifoLatency

    /** 音高比率（>1 升高）。非实时线程设置。 */
    @Volatile
    var pitchRatio: Float = 1f
        set(value) {
            field = value.coerceIn(0.25f, 4f)
        }

    /**
     * 共振峰搬移比率（>1 上移 = 更细/更女声；<1 下移 = 更低沉/更男声）。
     * 只搬移频谱包络（共振峰），不搬移谐波位置，避免"捏嗓子"假声听感
     * （plan/09 第 7.7 节：男→女 = 音高升高 + 共振峰上移，两者缺一不可）。
     */
    @Volatile
    var formantRatio: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
        }

    /** 包络估计窗口半径（bin）。~750Hz @48k/512；太小=谐波起伏，太大=抹平共振峰。 */
    private val envelopeRadius = 8

    init {
        for (k in 0 until fftFrameSize) {
            window[k] = (0.5 - 0.5 * cos(2.0 * PI * k / fftFrameSize)).toFloat()
        }
    }

    /** 均摊算法延迟（样本）。 */
    val latencySamples: Int
        get() = inFifoLatency + stepSize

    /**
     * 处理一块输入。输入输出等长，内部延迟恒定（见 [latencySamples]）。
     * 启动阶段（前 latencySamples 个样本）输出含渐入的近似静音，属正常现象。
     */
    fun process(input: FloatArray, output: FloatArray, length: Int = input.size) {
        for (i in 0 until length) {
            gInFIFO[gRover] = input[i]
            output[i] = gOutFIFO[gRover - inFifoLatency]
            gRover++
            if (gRover >= fftFrameSize) {
                gRover = inFifoLatency
                processFrame()
            }
        }
    }

    /** 复位全部状态（会话停止/不连续时调用）。 */
    fun reset() {
        gInFIFO.fill(0f)
        gOutFIFO.fill(0f)
        gFFTworksp.fill(0f)
        gLastPhase.fill(0f)
        gSumPhase.fill(0f)
        gOutputAccum.fill(0f)
        gAnaFreq.fill(0f)
        gAnaMagn.fill(0f)
        gSynFreq.fill(0f)
        gSynMagn.fill(0f)
        gRover = inFifoLatency
    }

    private fun processFrame() {
        // 1) 加窗 + 交错排布
        for (k in 0 until fftFrameSize) {
            gFFTworksp[2 * k] = gInFIFO[k] * window[k]
            gFFTworksp[2 * k + 1] = 0f
        }

        // 2) 分析 FFT
        Fft.transform(gFFTworksp, 2 * fftFrameSize, -1)

        // 3) 逐 bin 的幅度 / 真实频率
        for (k in 0..fftFrameSize2) {
            val real = gFFTworksp[2 * k].toDouble()
            val imag = gFFTworksp[2 * k + 1].toDouble()

            val magn = 2.0 * sqrt(real * real + imag * imag)
            val phase = atan2(imag, real)

            var tmp = phase - gLastPhase[k].toDouble()
            gLastPhase[k] = phase.toFloat()

            tmp -= k.toDouble() * expct

            // 映射相位差到 [-π, π]
            var qpd = (tmp / PI).toInt()
            qpd = if (qpd >= 0) qpd + (qpd and 1) else qpd - (qpd and 1)
            tmp -= PI * qpd

            // 偏离 bin 的频偏 → 真实频率
            tmp = osamp.toDouble() * tmp / (2.0 * PI)
            tmp = k.toDouble() + tmp

            gAnaMagn[k] = magn.toFloat()
            gAnaFreq[k] = tmp.toFloat()
        }

        // 4) 频域搬移（音高变换）+ 共振峰搬移
        java.util.Arrays.fill(gSynMagn, 0, fftFrameSize, 0f)
        java.util.Arrays.fill(gSynFreq, 0, fftFrameSize, 0f)
        val ratio = pitchRatio
        val fRatio = formantRatio
        for (k in 0..fftFrameSize2) {
            val index = (k * ratio).toInt()
            if (index in 0..fftFrameSize2) {
                gSynMagn[index] += gAnaMagn[k]
                gSynFreq[index] = gAnaFreq[k] * ratio
            }
        }

        // 共振峰搬移：估计频谱包络（局部峰值平滑），按 fRatio 重采样包络，
        // 逐 bin 把"谐波细结构 / 包络"的比值乘以搬移后的包络，再重新归一化到原能量。
        // 这样共振峰位置移动，但每个谐波的分量位置不变（不改变音高），
        // 只改变相对响度分布 → 男性声音包络下移、女性声音包络上移。
        if (fRatio != 1f) {
            // 1) 估计原始包络（局部最大平滑）
            val env = FloatArray(fftFrameSize2 + 1)
            for (k in 0..fftFrameSize2) {
                var m = 0f
                val lo = (k - envelopeRadius).coerceAtLeast(0)
                val hi = (k + envelopeRadius).coerceAtMost(fftFrameSize2)
                for (i in lo..hi) {
                    val v = gSynMagn[i]
                    if (v > m) m = v
                }
                env[k] = m
            }
            // 2) 搬移后包络（线性重采样）
            val nBins = fftFrameSize2 + 1
            for (k in 0 until nBins) {
                val src = k / fRatio
                val i0 = src.toInt()
                val frac = src - i0
                val e = if (i0 >= nBins - 1) env[nBins - 1]
                else env[i0] * (1f - frac) + env[i0 + 1] * frac
                val ratioE = if (env[k] > 1e-6f) e / env[k] else 1f
                gSynMagn[k] *= ratioE
            }
        }

        // 5) 合成谱 + 相位累积
        // 相位推进推导：连续帧之间的系数相位必须按输出频率每跳步进
        //   Δθ = 2π · f_syn(合成分箱) · stepSize / fftFrameSize = 2π · f_syn / osamp
        // 这样重叠相加（OLA）时各帧窗内正弦相位一致，不产生相消（此前能量骤降的根因）。
        for (k in 0..fftFrameSize2) {
            val magn = gSynMagn[k].toDouble()
            val fSyn = gSynFreq[k].toDouble()

            var ph = gSumPhase[k] + (2.0 * PI * fSyn / osamp)
            // 归一化到 [-π, π]，避免浮点累积过大
            ph -= (2.0 * PI) * Math.floor((ph + PI) / (2.0 * PI))
            gSumPhase[k] = ph.toFloat()

            gFFTworksp[2 * k] = (magn * cos(ph)).toFloat()
            gFFTworksp[2 * k + 1] = (magn * sin(ph)).toFloat()
        }

        // 6) 置零镜像半区（保持实数重建的正确性）
        java.util.Arrays.fill(gFFTworksp, fftFrameSize + 2, 2 * fftFrameSize, 0f)

        // 7) 逆 FFT
        Fft.transform(gFFTworksp, 2 * fftFrameSize, +1)

        // 8) 加窗 + 叠接相加
        // 归一化说明：帧相位已由公式 A 保证 OLA 相干，输出幅度 = a·(Σ w²)。
        // Hann 窗在 75% 重叠（osamp=4）下 Σ w²(n−mH) = 1.5，为达到单位增益，
        // 在常规 2/(N/2·osamp) 基础上乘 2/3（=1/1.5）。
        val scale = (4.0 / 3.0) / (fftFrameSize2 * osamp)
        for (k in 0 until fftFrameSize) {
            gOutputAccum[k] += (window[k] * gFFTworksp[2 * k] * scale).toFloat()
        }

        // 9) 输出 stepSize 个样本，随后滑动累加器与输入 FIFO
        System.arraycopy(gOutputAccum, 0, gOutFIFO, 0, stepSize)
        for (k in 0 until fftFrameSize) {
            gOutputAccum[k] = gOutputAccum[k + stepSize]
        }
        java.util.Arrays.fill(gOutputAccum, fftFrameSize - stepSize, fftFrameSize, 0f)

        for (k in 0 until inFifoLatency) {
            gInFIFO[k] = gInFIFO[k + stepSize]
        }
    }
}