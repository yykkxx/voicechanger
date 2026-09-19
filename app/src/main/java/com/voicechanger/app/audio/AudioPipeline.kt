package com.voicechanger.app.audio

import android.util.Log
import com.voicechanger.app.core.audio.SpscShortRingBuffer
import com.voicechanger.app.domain.AudioStandard
import com.voicechanger.app.domain.PipelineMetrics
import com.voicechanger.app.processing.ProcessorEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 实时音频流水线（plan/01 第 3.4、5 节）：
 *
 * ```
 * CaptureEndpoint → input ring → processor-thread → output ring → InjectionEndpoint
 * ```
 *
 * - 全程固定帧（10 ms）与预分配缓冲，无每帧分配；
 * - input ring 满时丢弃并计数（drop-newest），保证采集不被处理端拖死；
 * - output 侧由注入线程消费；空时写静音帧并计数 underrun（避免重复旧人声）。
 */
class AudioPipeline(
    private val processor: ProcessorEngine,
    private val inputRing: SpscShortRingBuffer =
        SpscShortRingBuffer(AudioStandard.DEFAULT_QUEUE_FRAMES, AudioStandard.SAMPLES_PER_FRAME),
    private val outputRing: SpscShortRingBuffer =
        SpscShortRingBuffer(AudioStandard.DEFAULT_QUEUE_FRAMES * 4, AudioStandard.SAMPLES_PER_FRAME),
) {
    companion object {
        private const val TAG = "VC/Pipeline"
    }

    private val running = AtomicBoolean(false)
    private var processThread: Thread? = null
    private var injectThread: Thread? = null

    private val captureFrames = AtomicLong(0)
    private val processedFrames = AtomicLong(0)
    private val avgProcessUs = AtomicLong(0)
    private val discontinuities = AtomicLong(0)

    @Volatile
    private var lastRms: Float = 0f

    /** 注入器由控制面在 start 前设置。 */
    var injector: com.voicechanger.app.audio.Injector? = null

    /** 可选返听注入器（耳机/听筒；null = 关闭返听）。 */
    @Volatile
    var monitorInjector: com.voicechanger.app.audio.Injector? = null

    /**
     * 输出闸门：false 时丢弃处理结果（不写注入端），但保持采集/处理节奏。
     * monitor 后端下返听开关的实体：关 = 不听处理后人声（plan/09 第 7.7 节）。
     */
    @Volatile
    var outputEnabled: Boolean = true

    /** 输入降噪（高通 + 自适应噪声门），在采集帧写入 input ring 前应用。 */
    val noiseSuppressor = com.voicechanger.app.core.audio.NoiseSuppressor(AudioStandard.SAMPLE_RATE)

    /** 输入降噪开关（默认开）。 */
    @Volatile
    var noiseEnabled: Boolean = true

    // 降噪工作缓冲（构造时分配一次，采集线程复用）
    private val noiseFloatBuf = FloatArray(AudioStandard.SAMPLES_PER_FRAME)

    /** short→float（S16 归一化到 [-1,1]）。 */
    private fun noiseFrameShortToFloat(src: ShortArray, dst: FloatArray, len: Int) {
        for (i in 0 until len) dst[i] = src[i] / 32768f
    }

    /** float→short（[-1,1] → S16，限幅防溢出）。 */
    private fun noiseFloatToShort(src: FloatArray, dst: ShortArray, len: Int) {
        for (i in 0 until len) {
            val v = (src[i] * 32768f).coerceIn(-32768f, 32767f)
            dst[i] = v.toInt().toShort()
        }
    }

    /** 输入直接来自 CaptureEndpoint 的采集线程；该方法必须无阻塞。 */
    fun pushCaptureFrame(frame: ShortArray): Boolean {
        captureFrames.incrementAndGet()
        lastRms = com.voicechanger.app.core.audio.PcmUtils.rms(frame, frame.size)
        // 输入降噪（对直通/变声/回环所有模式生效）
        if (noiseEnabled) {
            noiseFrameShortToFloat(frame, noiseFloatBuf, frame.size)
            noiseSuppressor.process(noiseFloatBuf, frame.size)
            noiseFloatToShort(noiseFloatBuf, frame, frame.size)
        }
        val ok = inputRing.write(frame)
        if (!ok) {
            discontinuities.incrementAndGet()
            Log.w(TAG, "input ring full, frame dropped")
        }
        return ok
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        processThread = Thread({ processLoop() }, "vc-process").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        injectThread = Thread({ injectLoop() }, "vc-inject").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "pipeline started")
    }

    fun stop() {
        running.set(false)
        processThread?.join(500)
        injectThread?.join(500)
        processThread = null
        injectThread = null
        inputRing.clear()
        outputRing.clear()
        Log.i(TAG, "pipeline stopped")
    }

    private fun processLoop() {
        val inFrame = ShortArray(AudioStandard.SAMPLES_PER_FRAME)
        val outFrame = ShortArray(AudioStandard.SAMPLES_PER_FRAME)
        while (running.get()) {
            val n = inputRing.read(inFrame)
            if (n <= 0) {
                // 无输入：短睡降低空转（10 ms 帧级别）
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            val t0 = System.nanoTime()
            val outN = processor.process(inFrame, n, outFrame)
            val dtUs = (System.nanoTime() - t0) / 1000
            // 指数滑动平均
            avgProcessUs.set((avgProcessUs.get() * 7 + dtUs) / 8)
            processedFrames.incrementAndGet()
            if (outN > 0) {
                outputRing.write(outFrame, outN)
            }
        }
    }

    private fun injectLoop() {
        val maxBatch = 6
        val batch = ShortArray(AudioStandard.SAMPLES_PER_FRAME * maxBatch)
        val monitorBuf = ShortArray(AudioStandard.SAMPLES_PER_FRAME * maxBatch)
        var loopCount = 0L

        // 固定 tick 自节拍（10ms），数据驱动：每 tick 从 outputRing 尽可能读批写入，
        // 与 AudioTrack 内部状态完全解耦（playbackHeadPosition 在部分虚拟/低延迟
        // 输出上卡 0/不前进，水位法、时间节拍法都会被 write 阻塞带偏）。
        // 注入速率 = 处理产出速率 = 采集速率，三者自动一致。
        val tickMs = 10L
        var nextTickNanos = System.nanoTime()
        var emptyTicks = 0L
        while (running.get()) {
            val inj = injector
            if (inj == null || !inj.isPlaying) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                nextTickNanos = System.nanoTime()
                continue
            }

            // 统一 10ms 节拍：与 process 产出（10ms/帧）同频，读当前所有可写帧写入，
            // 注入节奏跟随处理节奏，避免写批后长时间空转造成缓冲忽满忽空。
            waitUntil(nextTickNanos)
            nextTickNanos += tickMs * 1_000_000L

            // 批量消费：一次读最多 6 帧（60ms）；readMulti 支持部分帧（≥1 即返回），
            // inject 与 process 产出同步，不会因凑不满批而空等。
            val nFrames = outputRing.readMulti(batch, maxBatch)
            val nSamples = nFrames * AudioStandard.SAMPLES_PER_FRAME
            if (nSamples <= 0) {
                // 输出暂时没有新数据（处理端尚未产出）：轻量让出，不计 underrun
                // （只要采集持续，处理/注入就会跟上；真实欠载由 cap 与 writes 差值体现）
                if (++emptyTicks > 20) {
                    underrunCounter.incrementAndGet()
                    emptyTicks = 0
                }
                continue
            }
            emptyTicks = 0

            // 输出闸门：关闭时丢弃数据（保持采集/处理节奏，但不触发回放）
            if (!outputEnabled) {
                continue
            }

            // 返听副本（独立缓冲：避免主注入器的 stereo 展开污染共享数组）
            val mon = monitorInjector
            if (mon != null && mon.isPlaying) {
                System.arraycopy(batch, 0, monitorBuf, 0, nSamples)
                mon.write(monitorBuf, nSamples)
            }

            val wrote = inj.write(batch, nSamples)
            if (wrote < 0) {
                // 注入端错误：保持静音，等待控制面处理
                inj.writeSilence(AudioStandard.SAMPLES_PER_FRAME)
                Log.w(TAG, "inject write error $wrote")
            }

            // 周期健康日志（plan/09 第 7.4 节）：每 100 tick（约 1s）打一次
            if (++loopCount % 100 == 0L) {
                val m = snapshotMetrics()
                val injStats = (inj as? InjectionEndpoint)?.let {
                    "writes=${it.totalWrites} samples=${it.writtenSamples} err=${it.writeErrors}"
                } ?: "inj=n/a"
                Log.i(
                    TAG,
                    "health cap=${m.captureFrames} proc=${m.processedFrames} " +
                        "under=${m.underruns} dropOut=${m.outputDropped} dropIn=${m.inputDropped} " +
                        "rms=${"%.3f".format(m.rms)} $injStats",
                )
            }
        }
    }

    /** 忙等/休眠到目标时刻（纳秒），绝对时间避免 drift。 */
    private fun waitUntil(targetNanos: Long) {
        var remaining = targetNanos - System.nanoTime()
        if (remaining <= 0) return
        while (remaining > 1_000_000L) {
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining = targetNanos - System.nanoTime()
        }
        // 最后 <1ms 忙等，保证 tick 精度
        while (System.nanoTime() < targetNanos) {
            // spin
        }
    }

    private val underrunCounter = AtomicLong(0)

    fun snapshotMetrics(): PipelineMetrics = PipelineMetrics(
        captureFrames = captureFrames.get(),
        processedFrames = processedFrames.get(),
        inputDropped = inputRing.droppedCount.get(),
        outputDropped = outputRing.droppedCount.get(),
        underruns = underrunCounter.get(),
        discontinuities = discontinuities.get(),
        avgProcessUs = avgProcessUs.get(),
        rms = lastRms,
    )
}