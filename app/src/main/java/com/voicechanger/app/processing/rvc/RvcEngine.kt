package com.voicechanger.app.processing.rvc

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet

/** 推理后端优先级：NPU(NNAPI) → GPU(NNAPI+FP16+CPU_DISABLED) → CPU(XNNPACK)。 */
enum class RvcBackend(val id: String, val label: String) {
    NPU("npu", "NPU (NNAPI)"),
    GPU("gpu", "GPU (NNAPI/FP16)"),
    CPU("cpu", "CPU (MLAS×N)");

    companion object {
        val PRIORITY = listOf(NPU, GPU, CPU)

        fun createOptions(b: RvcBackend): OrtSession.SessionOptions {
            val o = OrtSession.SessionOptions()
            when (b) {
                NPU -> o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                GPU -> o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                CPU -> {
                    // 只用多线程 MLAS（实测：XNNPACK 在本模型的 conv/GRU 组合下更慢，RMVPE 差 10 倍）
                    o.setIntraOpNumThreads(4)
                }
            }
            return o
        }

        fun open(
            env: OrtEnvironment,
            path: String,
            tag: String,
            prefer: List<RvcBackend> = PRIORITY,
        ): Pair<RvcBackend, OrtSession> {
            var last: Throwable? = null
            for (b in prefer) {
                var opts: OrtSession.SessionOptions? = null
                try {
                    opts = createOptions(b)
                    val s = env.createSession(path, opts)
                    Log.i("VC/RvcEP", "$tag -> ${b.id}")
                    return b to s
                } catch (t: Throwable) {
                    last = t
                    Log.w("VC/RvcEP", "$tag backend ${b.id} unavailable: ${t.message}")
                    runCatching { opts?.close() }
                }
            }
            throw IllegalStateException("all backends failed for $tag: ${last?.message}", last)
        }
    }
}

/**
 * RVC 三模型 ONNX 会话（hubert / rmvpe / net_g48k）。
 * 全部实数算子，无复数/自定义算子 → 可跑 NNAPI(NPU)、GPU、CPU。
 */
class RvcEngine private constructor(
    private val env: OrtEnvironment,
    private val hubert: OrtSession,
    private val rmvpe: OrtSession,
    private val netG: OrtSession,
    private val mel: Rvc.MelFrontend,
    private val frames: Int,
    val backend: RvcBackend,
) : AutoCloseable {

    companion object {
        private const val TAG = "VC/RvcEngine"

        fun open(context: Context, variant: Rvc.Variant): RvcEngine? {
            val missing = Rvc.ensureInstalled(context)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "models missing: $missing")
                return null
            }
            val melConst = Rvc.loadMelConst(context) ?: return null
            val dir = Rvc.dir(context)
            val env = OrtEnvironment.getEnvironment()
            var h: OrtSession? = null
            var r: OrtSession? = null
            var g: OrtSession? = null
            return try {
                val (bh, sh) = RvcBackend.open(env, File(dir, Rvc.FILE_HUBERT).absolutePath, "hubert")
                h = sh
                // RMVPE 含 BiGRU/反卷积，NNAPI 会大量回退到 CPU 反而更慢 → 直接走 CPU(XNNPACK)
                val (br, sr) = RvcBackend.open(
                    env, File(dir, Rvc.rmvpeFile(context, variant.frames)).absolutePath, "rmvpe_" + variant.frames,
                    prefer = listOf(RvcBackend.CPU) + RvcBackend.PRIORITY,
                )
                r = sr
                // net_g：先试 GPU(NNAPI/FP16)、再 CPU(MLAS)；两者都记录耗时以便对比选优
                val (bg, sg) = RvcBackend.open(
                    env, File(dir, variant.file).absolutePath, "net_g",
                    prefer = listOf(RvcBackend.GPU, RvcBackend.CPU, RvcBackend.NPU),
                )
                g = sg
                val backend = if (bh == br && br == bg) bh else RvcBackend.CPU
                Log.i(TAG, "ready backend=$backend (h=$bh r=$br g=$bg) L=${variant.frames}")
                RvcEngine(env, sh, sr, sg, Rvc.MelFrontend(melConst), variant.frames, backend)
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                runCatching { h?.close() }
                runCatching { r?.close() }
                runCatching { g?.close() }
                null
            }
        }
    }

    private var sid: Long = 0

    fun setSpeaker(id: Long) {
        sid = id.coerceIn(0, 108)
    }

    /** 16k wav（[-1,1]）→ 内容特征 [T][768]（50 Hz）。 */
    fun hubertFeatures(wav: FloatArray): Array<FloatArray> {
        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_values"] =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(wav), longArrayOf(1, wav.size.toLong()))
        return hubert.run(inputs).use { res ->
            val t = res.get("features").get() as OnnxTensor
            val shape = t.info.shape
            val frames = shape[1].toInt()
            val dim = shape[2].toInt()
            val flat = FloatArray(frames * dim)
            copyFloat(t, flat)
            Array(frames) { i -> flat.copyOfRange(i * dim, (i + 1) * dim) }
        }
    }

    /** mel[128][T] → RMVPE hidden [Tp][360]。 */
    fun rmvpeHidden(melIn: Array<FloatArray>): Array<FloatArray> {
        val rows = melIn.size
        val cols = melIn[0].size
        val flat = FloatArray(rows * cols)
        for (r in 0 until rows) System.arraycopy(melIn[r], 0, flat, r * cols, cols)
        val inputs = HashMap<String, OnnxTensor>()
        inputs["mel"] =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, rows.toLong(), cols.toLong()))
        return rmvpe.run(inputs).use { res ->
            val t = res.get("hidden").get() as OnnxTensor
            val shape = t.info.shape
            val frames = shape[1].toInt()
            val bins = shape[2].toInt()
            val f = FloatArray(frames * bins)
            copyFloat(t, f)
            Array(frames) { i -> f.copyOfRange(i * bins, (i + 1) * bins) }
        }
    }

    /** net_g：phone[L*768] + pitch[L] + nsff0[L] → 48k 音频（L*480 样本）。 */
    fun synthesize(phone: FloatArray, pitch: LongArray, nsff0: FloatArray): FloatArray {
        val l = pitch.size
        val inputs = HashMap<String, OnnxTensor>()
        inputs["phone"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, l.toLong(), 768))
        inputs["lengths"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(l.toLong())), longArrayOf(1))
        inputs["pitch"] = OnnxTensor.createTensor(env, LongBuffer.wrap(pitch), longArrayOf(1, l.toLong()))
        inputs["nsff0"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(nsff0), longArrayOf(1, l.toLong()))
        inputs["sid"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sid)), longArrayOf(1))
        val out = netG.run(inputs).use { res ->
            val t = res.get("audio").get() as OnnxTensor
            val shape = t.info.shape
            val n = shape[shape.size - 1].toInt()
            FloatArray(n).also { copyFloat(t, it) }
        }
        inputs.values.forEach { runCatching { it.close() } }
        return out
    }

    fun melFrontend(): Rvc.MelFrontend = mel

    private fun copyFloat(t: OnnxTensor, dst: FloatArray) {
        val b = t.floatBuffer
        b.rewind()
        val n = minOf(dst.size, b.remaining())
        if (n > 0) b.get(dst, 0, n)
    }

    override fun close() {
        runCatching { hubert.close() }
        runCatching { rmvpe.close() }
        runCatching { netG.close() }
    }
}