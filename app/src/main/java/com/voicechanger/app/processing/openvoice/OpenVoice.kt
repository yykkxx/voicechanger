package com.voicechanger.app.processing.openvoice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * OpenVoice V2 端侧支撑：常量、模型文件管理。
 *
 * 模型（ONNX，MIT License，MyShell AI）：
 * - `tone_converter.onnx`  音色转换器（轻量，~30MB）
 *
 * 与 RVC 不同，OpenVoice 使用参考音频做 zero-shot 音色克隆，
 * 不需要多 speaker 训练，只需一段参考音频即可复制音色。
 *
 * 特点：
 * - 单模型，体积小（~30MB vs RVC 的 1.4GB）
 * - 可跑 NNAPI(NPU/GPU) 或 CPU
 * - 支持跨语言音色克隆
 */
object OpenVoice {

    private const val TAG = "VC/OpenVoice"

    const val DIR = "models/openvoice"
    const val FILE_CONVERTER = "tone_clone_model.onnx"
    const val FILE_EXTRACTOR = "tone_color_extract_model.onnx"

    private val REQUIRED = listOf(FILE_CONVERTER)

    private val EXTERNAL_SOURCES = listOf(
        "/data/adb/modules/voicechanger_priv/models",
        "/sdcard/Download/VoiceChanger/models",
    )

    fun dir(context: Context): File = File(context.filesDir, DIR)

    private val ALL_FILES = REQUIRED

    fun missing(context: Context): List<String> {
        val d = dir(context)
        return REQUIRED.filter { !File(d, it).let { f -> f.isFile && f.length() > 0 } }
    }

    fun isReady(context: Context): Boolean = missing(context).isEmpty()

    /** 从模块目录/下载目录同步模型到应用私有目录。 */
    fun ensureInstalled(context: Context): List<String> {
        val dst = dir(context)
        if (!dst.exists() && !dst.mkdirs()) return missing(context)
        for (name in ALL_FILES) {
            val target = File(dst, name)
            if (target.isFile && target.length() > 0) continue
            val src = EXTERNAL_SOURCES.asSequence()
                .flatMap { base -> sequenceOf(File(base, name), File(File(base, "openvoice"), name)) }
                .firstOrNull { it.isFile && it.length() > 0 } ?: continue
            try {
                src.inputStream().use { i -> target.outputStream().use { i.copyTo(it) } }
                Log.i(TAG, "installed $name (${target.length()} bytes)")
            } catch (t: Throwable) {
                Log.w(TAG, "install $name failed: ${t.message}")
                target.delete()
            }
        }
        return missing(context)
    }
}
