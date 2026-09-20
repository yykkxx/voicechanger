package com.voicechanger.app.processing.openvoice

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * OpenVoice ONNX 引擎：tone converter 单模型推理。
 *
 * 后端选择：NPU(NNAPI/FP16) → GPU(NNAPI/FP16/CPU_DISABLED) → CPU(MLAS)。
 */
class OpenVoiceEngine private constructor(
    private val env: OrtEnvironment,
    private val converter: OrtSession,
    val backend: OpenVoiceBackend,
) : AutoCloseable {

    companion object {
        private const val TAG = "VC/OVEngine"

        fun open(context: Context): OpenVoiceEngine? {
            val missing = OpenVoice.ensureInstalled(context)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "models missing: $missing")
                return null
            }
            val dir = OpenVoice.dir(context)
            val env = OrtEnvironment.getEnvironment()
            var s: OrtSession? = null
            return try {
                val (b, sess) = OpenVoiceBackend.open(
                    env, File(dir, OpenVoice.FILE_CONVERTER).absolutePath, "tone_converter"
                )
                s = sess
                Log.i(TAG, "ready backend=$b")
                OpenVoiceEngine(env, sess, b)
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                runCatching { s?.close() }
                null
            }
        }
    }

    /**
     * tone converter 推理：输入 mel 特征 → 输出转换后 mel 特征。
     * 输入: [1, T, 80]  mel 特征（OpenVoice V2 用 80 mel bins）
     * 输出: [1, T, 80]  转换后 mel 特征
     */
    fun convert(melInput: FloatArray, frames: Int, melBins: Int = 80): FloatArray {
        val inputs = HashMap<String, OnnxTensor>()
        inputs["mel"] = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(melInput),
            longArrayOf(1, frames.toLong(), melBins.toLong())
        )
        val out = converter.run(inputs).use { res ->
            // 用第一个输出名获取（tone converter 通常只有一个输出）
            val outputName = converter.outputNames.first()
            val t = res.get(outputName).get() as OnnxTensor
            val shape = t.info.shape
            val n = (shape[1].toInt()) * (shape[2].toInt())
            FloatArray(n).also { copyFloat(t, it) }
        }
        inputs.values.forEach { runCatching { it.close() } }
        return out
    }

    private fun copyFloat(t: OnnxTensor, dst: FloatArray) {
        val b = t.floatBuffer
        b.rewind()
        val n = minOf(dst.size, b.remaining())
        if (n > 0) b.get(dst, 0, n)
    }

    override fun close() {
        runCatching { converter.close() }
    }
}

/** 推理后端优先级：NPU → GPU → CPU。 */
enum class OpenVoiceBackend(val id: String, val label: String) {
    NPU("npu", "NPU (NNAPI)"),
    GPU("gpu", "GPU (NNAPI/FP16)"),
    CPU("cpu", "CPU (MLAS)");

    companion object {
        val PRIORITY = listOf(NPU, GPU, CPU)

        fun createOptions(b: OpenVoiceBackend): OrtSession.SessionOptions {
            val o = OrtSession.SessionOptions()
            when (b) {
                NPU -> o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                GPU -> o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                CPU -> o.setIntraOpNumThreads(4)
            }
            return o
        }

        fun open(
            env: OrtEnvironment,
            path: String,
            tag: String,
            prefer: List<OpenVoiceBackend> = PRIORITY,
        ): Pair<OpenVoiceBackend, OrtSession> {
            var last: Throwable? = null
            for (b in prefer) {
                var opts: OrtSession.SessionOptions? = null
                try {
                    opts = createOptions(b)
                    val s = env.createSession(path, opts)
                    Log.i("VC/OVEngine", "$tag -> ${b.id}")
                    return b to s
                } catch (t: Throwable) {
                    last = t
                    Log.w("VC/OVEngine", "$tag backend ${b.id} unavailable: ${t.message}")
                    runCatching { opts?.close() }
                }
            }
            throw IllegalStateException("all backends failed for $tag: ${last?.message}", last)
        }
    }
}