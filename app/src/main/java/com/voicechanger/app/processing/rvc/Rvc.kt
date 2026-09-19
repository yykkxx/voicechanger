package com.voicechanger.app.processing.rvc

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * RVC（Retrieval-based Voice Conversion）端侧支撑：常量、模型文件管理、mel 前端与 f0 解码。
 *
 * 模型（ONNX，全部实数算子，可跑 NNAPI/GPU/CPU）：
 * - `hubert.onnx`   16k wav → [1,T,768]（50Hz 内容特征）
 * - `rmvpe.onnx`    mel128   → [1,T,360]（音高显著度）
 * - `net_g48k.onnx` (phone,pitch,nsff0,sid) → 48k 音频（NSF-HiFiGAN 解码器）
 * - `rmvpe_mel.bin` mel 基矩阵 + hann 窗（端侧 STFT 用）
 */
object Rvc {

    private const val TAG = "VC/Rvc"

    // ---- 分块参数（必须与 ONNX 导出时一致）----
    /** net_g 默认帧数（100 帧 × 10ms = 1s）。注意力含固定长度常量，必须恒定。 */
    const val L = 100
    const val SAMPLE_RATE = 48000
    const val MODEL_SR = 16000
    /** net_g 每帧输出样本数（48k）：1/100 s → 480 */
    const val HOP = 480
    /** 每帧对应的 16k 输入样本数 */
    const val FRAME_16K = 160
    /** L=100 时每次推理处理的 16k 样本数：100 帧 × 160 = 1 s */
    const val WINDOW_16K = 16000
    const val HUBERT_STRIDE = 320
    const val N_MELS = 128
    const val N_FFT = 1024
    const val MEL_HOP = 160
    const val F0_MIN = 50.0
    const val F0_MAX = 1100.0

    // ---- 模型文件 ----
    const val DIR = "models/rvc"
    const val FILE_HUBERT = "hubert.onnx"
    const val FILE_RMVPE = "rmvpe.onnx"
    const val FILE_NETG = "net_g48k.onnx"
    const val FILE_NETG_L50 = "net_g48k_L50.onnx"
    const val FILE_NETG_L32 = "net_g48k_L32.onnx"
    const val FILE_NETG32_L32 = "net_g32k_L32.onnx"
    const val FILE_MEL = "rmvpe_mel.bin"

    private val REQUIRED = listOf(FILE_HUBERT, FILE_RMVPE, FILE_MEL)

    /**
     * net_g 变体：窗口帧数 L 越小 → 单次推理越短、延迟越低（质量基本相同）。
     * 按「延迟优先」排序，运行期取第一个存在的变体。
     */
    data class Variant(
        val frames: Int,
        val file: String,
        val stepFrames: Int,
        val sr: Int = SAMPLE_RATE,
    ) {
        val window16k: Int get() = frames * FRAME_16K
        val step16k: Int get() = stepFrames * FRAME_16K
        /** 每帧输出样本数（32k 模型 = 320，48k = 480）。 */
        val samplesPerFrame: Int get() = sr / 100
    }

    /** 按「延迟/算力优先」排序：32k 模型解码器便宜 1.5 倍，最省算力。 */
    val VARIANTS = listOf(
        Variant(32, "net_g32k_L32.onnx", 16, 32000),
        Variant(32, FILE_NETG_L32, 16),
        Variant(50, FILE_NETG_L50, 25),
        Variant(100, FILE_NETG, 50),
    )

    fun resolveVariant(context: Context): Variant? {
        val d = dir(context)
        return VARIANTS.firstOrNull { File(d, it.file).let { f -> f.isFile && f.length() > 0 } }
    }

    private val EXTERNAL_SOURCES = listOf(
        "/data/adb/modules/voicechanger_priv/models",
        "/sdcard/Download/VoiceChanger/models",
    )

    fun dir(context: Context): File = File(context.filesDir, DIR)

    private val ALL_FILES = REQUIRED + VARIANTS.map { it.file } + listOf("rmvpe_L32.onnx", "rmvpe_L50.onnx")

