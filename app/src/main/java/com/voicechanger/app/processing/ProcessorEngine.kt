package com.voicechanger.app.processing

import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.StreamFormat

/**
 * 处理引擎统一接口：内部 DSP 与本地端口处理实现同一契约（见 plan/03 第 1 节）。
 *
 * 约束：
 * - [process] 运行在实时音频线程：禁止分配、I/O、加锁等阻塞操作；
 * - 参数通过 [update] 提交，内部以不可变快照在帧边界生效；
 * - discontinuity 时调用 [reset]，重置需要连续历史的状态。
 */
interface ProcessorEngine : AutoCloseable {

    /** 以当前流格式初始化。可能抛异常表示初始化失败。 */
    fun configure(format: StreamFormat, params: EffectParams)

    /**
     * 处理一帧：输入 [input] / [length]，输出写入 [output]。
     * 返回输出样本数；0 表示本帧无输出（如静音策略）。
     *
     * [output] 缓冲由调用方分配且长度 >= length。
     */
    fun process(input: ShortArray, length: Int, output: ShortArray): Int

    /** 提交新参数（任意线程调用，实时线程帧边界读取）。 */
    fun update(params: EffectParams)

    /** 重置内部状态（discontinuity = true 表示发生了丢帧/重连）。 */
    fun reset(discontinuity: Boolean)

    /** 引擎稳定输出延迟（帧数），控制面使用。 */
    fun algorithmicDelayFrames(): Int = 0

    override fun close() {}
}