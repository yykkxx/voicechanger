package com.voicechanger.app.route

import android.content.Context

/**
 * 路由后端注册表（plan/02 第 4 节）。
 *
 * AUTO 选择顺序：
 * 1. PolicyRecorderMixBackend（priv-app + 权限就绪时）
 * 2. OplusVirtualAudioBackend（发现虚拟设备且通过注入验证后）
 * 3. MonitorOnlyBackend（降级：仅监听，不注入）
 *
 * 注意：Phase 1 主动注入验证通过前，policy 后端只在"权限就绪"时被选中，
 * 但 UI 必须明确显示其验证状态（DEGRADED/UNVERIFIED），不夸大结论。
 */
object RouteRegistry {

    val backends: List<RouteBackend> = listOf(
        PolicyRecorderMixBackend(),
        OplusVirtualAudioBackend(),
        MonitorOnlyBackend(),
    )

    /** 后端实例缓存（会话间保持策略状态查询）。 */
    fun byId(id: String?): RouteBackend? =
        backends.firstOrNull { it.id == id }

    /**
     * AUTO 选择（plan/09 第 7.1 节）：
     * - 显式 backendId 优先（注入后端需 Phase 1 验证/显式选择）；
     * - 否则**直接选 MonitorOnlyBackend（监听/返听模式）**：
     *   不注册任何 AudioPolicy、不触碰全局路由，保证变声开启时其它应用播放不受影响；
     *   （厂商虚拟设备本机未加载、policy recorder mix 本机不可用且破坏全局播放）
     * - 不再默认尝试 policy/oplus 注入后端。
     */
    fun selectAuto(context: Context, explicitId: String?, allowMonitorFallback: Boolean): RouteBackend {
        byId(explicitId)?.let { return it }
        // 监听/返听模式是当前唯一不破坏系统音频的默认选择
        return backends.first { it.id == MonitorOnlyBackend.ID }
    }

    /** 生成所有后端的能力报告文本（诊断页）。 */
    fun buildReports(context: Context): String = buildString {
        for (backend in backends) {
            val report = runCatching { backend.probe(context) }
                .getOrElse { t ->
                    CapabilityReport(
                        CapabilityReport.CapabilityStatus.UNSUPPORTED,
                        "probe 异常: ${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            append("[").append(backend.id).append("] ")
                .append(backend.displayName).append('\n')
            append("  状态: ").append(report.status)
                .append("  需特权: ").append(report.requiresPrivileged).append('\n')
            append("  详情: ").append(report.details).append('\n')
        }
    }
}