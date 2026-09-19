package com.voicechanger.app.route

import android.content.Context

/**
 * 路由后端抽象（plan/02 第 2 节）。
 *
 * 后端只负责“采集与注入的物理通路选择”和策略注册/注销，
 * 不参与任何变声算法。
 */
interface RouteBackend {

    /** 稳定标识，用于 UI/日志/配置。 */
    val id: String

    /** 展示名。 */
    val displayName: String

    /** 能力探测：不产生副作用的评估报告。 */
    fun probe(context: Context): CapabilityReport

    /**
     * 打开会话：注册策略、创建注入端（可能耗时）。
     * 返回结果包含注入端与说明；失败时给出稳定原因。
     */
    @Throws(RouteException::class)
    fun open(context: Context, request: RouteRequest): RouteSessionResult

    /** 会话清理：注销策略、释放设备。必须幂等。 */
    fun close(context: Context)
}

data class CapabilityReport(
    val status: CapabilityStatus,
    val details: String,
    /** 是否需要 privileged 部署。 */
    val requiresPrivileged: Boolean = false,
) {
    enum class CapabilityStatus { SUPPORTED, DEGRADED, UNSUPPORTED }
}

/** 路由请求（plan/02 第 2.1 节）。 */
data class RouteRequest(
    val targetPackage: String? = null,
    val preferStereoInjection: Boolean = false,
    /** 手动指定注入设备（高级设置）。null = 自动。 */
    val manualInjectAddress: String? = null,
)

/** 打开的回话结果。 */
data class RouteSessionResult(
    val backendId: String,
    /**
     * 已就绪的注入器（调用方随后执行 open()/start()）。
     * 设备后端 = InjectionEndpoint；策略后端 = PolicyTrackInjector（track 由 AudioPolicy 创建）。
     */
    val injector: com.voicechanger.app.audio.Injector,
    val stereoInjection: Boolean,
    val notes: String,
)

class RouteException(
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause)