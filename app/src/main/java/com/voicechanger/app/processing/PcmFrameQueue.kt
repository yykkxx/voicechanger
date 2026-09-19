package com.voicechanger.app.processing

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 定长槽位的无锁 SPSC 帧队列，附带序号 / 时间戳元数据。
 *
 * 供 [LoopbackSocketTransport] 的 TX / RX 路径使用（严格单生产者单消费者）：
 * - TX 队列：process 线程（生产者）→ tx 线程（消费者）
 * - RX 队列：rx 线程（生产者）→ process 线程（消费者）
 *
 * 策略：写满时丢弃**新帧**并计数（与 [com.voicechanger.app.core.audio.SpscShortRingBuffer] 一致）。
 * 不做任何阻塞、不加锁；构造后无内存分配（除取帧时写入调用方缓冲）。
 */
class PcmFrameQueue(
    val capacity: Int,
    val frameSamples: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(frameSamples > 0) { "frameSamples must be > 0" }
    }

    /** 元信息输出载体（复用，避免取帧时分配）。 */
    class Meta {
        var sequence: Long = 0
        var timestampUs: Long = 0
    }

    private val samples = Array(capacity) { ShortArray(frameSamples) }
    private val sequences = LongArray(capacity)
    private val timestampsUs = LongArray(capacity)
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    /** 因写满而丢弃的新帧数量。 */
    val droppedCount = AtomicLong(0)

    fun available(): Int = writeIndex.get() - readIndex.get()

    val isEmpty: Boolean get() = available() <= 0

    /**
     * 入队一帧。返回 false 表示队列已满、本帧被丢弃。
     * 调用方可据此设置 DISCONTINUITY 标记。
     */
    fun offer(src: ShortArray, length: Int, sequence: Long, timestampUs: Long): Boolean {
        if (available() >= capacity) {
            droppedCount.incrementAndGet()
            return false
        }
        val w = writeIndex.get()
        val slot = Math.floorMod(w, capacity)
        val len = if (length > frameSamples) frameSamples else length
        System.arraycopy(src, 0, samples[slot], 0, len)
        if (len < frameSamples) {
            java.util.Arrays.fill(samples[slot], len, frameSamples, 0)
        }
        sequences[slot] = sequence
        timestampsUs[slot] = timestampUs
        writeIndex.lazySet(w + 1)
        return true
    }

    /**
     * 取出一帧写入 [dst]（要求 dst.size >= frameSamples）。
     * @return 帧序号；-1 表示队列为空。
     * [meta] 非空时填充序号与时间戳。
     */
    fun poll(dst: ShortArray, meta: Meta? = null): Long {
        if (available() <= 0) return -1
        val r = readIndex.get()
        val slot = Math.floorMod(r, capacity)
        System.arraycopy(samples[slot], 0, dst, 0, frameSamples)
        val seq = sequences[slot]
        meta?.let {
            it.sequence = seq
            it.timestampUs = timestampsUs[slot]
        }
        readIndex.lazySet(r + 1)
        return seq
    }
}