package com.voicechanger.app.audio

import android.media.AudioTrack
import android.util.Log

/**
 * 包装外部创建（由 AudioPolicy.createAudioTrackSource 等创建）的 AudioTrack。
 *
 * 用于策略注入场景：track 的生命周期由 AudioPolicy 关联，
 * 本类只负责写入与状态查询，不重复创建。
 *
 * 语义与其他 Injector 对齐：
 * - open(): 校验 track 状态（已由创建者初始化，故为验证性操作）；
 * - start()/stop(): play/stop；
 * - close(): 停用并释放 track（策略注销由 backend.close 处理）。
 */
class PolicyTrackInjector(
    private var track: AudioTrack,
    private val stereoExpansion: Boolean,
) : Injector {

    companion object {
        private const val TAG = "VC/PolicyInject"
    }

    /** mono→stereo 展开缓冲（翻倍容量；write 由注入线程单线程调用）。 */
    private val stereoBuf = ShortArray(com.voicechanger.app.domain.AudioStandard.SAMPLES_PER_FRAME * 2)

    override fun open() {
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("policy track state=${track.state}")
        }
    }

    override fun start() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
    }

    override val isPlaying: Boolean
        get() = track.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun write(frame: ShortArray, length: Int): Int {
        return if (stereoExpansion) {
            // mono → stereo：展开到翻倍缓冲（不能原地写，帧只有 mono 容量）
            val buf = stereoBuf
            for (i in 0 until length) {
                buf[i * 2] = frame[i]
                buf[i * 2 + 1] = frame[i]
            }
            track.write(buf, 0, length * 2)
        } else {
            track.write(frame, 0, length)
        }
    }

    override fun writeSilence(length: Int): Int {
        val n = if (stereoExpansion) length * 2 else length
        val buf = stereoBuf
        if (n == stereoBuf.size) {
            // 整缓冲写静音：先原地清零再写入（AudioTrack 流式会异步拷贝，不能复用残留数据）
            for (i in buf.indices) buf[i] = 0
        } else {
            for (i in 0 until n) buf[i] = 0
        }
        return track.write(buf, 0, n)
    }

    override fun stop() {
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
        }
    }

    override fun close() {
        stop()
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }
}