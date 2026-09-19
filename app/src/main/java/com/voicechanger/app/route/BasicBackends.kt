package com.voicechanger.app.route

import android.content.Context
import android.media.AudioManager
import com.voicechanger.app.audio.AudioDeviceLookup
import com.voicechanger.app.audio.InjectionEndpoint

/**
 * 监听/调试后端（plan/02 第 3.4 节）：
 * 不尝试影响第三方应用，仅提供“麦克风 → 处理 → 输出设备”演示通路。
 *
 * 使用场景：
 * - 普通安装包开发；
 * - 路由注入失败时的降级演示；
 * - Phase 2/3 的采集、DSP、延迟验证。
 *
 * 安全：默认选择非扬声器的输出设备；找不到时使用默认设备并提示。
 */
class MonitorOnlyBackend : RouteBackend {

    override val id: String = ID
    override val displayName: String = "监听模式（不注入）"

    companion object {
        const val ID = "monitor"
    }

    override fun probe(context: Context): CapabilityReport {
        return CapabilityReport(
            status = CapabilityReport.CapabilityStatus.SUPPORTED,
            details = "始终可用；仅用于本地监听与调试，不向目标应用注入。",
        )
    }

    override fun open(context: Context, request: RouteRequest): RouteSessionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // 选择安全的监听输出（排除 SCO；有线/USB/A2DP 媒体通道优先）
        // null = 不 setPreferredDevice，跟随系统默认媒体路由（避免误绑 SCO 抢占）
        val safeDevice = am?.let { AudioDeviceLookup.findSafeMonitorDevice(it) }
        return RouteSessionResult(
            backendId = id,
            injector = InjectionEndpoint(
                preferredDevice = safeDevice,
                // 监听/返听统一 stereo 展开（耳机/扬声器均兼容 mono→stereo）
                stereoExpansion = true,
                usage = android.media.AudioAttributes.USAGE_MEDIA,
                contentType = android.media.AudioAttributes.CONTENT_TYPE_SPEECH,
            ),
            stereoInjection = true,
            notes = if (safeDevice == null) {
                "未检测到有线/USB/A2DP 媒体输出；监听跟随系统默认路由（注意扬声器回授风险）。"
            } else {
                "已锁定安全输出设备：${safeDevice.productName}（监听=返听通道，排除 SCO）"
            },
        )
    }

    override fun close(context: Context) {
        // 无系统级资源需要清理
    }
}

/**
 * 厂商虚拟音频设备后端（ColorOS 候选，plan/02 第 3.2 节）。
 *
 * 当前阶段：
 * - probe：枚举 `127.0.0.x` / `127.0.1.1` 设备并给出结论；
 * - open：选择虚拟输出设备作为注入端。
 *
 * 尚未实现（Phase 1 验证后决定）：
 * - 与 framework 策略结合把目标 UID 录音路由到虚拟输入；
 * - 虚拟 out→in 管道的完整闭环。
 */
class OplusVirtualAudioBackend : RouteBackend {

    override val id: String = ID
    override val displayName: String = "厂商虚拟音频设备"

    companion object {
        const val ID = "oplus_virtual"
    }

    override fun probe(context: Context): CapabilityReport {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CapabilityReport(
                CapabilityReport.CapabilityStatus.UNSUPPORTED, "AudioManager 不可用",
            )
        val out = AudioDeviceLookup.findVirtualOut(am)
        val inp = AudioDeviceLookup.findVirtualIn(am)
        val outDesc = out?.let { "out=${it.id}@${it.address}" } ?: "out=-"
        val inDesc = inp?.let { "in=${it.id}@${it.address}" } ?: "in=-"
        return when {
            out != null && inp != null -> CapabilityReport(
                status = CapabilityReport.CapabilityStatus.SUPPORTED,
                details = "发现厂商虚拟管道：$outDesc $inDesc（type=IP）。" +
                    "通过 AudioTrack.preferredDevice=虚拟输出 直写 + AudioRecord.preferredDevice=虚拟输入 回读验证（Phase 1）。",
                requiresPrivileged = true,
            )
            out != null || inp != null -> CapabilityReport(
                status = CapabilityReport.CapabilityStatus.DEGRADED,
                details = "仅发现部分虚拟设备（$outDesc $inDesc）。",
                requiresPrivileged = true,
            )
            else -> CapabilityReport(
                status = CapabilityReport.CapabilityStatus.UNSUPPORTED,
                details = "未发现 AUDIO_DEVICE_OUT_IP / IN_IP 虚拟设备（该 ROM 可能不提供）。",
                requiresPrivileged = true,
            )
        }
    }

    override fun open(context: Context, request: RouteRequest): RouteSessionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: throw RouteException("AudioManager 不可用")
        val out = request.manualInjectAddress?.let {
            AudioDeviceLookup.findByAddress(am, it, isInput = false)
        } ?: AudioDeviceLookup.findVirtualOut(am)
            ?: throw RouteException("未找到虚拟输出设备（127.0.0.x）")

        // 注：注入轨由 InjectionEndpoint 显式绑定到 ip_out（preferredDevice），
        // 且 usage 使用 USAGE_MEDIA + CONTENT_TYPE_SPEECH，不会抢占通话/媒体主输出
        // （plan/09 第 4.2 节，避免再次出现全局静音）。
        return RouteSessionResult(
            backendId = id,
            injector = com.voicechanger.app.audio.InjectionEndpoint(
                preferredDevice = out,
                // 厂商虚拟设备 profile = AUDIO_CHANNEL_OUT_STEREO（plan/02 第 3.2 节），
                // 内部 mono → stereo 展开以匹配虚拟设备格式。
                stereoExpansion = true,
                // 虚拟设备直写：请求以指定设备为输出，而不是参与主混音
                usage = android.media.AudioAttributes.USAGE_MEDIA,
                contentType = android.media.AudioAttributes.CONTENT_TYPE_SPEECH,
            ),
            stereoInjection = true,
            notes = "注入设备：${out.productName} address=${out.address}。" +
                "未修改任何目标应用录音路由；如需替换第三方麦克风需完成 Phase 1 标记音验证。",
        )
    }

    override fun close(context: Context) {
        // 无系统级注册需要清理
    }
}