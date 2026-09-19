package com.voicechanger.app.domain

/**
 * UI 与前台服务之间的参数单例桥。
 *
 * 背景：变声参数（11+ 字段）不适合经 Intent extras 传递；
 * UI 在用户每次调整时写入本对象；服务在启动会话时读取。
 * 会话运行中 UI 仍通过 [com.voicechanger.app.service.VoiceChangerService.updateEffects]
 * 实时更新。
 */
object CurrentParams {
    @Volatile
    var effects: EffectParams = EffectParams()

    @Volatile
    var preset: VoicePreset = VoicePreset.ORIGINAL

    /** AI 声线转换最近一次初始化状态，供 UI 显示。 */
    @Volatile
    var aiStatus: String = "未初始化"

    /** AI 声线转换实际使用的推理后端（NPU/GPU/CPU 或 DSP）。 */
    @Volatile
    var aiBackend: String = "-"
}