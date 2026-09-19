package com.voicechanger.app.processing

import com.voicechanger.app.core.audio.PcmUtils
import com.voicechanger.app.domain.AudioStandard
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import com.voicechanger.app.processing.dsp.BassBoost
import com.voicechanger.app.processing.dsp.Chorus
import com.voicechanger.app.processing.dsp.Distortion
import com.voicechanger.app.processing.dsp.Echo
import com.voicechanger.app.processing.dsp.Flanger
import com.voicechanger.app.processing.dsp.FloatSoftClip
import com.voicechanger.app.processing.dsp.Presence
import com.voicechanger.app.processing.dsp.RingModulator
import com.voicechanger.app.processing.dsp.SmbPitchShifter
import com.voicechanger.app.processing.dsp.Telephone
import com.voicechanger.app.processing.dsp.TiltEq
import com.voicechanger.app.processing.dsp.Tremolo
import kotlin.math.abs
import kotlin.math.pow

/**
 * 内部处理引擎：完整 DSP 链（Phase 3 实现）。
 *
 * 处理顺序：
 * ```
 * 输入 → 增益 → 噪声门 → 音高（SMB 相位声码器）→ EQ 倾斜
 *      → 环形调制（机器人）→ 失真（怪兽）→ 回声 → 输出增益 → 软限幅
 * ```
 *
 * 全部使用 float 工作缓冲，帧内无分配；音高变换器默认开启，
 * pitch=0 时旁路（ratio 1 仍会有 ~21ms 固定延迟，为保持延迟恒定，
 * 该延迟对注入链路可接受；不期望变声的用户请用 PASSTHROUGH 模式）。
 */
class InternalDspProcessor : ProcessorEngine {

    companion object {
        /** 音高滑音时间（毫秒）：参数切换时线性过渡，避免爆音/阶梯感。 */
        private const val PITCH_GLIDE_MS = 60
    }

    @Volatile
    private var params: EffectParams = EffectParams()

    private var inputGain: Float = 1f
    private var outputGain: Float = 1f
    private var gateEnabled: Boolean = false
    private var gateLinear: Float = 0f
    private var limiterEnabled: Boolean = true

    // DSP 组件（实时线程所有者）
    private val pitchShifter = SmbPitchShifter(
        // 512/4：算法延迟减半（~10.6ms），对实时返听更友好（plan/09 第 7.8 节：延迟优先）
        fftFrameSize = 512,
        osamp = 4,
        sampleRate = AudioStandard.SAMPLE_RATE,
    )
    private val tiltEq = TiltEq(AudioStandard.SAMPLE_RATE)
    private val ringMod = RingModulator(AudioStandard.SAMPLE_RATE, frequency = 90f)
    private val distortion = Distortion()
    private val echo = Echo(AudioStandard.SAMPLE_RATE, maxDelayMs = 600)

    // 会话 #9 新增：专业人声调制效果器
    private val chorus = Chorus(AudioStandard.SAMPLE_RATE)
    private val flanger = Flanger(AudioStandard.SAMPLE_RATE)
    private val tremolo = Tremolo(AudioStandard.SAMPLE_RATE)
    private val telephone = Telephone(AudioStandard.SAMPLE_RATE)
    private val bassBoost = BassBoost(AudioStandard.SAMPLE_RATE)
    private val presence = Presence(AudioStandard.SAMPLE_RATE)

    // 帧内工作缓冲（构造时分配一次）
    private val floatBuf = FloatArray(AudioStandard.SAMPLES_PER_FRAME)
    private val pitchBuf = FloatArray(AudioStandard.SAMPLES_PER_FRAME)