    /** RMVPE 也有「固化长度」：与 net_g 变体的帧数保持一致的文件优先。 */
    fun rmvpeFile(context: Context, frames: Int): String {
        val cand = if (frames == 100) "rmvpe.onnx" else "rmvpe_L$frames.onnx"
        val d = dir(context)
        return if (File(d, cand).isFile) cand else "rmvpe.onnx"
    }

    fun missing(context: Context): List<String> {
        val d = dir(context)
        val miss = REQUIRED.filter { !File(d, it).let { f -> f.isFile && f.length() > 0 } }
        return if (resolveVariant(context) == null) miss + FILE_NETG else miss
    }

    fun isReady(context: Context): Boolean = missing(context).isEmpty()

    /** 从模块目录/下载目录同步模型到应用私有目录（首次运行）。
     *  支持两种布局：`models/<file>`（旧）或 `models/rvc/<file>`（推荐，与 MeanVC 共用模块 models 目录）。 */
    fun ensureInstalled(context: Context): List<String> {
        val dst = dir(context)
        if (!dst.exists() && !dst.mkdirs()) return missing(context)
        for (name in ALL_FILES) {
            val target = File(dst, name)
            if (target.isFile && target.length() > 0) continue
            val src = EXTERNAL_SOURCES.asSequence()
                .flatMap { base -> sequenceOf(File(base, name), File(File(base, "rvc"), name)) }
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

    // ---------------------------------------------------------------- mel 前端

    /** RMVPE mel 常量（基矩阵 + hann 窗），从 `rmvpe_mel.bin` 加载。 */
    class MelConst(val basis: Array<FloatArray>, val window: FloatArray)

    fun loadMelConst(context: Context): MelConst? {
        val f = File(dir(context), FILE_MEL)
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8); buf.get(magic)
            require(String(magic) == "RVMEL001") { "bad magic" }
            val rows = buf.int; val cols = buf.int
            val basis = Array(rows) { FloatArray(cols) }
            for (r in 0 until rows) for (c in 0 until cols) basis[r][c] = buf.float
            val winLen = buf.int
            val win = FloatArray(winLen) { buf.float }
            MelConst(basis, win)
        } catch (t: Throwable) {
            Log.w(TAG, "loadMelConst failed: ${t.message}")
            null
        }
    }

    /**
     * 16k wav → log-mel [128][T]，对齐 RVC `MelSpectrogram(is_half=False,128,16000,1024,160,None,30,8000)`：
     * center=True 反射填充、|STFT|、mel basis、log(clamp(·,1e-5))。
     */
    class MelFrontend(private val c: MelConst) {
        private val re = FloatArray(N_FFT)
        private val im = FloatArray(N_FFT)

        /** 每个 mel 滤波器的有效 bin 区间（mel 基大部分为 0，跳过可再省约 90% 乘加）。 */
        private val lo = IntArray(N_MELS)
        private val hi = IntArray(N_MELS)

        init {
            val bins = N_FFT / 2 + 1
            for (m in 0 until N_MELS) {
                val b = c.basis[m]
                var a = 0
                while (a < bins && b[a] == 0f) a++
                var z = bins - 1
                while (z >= a && b[z] == 0f) z--
                lo[m] = a
                hi[m] = (z + 1).coerceAtMost(bins)
            }
        }

