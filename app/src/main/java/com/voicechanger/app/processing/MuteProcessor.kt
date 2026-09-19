package com.voicechanger.app.processing

import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat
import java.util.Arrays

/**
 * 故障安全处理器：持续输出静音（plan/01 第 3.5 节、plan/03 第 5 节）。
 * 用于外部处理器断开、电话抢占等场景，避免把原声或旧数据泄漏到通话中。
 */
class MuteProcessor : ProcessorEngine {

    override fun configure(format: StreamFormat, params: EffectParams) = Unit

    override fun update(params: EffectParams) = Unit

    override fun process(input: ShortArray, length: Int, output: ShortArray): Int {
        Arrays.fill(output, 0, length, 0)
        return length
    }

    override fun reset(discontinuity: Boolean) = Unit
}