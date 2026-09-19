package com.voicechanger.app.processing

import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat

/**
 * 直通处理器：用于路由与延迟基线（plan/03 第 2.1 节第 1 步）。
 * 行为：output = input，可选输入/输出增益与限幅。
 */
class PassthroughProcessor : ProcessorEngine {

    @Volatile
    private var params: EffectParams = EffectParams()

    private var inputGain: Float = 1f
    private var outputGain: Float = 1f
    private var limiterEnabled: Boolean = true

    override fun configure(format: StreamFormat, params: EffectParams) {
        update(params)
    }

    override fun update(params: EffectParams) {
        this.params = params
        // 在非实时线程完成 dB→linear 换算
        inputGain = com.voicechanger.app.core.audio.PcmUtils.dbToLinear(params.inputGainDb)
        outputGain = com.voicechanger.app.core.audio.PcmUtils.dbToLinear(params.outputGainDb)
        limiterEnabled = params.limiterEnabled
    }

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        System.arraycopy(input, 0, output, 0, length)
        val p = params
        if (inputGain != 1f) {
            com.voicechanger.app.core.audio.PcmUtils.applyGainInPlace(output, length, inputGain)
        }
        if (outputGain != 1f) {
            com.voicechanger.app.core.audio.PcmUtils.applyGainInPlace(output, length, outputGain)
        }
        if (limiterEnabled) {
            com.voicechanger.app.core.audio.PcmUtils.softClipInPlace(output, length)
        }
        return length
    }

    override fun reset(discontinuity: Boolean) {
        // 无内部连续状态
    }
}