        fun compute(wav: FloatArray): Array<FloatArray> {
            val n = wav.size
            val frames = 1 + n / MEL_HOP
            val pad = N_FFT / 2
            val padded = FloatArray(n + 2 * pad)
            for (i in 0 until pad) padded[i] = wav[pad - i]
            System.arraycopy(wav, 0, padded, pad, n)
            for (i in 0 until pad) padded[pad + n + i] = wav[n - 2 - i]

            val out = Array(N_MELS) { FloatArray(frames) }
            val mag = FloatArray(N_FFT / 2 + 1)
            for (t in 0 until frames) {
                val off = t * MEL_HOP
                java.util.Arrays.fill(re, 0f)
                java.util.Arrays.fill(im, 0f)
                for (i in 0 until N_FFT) re[i] = padded[off + i] * c.window[i]
                fft(re, im)
                // 幅度谱只算一次（此前每个 mel 频带重复计算 → 慢 100 倍）
                for (k in mag.indices) {
                    val r = re[k]
                    val i2 = im[k]
                    mag[k] = kotlin.math.sqrt(r * r + i2 * i2)
                }
                for (m in 0 until N_MELS) {
                    var acc = 0f
                    val b = c.basis[m]
                    for (k in lo[m] until hi[m]) acc += b[k] * mag[k]
                    out[m][t] = ln(if (acc < 1e-5f) 1e-5f else acc)
                }
            }
            return out
        }

        private fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j or bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang); val wi = kotlin.math.sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k]; val ai = im[i + k]
                        val br = re[i + k + len / 2]; val bi = im[i + k + len / 2]
                        val tr = br * cr - bi * ci
                        val ti = br * ci + bi * cr
                        re[i + k] = ar + tr.toFloat(); im[i + k] = ai + ti.toFloat()
                        re[i + k + len / 2] = ar - tr.toFloat(); im[i + k + len / 2] = ai - ti.toFloat()
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    // ---------------------------------------------------------------- f0 解码

    private val CENTS by lazy { FloatArray(368) { 20f * (it - 4) + 1997.3794084376191f } }

    /**
     * RMVPE hidden[360] → f0（Hz）。对齐 `RMVPE.to_local_average_cents` + `decode`：
     * 取每帧 argmax，在 ±4 bin 窗口内按显著度加权求 cents 均值；低于阈值置静音；
     * f0 = 10 · 2^(cents/1200)。
     */
    fun hiddenToF0(hidden: Array<FloatArray>, thred: Float = 0.03f): FloatArray {
        val t = hidden.size
        val out = FloatArray(t)
        for (i in 0 until t) {
            val row = hidden[i]
            var best = 0
            var bestV = -1e9f
            for (k in row.indices) if (row[k] > bestV) { bestV = row[k]; best = k }
            var acc = 0f
            var wsum = 0f
            for (d in -4..4) {
                val idx = best + d
                if (idx < 0 || idx >= row.size) continue
                val w = row[idx]
                acc += w * CENTS[idx + 4]
                wsum += w
            }
            val cents = if (wsum > 0f) acc / wsum else 0f
            out[i] = if (bestV <= thred) 0f else 10f * 2f.pow(cents / 1200f)
        }
        return out
    }

    /** f0(Hz) → RVC 粗粒度 pitch（1..255）。 */
    fun f0ToCoarse(f0: FloatArray): LongArray {
        val lo = 1127.0 * ln(1 + F0_MIN / 700.0)
        val hi = 1127.0 * ln(1 + F0_MAX / 700.0)
        val out = LongArray(f0.size)
        for (i in f0.indices) {
            var mel = 1127.0 * ln(1 + (f0[i].coerceAtLeast(0f)) / 700.0)
            mel = if (mel > 0) (mel - lo) * 254.0 / (hi - lo) + 1.0 else 1.0
            out[i] = mel.coerceIn(1.0, 255.0).roundToInt().toLong()
        }
        return out
    }

    /** f0 序列线性重采样到目标长度。 */
    fun resampleF0(f0: FloatArray, target: Int): FloatArray {
        if (f0.isEmpty()) return FloatArray(target)
        val out = FloatArray(target)
        for (i in 0 until target) {
            val src = if (target == 1) 0f else i.toFloat() * (f0.size - 1) / (target - 1)
            val i0 = src.toInt().coerceIn(0, f0.size - 1)
            val i1 = (i0 + 1).coerceAtMost(f0.size - 1)
            val w = src - i0
            out[i] = f0[i0] + (f0[i1] - f0[i0]) * w
        }
        return out
    }
}

// 说明：本文件仅使用 Kotlin 标准类型；上一行保留说明以免误用占位别名。