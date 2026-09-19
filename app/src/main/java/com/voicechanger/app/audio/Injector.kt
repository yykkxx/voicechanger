package com.voicechanger.app.audio

/**
 * 注入抽象：向目标路由写 PCM 的统一接口。
 *
 * 两种实现：
 * - [InjectionEndpoint]：设备注入（AudioTrack.preferredDevice = 虚拟设备/监听设备）；
 * - PolicyInjector（见 route/PolicyRecorderMixBackend.kt）：策略注入，
 *   AudioTrack 由 `AudioPolicy.createAudioTrackSource(mix)` 创建，写入即进入目标录音链路。
 *
 * 生命周期（由服务串行控制）：
 * ```
 * open() → start() → [write()/writeSilence() 持续] → stop() → close()
 * ```
 * 所有方法幂等。
 */
interface Injector : AutoCloseable {

    /** 创建资源（可重复调用）。失败抛异常。 */
    fun open()

    /** 开始接收写入（play）。 */
    fun start()

    val isPlaying: Boolean

    /**
     * 写入一帧 mono 48k S16LE。实现方按需展开为 stereo。
     * 返回写入的样本数（mono 计）；负值 = 错误码，调用方应降级为静音。
     */
    fun write(frame: ShortArray, length: Int): Int

    /** 写入静音（正确的通道数由实现处理）。 */
    fun writeSilence(length: Int): Int

    fun stop()

    override fun close()
}