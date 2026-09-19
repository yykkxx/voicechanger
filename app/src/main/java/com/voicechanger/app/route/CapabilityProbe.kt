package com.voicechanger.app.route

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 路由能力探测（plan/02 第 2 节、plan/04 第 4 节）。
 *
 * 仅读取事实：权限、设备、API 可用性。不尝试修改系统状态，不做任何注入。
 * 输出为可复制的文本报告，供 UI 诊断页展示。
 */
object CapabilityProbe {
    private const val TAG = "VC/Probe"

    const val PERM_MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"
    const val PERM_CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"

    /** 探测结果文本（支持一键复制）。 */
    fun buildReport(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== VoiceChanger 能力报告 ===\n")
        sb.append("时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
        sb.append("包名: ").append(context.packageName).append('\n')

        val pm = context.packageManager
        sb.append("安装路径: ")
        try {
            sb.append(pm.getApplicationInfo(context.packageName, 0).sourceDir).append('\n')
        } catch (t: Throwable) {
            sb.append("<读取失败: ").append(t.message).append(">\n")
        }

        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("设备: ").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n')
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')

        appendPermission(sb, context, "RECORD_AUDIO")
        appendPermission(sb, context, "MODIFY_AUDIO_ROUTING")
        appendPermission(sb, context, "CAPTURE_AUDIO_OUTPUT")
        appendPermission(sb, context, "MODIFY_AUDIO_SETTINGS")

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            sb.append("\n[AudioManager 不可用]\n")
            return sb.toString()
        }
        appendDevices(sb, am)
        appendVirtualDevices(sb, am)
        appendPolicyApi(sb)
        return sb.toString()
    }

    /**
     * 与 [buildReport] 相同的报告，但绝不抛出异常（诊断页使用）。
     * 任何内部错误都以文本形式返回。
     */
    fun buildReportScoped(context: Context): String = try {
        buildReport(context)
    } catch (t: Throwable) {
        Log.w(TAG, "buildReport failed", t)
        "能力报告生成失败: ${t.javaClass.simpleName}: ${t.message}\n"
    }

    private fun appendPermission(sb: StringBuilder, context: Context, permission: String) {
        val full = if (permission.startsWith("android.permission.")) permission
        else "android.permission.$permission"
        val granted = try {
            context.checkSelfPermission(full) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        sb.append("权限 ").append(permission).append(": ").append(if (granted) "granted=true" else "granted=false").append('\n')
    }

    private fun appendDevices(sb: StringBuilder, am: AudioManager) {
        sb.append("\n--- 输入设备 ---\n")
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { dev ->
            sb.append(deviceLine(dev)).append('\n')
        }
        sb.append("--- 输出设备 ---\n")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { dev ->
            sb.append(deviceLine(dev)).append('\n')
        }
    }

    private fun deviceLine(dev: AudioDeviceInfo): String = buildString {
        append("id=").append(dev.id)
        append(" type=").append(dev.type)
        append(" name=").append(dev.productName)
        append(" addr=").append(dev.address ?: "-")
        append(" ch=").append(dev.channelCounts.joinToString("/"))
        append(" sr=").append(dev.sampleRates.joinToString("/"))
    }

    private fun appendVirtualDevices(sb: StringBuilder, am: AudioManager) {
        sb.append("\n--- 虚拟设备识别（ColorOS 候选）---\n")
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.address?.startsWith("127.0.0") == true }
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.address?.startsWith("127.0.1") == true }
        sb.append("virtual out: ").append(outs.joinToString { "${it.id}@" + (it.address ?: "-") }.ifEmpty { "<none>" }).append('\n')
        sb.append("virtual in : ").append(ins.joinToString { "${it.id}@" + (it.address ?: "-") }.ifEmpty { "<none>" }).append('\n')
    }

    /**
     * 检查 AudioPolicy / AudioMix API 在当前 SDK 的可见性（仅反射检查，不调用）。
     * 完整注册探测见 [AudioPolicyProbe]。
     */
    private fun appendPolicyApi(sb: StringBuilder) {
        sb.append("\n--- AudioPolicy API ---\n")
        checkClass(sb, "android.media.audiopolicy.AudioPolicy")
        checkClass(sb, "android.media.audiopolicy.AudioMix")
        checkClass(sb, "android.media.audiopolicy.AudioMixingRule")
        checkClass(sb, "android.media.audiopolicy.AudioPolicy\$Builder")
        try {
            val cls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val methods = cls.declaredMethods.map { it.name }.distinct().sorted()
            sb.append("AudioPolicy methods: ").append(methods.joinToString(",")).append('\n')
            cls.getDeclaredConstructor()
        } catch (t: Throwable) {
            sb.append("AudioPolicy methods 读取失败: ").append(t.message).append('\n')
        }
        try {
            val cls = Class.forName("android.media.AudioManager")
            val methods = cls.declaredMethods.map { it.name }
                .filter { it.contains("AudioPolicy", ignoreCase = true) || it.contains("register", ignoreCase = true) }
                .distinct().sorted()
            sb.append("AudioManager policy methods: ").append(methods.joinToString(",")).append('\n')
        } catch (t: Throwable) {
            sb.append("AudioManager methods 读取失败: ").append(t.message).append('\n')
        }
    }

    private fun checkClass(sb: StringBuilder, name: String) {
        try {
            Class.forName(name)
            sb.append("class ").append(name).append(": OK\n")
        } catch (t: Throwable) {
            Log.w(TAG, "class $name not found", t)
            sb.append("class ").append(name).append(": NOT FOUND (").append(t.message).append(")\n")
        }
    }
}