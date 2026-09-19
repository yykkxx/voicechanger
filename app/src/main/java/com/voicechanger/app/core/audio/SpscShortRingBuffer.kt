package com.voicechanger.app.core.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * 单生产者单消费者（SPSC）短整型环形缓冲，固定帧采样（frameSamples）。
 *
 * 契约（与既有测试 `SpscShortRingBufferTest` 对齐，plan/03 第 2.4 节）：
 * - [write] 满时丢弃**新帧**（drop-newest），不覆盖旧数据，并 [droppedCount] 计数；
 * - [write] 允许短帧：只写 length 个样本，帧尾部补零（保持帧边界对齐）；
 * - [read] 按帧粒度消费，返回实际拷贝的样本数（不足一帧返回剩余数，空返回 0）；
 * - [readMulti] 批量读取完整帧（供注入端一次 AudioTrack.write 消费多帧）；
 * - 全程无锁、无双端竞态（调用方保证单生产者/单消费者）；
 * - 常量副本无分配：写入除帧间补零外零循环，读取用 System.arraycopy。
 */
class SpscShortRingBuffer(
    /** 容量（帧数）。 */
    val capacity: Int,
    /** 每帧样本数。 */
    val frameSamples: Int,
) {
    private val buffer = ShortArray(capacity * frameSamples)
    private var writeIndex = 0L
    private var readIndex = 0L

    /** 因满被丢弃的帧数（drop-newest 策略）。 */
    val droppedCount = AtomicLong(0)

    val isEmpty: Boolean
        get() = writeIndex == readIndex

    /** 当前可读样本数。 */
    fun available(): Int = (writeIndex - readIndex).toInt()

    fun clear() {
        writeIndex = 0L
        readIndex = 0L
    }

    /** 写入一帧（长度为 [frameSamples]）。满时丢弃并返回 false。 */
    fun write(frame: ShortArray): Boolean = write(frame, frameSamples)

    /**
     * 写入一帧（可短帧）：只消费 length 个样本，帧尾自动补零。
     * 满时丢弃整帧（不写任何样本）并返回 false。
     */
    fun write(frame: ShortArray, length: Int): Boolean {
        val n = length.coerceAtMost(frameSamples)
        val size = buffer.size.toLong()
        if (writeIndex - readIndex + frameSamples > size) {
            droppedCount.incrementAndGet()
            return false
        }
        val pos = (writeIndex % size).toInt()
        val first = minOf(n, buffer.size - pos)
        System.arraycopy(frame, 0, buffer, pos, first)
        if (first < n) {
            System.arraycopy(frame, first, buffer, 0, n - first)
        }
        // 尾部补零（保持帧边界：读取侧永远按 frameSamples 消费）
        if (n < frameSamples) {
            var p = (pos + n) % buffer.size
            var remain = frameSamples - n
            while (remain-- > 0) {
                buffer[p] = 0
                p = if (p + 1 == buffer.size) 0 else p + 1
            }
        }
        writeIndex += frameSamples
        return true
    }

    /**
     * 读取一帧填充 out，返回实际样本数（空返回 0；尾帧不足返回剩余数）。
     * 推进按实际读取的样本数（非整帧）进行，保证返回与 [available] 自洽。
     */
    fun read(out: ShortArray): Int {
        val avail = writeIndex - readIndex
        if (avail <= 0) return 0
        val n = minOf(avail, frameSamples.toLong()).toInt()
        val pos = (readIndex % buffer.size).toInt()
        val first = minOf(n, buffer.size - pos)
        System.arraycopy(buffer, pos, out, 0, first)
        if (first < n) {
            System.arraycopy(buffer, 0, out, first, n - first)
        }
        readIndex += n
        return n
    }

    /**
     * 批量读取最多 [maxFrames] 个完整帧到 out（容量需 ≥ maxFrames×frameSamples），
     * 返回读取的完整帧数（不足一帧的尾数据不消费，留给下次 [read]）。
     */
    fun readMulti(out: ShortArray, maxFrames: Int): Int {
        val avail = writeIndex - readIndex
        if (avail <= 0) return 0
        val frames = minOf(maxFrames.toLong(), avail / frameSamples).toInt()
        if (frames <= 0) return 0
        val n = frames * frameSamples
        val pos = (readIndex % buffer.size).toInt()
        val first = minOf(n, buffer.size - pos)
        System.arraycopy(buffer, pos, out, 0, first)
        if (first < n) {
            System.arraycopy(buffer, 0, out, first, n - first)
        }
        readIndex += n
        return frames
    }
}