    // 音高滑音状态
    private var currentPitch = 1f
    private var targetPitch = 1f
    private val pitchGlideStep: Float = (AudioStandard.SAMPLES_PER_FRAME.toFloat() / (PITCH_GLIDE_MS * AudioStandard.SAMPLE_RATE / 1000f))

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
        // 初始就位（避免首帧滑音）
        currentPitch = semitonesToRatio(params.pitchSemitones)
        targetPitch = currentPitch
        pitchShifter.pitchRatio = currentPitch
        pitchShifter.formantRatio = params.formantRatio
    }

    override fun update(params: EffectParams) {
        this.params = params
        inputGain = PcmUtils.dbToLinear(params.inputGainDb)
        outputGain = PcmUtils.dbToLinear(params.outputGainDb)
        limiterEnabled = params.limiterEnabled
        gateEnabled = params.noiseGateThresholdDb > -55f
        gateLinear = PcmUtils.dbToLinear(params.noiseGateThresholdDb)

        tiltEq.tiltGain = PcmUtils.dbToLinear(params.eqTiltDb)
        ringMod.amount = params.robotAmount.coerceIn(0f, 1f)
        distortion.amount = params.drive.coerceIn(0f, 1f)
        echo.amount = params.echoAmount.coerceIn(0f, 1f)
        echo.delayMs = params.echoDelayMs
        echo.feedback = params.echoFeedback

        // 会话 #9 新增效果器参数
        chorus.amount = params.chorusAmount.coerceIn(0f, 1f)
        flanger.amount = params.flangerAmount.coerceIn(0f, 1f)
        tremolo.amount = params.tremoloAmount.coerceIn(0f, 1f)
        telephone.amount = params.telephoneAmount.coerceIn(0f, 1f)
        bassBoost.gainDb = params.bassBoostDb.coerceIn(-12f, 12f)
        presence.gainDb = params.presenceDb.coerceIn(-12f, 12f)

        targetPitch = semitonesToRatio(params.pitchSemitones)
        pitchShifter.formantRatio = params.formantRatio
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        val p = params

        // 0) 短音程到 float（-1..1）
        for (i in 0 until length) {
            floatBuf[i] = input[i] / 32768f
        }

        // 1) 输入增益
        if (inputGain != 1f) {
            val g = inputGain
            for (i in 0 until length) floatBuf[i] *= g
        }

        // 2) 噪声门（统计输入能量）
        if (gateEnabled) {
            var acc = 0f
            for (i in 0 until length) acc += floatBuf[i] * floatBuf[i]
            val rms = kotlin.math.sqrt(acc / length)
            if (rms < gateLinear) {
                java.util.Arrays.fill(output, 0, length, 0)
                return length
            }
        }

        // 3) 音高（带滑音；ratio==1 且已稳定时旁路以省 CPU）
        if (abs(targetPitch - 1f) > 0.001f || abs(currentPitch - 1f) > 0.001f) {
            if (abs(currentPitch - targetPitch) > 0.001f) {
                val dir = if (targetPitch > currentPitch) 1f else -1f
                val maxStep = pitchGlideStep * abs(targetPitch - currentPitch).coerceAtLeast(0.001f) * 8f
                currentPitch += dir * maxStep.coerceAtMost(abs(targetPitch - currentPitch))
                pitchShifter.pitchRatio = currentPitch
            }
            pitchShifter.process(floatBuf, pitchBuf, length)
        } else {
            System.arraycopy(floatBuf, 0, pitchBuf, 0, length)
        }

        // 4) EQ 倾斜
        tiltEq.process(pitchBuf, length)

        // 4.5) 低音增强 + 临场感（会话 #9：音色塑形前置，避免被后续调制削弱）
        if (p.bassBoostDb != 0f) bassBoost.process(pitchBuf, length)
        if (p.presenceDb != 0f) presence.process(pitchBuf, length)

        // 5) 环形调制（机器人）
        if (p.robotAmount > 0f) ringMod.process(pitchBuf, length)

        // 6) 失真（怪兽）
        if (p.drive > 0f) distortion.process(pitchBuf, length)

        // 6.5) 电话音（会话 #9：带通塑形，与失真互补）
        if (p.telephoneAmount > 0f) telephone.process(pitchBuf, length)

        // 7) 回声（空灵）
        if (p.echoAmount > 0f) echo.process(pitchBuf, length)

        // 7.5) 合唱 / 镶边 / 颤音（会话 #9：时间调制类，放在回声后避免反馈污染）
        if (p.chorusAmount > 0f) chorus.process(pitchBuf, length)
        if (p.flangerAmount > 0f) flanger.process(pitchBuf, length)
        if (p.tremoloAmount > 0f) tremolo.process(pitchBuf, length)

        // 8) 输出增益 + 软限幅
        if (outputGain != 1f) {
            val g = outputGain
            for (i in 0 until length) pitchBuf[i] *= g
        }
        if (limiterEnabled) FloatSoftClip.process(pitchBuf, length)

        // 9) float → short
        for (i in 0 until length) {
            val v = (pitchBuf[i] * 32767f).toInt()
            output[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return length
    }

    override fun reset(discontinuity: Boolean) {
        pitchShifter.reset()
        tiltEq.reset()
        ringMod.reset()
        distortion.reset()
        echo.reset()
        chorus.reset()
        flanger.reset()
        tremolo.reset()
        telephone.reset()
        bassBoost.reset()
        presence.reset()
        currentPitch = targetPitch
        pitchShifter.pitchRatio = currentPitch
    }

    override fun algorithmicDelayFrames(): Int = 2 // ~21ms，按 10ms 帧向上取整

    private fun semitonesToRatio(semitones: Float): Float =
        2f.pow(semitones / 12f)
}