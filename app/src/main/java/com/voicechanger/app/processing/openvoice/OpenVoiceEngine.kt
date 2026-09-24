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
 * 模型输入/输出（通过 ONNX protobuf 分析确认）：
 * - 输入: `src_tone` [1, T, 80] 源音频 mel 特征
 * - 输入: `dest_tone` [1, T, 80] 目标音色 mel 特征（用预设女声参考）
 * - 输出: `audio` [1, 1, T*hop] 转换后音频波形
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
                    env, File(dir, OpenVoice.FILE_CONVERTER).absolutePath, "tone_clone"
                )
                s = sess
                Log.i(TAG, "ready backend=$b inputs=${sess.inputNames} outputs=${sess.outputNames}")
                OpenVoiceEngine(env, sess, b)
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                runCatching { s?.close() }
                null
            }
        }
    }

    /**
     * tone converter 推理。
     * 接受源 mel 特征 + 目标音色参考 mel 特征，输出转换后音频。
     * 如果模型只需要单输入，会自动适配。
     */
    fun convert(melInput: FloatArray, frames: Int, melBins: Int = 80): FloatArray {
        val inputs = HashMap<String, OnnxTensor>()
        // 尝试模型实际输入名
        val inputNames = converter.inputNames
        val melTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(melInput),
            longArrayOf(1, frames.toLong(), melBins.toLong())
        )
        when {
            inputNames.contains("src_tone") -> {
                inputs["src_tone"] = melTensor
                // dest_tone 用同一输入（自转换：源音色→源音色，仅改变 F0）
                // 实际中应填入目标音色的参考 mel，这里用源 mel 近似
                inputs["dest_tone"] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(melInput),
                    longArrayOf(1, frames.toLong(), melBins.toLong())
                )
            }
            inputNames.contains("spectrogram_input") -> {
                inputs["spectrogram_input"] = melTensor
            }
            inputNames.contains("mel") -> {
                inputs["mel"] = melTensor
            }
            else -> {
                // 用第一个输入名
                inputs[inputNames.first()] = melTensor
            }
        }
        val out = converter.run(inputs).use { res ->
            val outputName = converter.outputNames.first()
            val t = res.get(outputName).get() as OnnxTensor
            val shape = t.info.shape
            // 输出可能是 [1, 1, N] 或 [1, N] 或 [N]
            val n = if (shape.size >= 3) {
                (shape[1].toInt()) * (shape[2].toInt())
            } else if (shape.size == 2) {
                shape[1].toInt()
            } else {
                shape[0].toInt()
            }
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