package com.voicechanger.app.domain

/** 处理模式：与 plan/01、plan/03 对应。 */
enum class ProcessorMode {
    /** 低延迟直通，用于路由与延迟基线。 */
    PASSTHROUGH,

    /** 设备内部 DSP（当前为 gain + limiter 的基础实现）。 */
    INTERNAL,

    /** 本地端口处理（协议已就绪，socket 传输待实现）。 */
    LOOPBACK_SOCKET,

    /** AI 声线转换（RVC 开源模型，NPU→GPU→CPU 自动选路）。 */
    AI_MEANVC,

    /** AI 声线转换（OpenVoice V2，轻量 zero-shot 音色克隆）。 */
    AI_OPENVOICE,

    /** AI 声线转换（FreeVC，基于 VITS 的轻量语音转换）。 */
    AI_FREEVC,

    /** AI 声线转换（DDSP-SVC，超轻量可微分 DSP，极低延迟）。 */
    AI_DDSP,

    /** 故障安全：输出静音。 */
    MUTE,
}

/** 稳定错误码：UI 与日志均使用该枚举，避免依赖异常字符串。 */
enum class ErrorCode {
    PERMISSION_RECORD_AUDIO_MISSING,
    PERMISSION_ROUTING_MISSING,
    ROUTE_UNSUPPORTED,
    ROUTE_REGISTRATION_FAILED,
    CAPTURE_INIT_FAILED,
    CAPTURE_READ_FAILED,
    INJECT_INIT_FAILED,
    INJECT_STATE_INVALID,
    DSP_INIT_FAILED,
    SOCKET_DISCONNECTED,
    SOCKET_NOT_IMPLEMENTED,
    SYSTEM_INTERRUPTION,
    INTERNAL_ERROR,
}

sealed interface PipelineState {
    data object Idle : PipelineState
    data object Checking : PipelineState
    data class PreparingRoute(val backendId: String?) : PipelineState
    data object Starting : PipelineState
    data class Running(val mode: ProcessorMode, val backendId: String) : PipelineState
    data class Recovering(val reason: String) : PipelineState
    data object Stopping : PipelineState
    data class Error(val code: ErrorCode, val message: String) : PipelineState
}

/** 变声参数：在帧边界以不可变快照生效（见 plan/03 第 2.3 节）。 */
data class EffectParams(
    val pitchSemitones: Float = 0f,
    /** 保留字段（当前由 EQ 倾斜近似实现听感差异，见 eqTiltDb）。 */
    val formantRatio: Float = 1f,
    val mix: Float = 1f,
    val inputGainDb: Float = 0f,
    val outputGainDb: Float = 0f,
    val noiseGateThresholdDb: Float = -60f,
    val limiterEnabled: Boolean = true,
    /** 明亮度倾斜（dB）：>0 明亮（女声/童声），<0 低沉（男声/怪兽）。 */
    val eqTiltDb: Float = 0f,
    /** 机器人效果强度 0..1（环形调制，固定 ~90Hz）。 */
    val robotAmount: Float = 0f,
    /** 失真强度 0..1（软削波驱动，怪兽/嘶吼）。 */
    val drive: Float = 0f,
    /** 回声湿声比例 0..1。 */
    val echoAmount: Float = 0f,
    val echoDelayMs: Int = 140,
    val echoFeedback: Float = 0.3f,
    /** 合唱强度 0..1（多延迟线 + 慢速 LFO，人声加厚/天使感）。 */
    val chorusAmount: Float = 0f,
    /** 镶边强度 0..1（短延迟扫频，太空/电子感）。 */
    val flangerAmount: Float = 0f,
    /** 颤音强度 0..1（幅度调制，唱歌/机械感）。 */
    val tremoloAmount: Float = 0f,
    /** 电话音强度 0..1（300Hz–3.4kHz 带通 + 轻微饱和，通话感）。 */
    val telephoneAmount: Float = 0f,
    /** 低音增强（dB，>0 更浑厚低沉）。 */
    val bassBoostDb: Float = 0f,
    /** 临场感提升（dB，2–4kHz 峰提升，人声更靠前清晰）。 */
    val presenceDb: Float = 0f,
)

/** 会话配置：由 UI/通知命令构造，交给服务执行。 */
data class SessionConfig(
    val mode: ProcessorMode = ProcessorMode.PASSTHROUGH,
    /** null = AUTO（按注册表顺序选择）。 */
    val backendId: String? = null,
    val targetPackage: String? = null,
    val preferredInputDeviceId: Int? = null,
    val effects: EffectParams = EffectParams(),
    /** AUTO 模式下，无特权后端时是否允许退化为监听模式。 */
    val allowMonitorFallback: Boolean = true,
)