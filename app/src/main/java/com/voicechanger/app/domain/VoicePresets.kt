package com.voicechanger.app.domain

/**
 * 内置音色预设：选择后映射为一组 [EffectParams]（保留基础增益设置）。
 * 预设实现基于 InternalDspProcessor 的 DSP 链：
 * 音高（相位声码器）/ EQ 倾斜 / 环形调制 / 失真 / 回声 / 合唱 / 镶边 / 颤音 / 电话音 / 低音 / 临场感。
 */
enum class VoicePreset(val label: String) {
    ORIGINAL("原声"),
    GIRL("女声"),
    BOY("男声"),
    CHILD("童声"),
    MONSTER("怪兽"),
    ROBOT("机器人"),
    ETHEREAL("空灵"),
    SINGER("歌者"),
    TELEPHONE("电话音"),
    DEEP("浑厚低音"),
    ANGEL("天使"),
    RADIO("电台主播"),
    CUSTOM("自定义");

    /** 在 [base] 上应用本预设（保留增益/门限等用户设置）。 */
    fun apply(base: EffectParams): EffectParams = when (this) {
        ORIGINAL -> base.copy(
            pitchSemitones = 0f, eqTiltDb = 0f, robotAmount = 0f, drive = 0f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 0f, presenceDb = 0f,
        )
        GIRL -> base.copy(
            pitchSemitones = 7f, eqTiltDb = 3f, formantRatio = 1.35f,
            robotAmount = 0f, drive = 0f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 0f, presenceDb = 2f,
        )
        BOY -> base.copy(
            pitchSemitones = -4f, eqTiltDb = -2f, formantRatio = 0.85f,
            robotAmount = 0f, drive = 0f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 3f, presenceDb = 0f,
        )
        CHILD -> base.copy(
            pitchSemitones = 10f, eqTiltDb = 4f, formantRatio = 1.4f,
            robotAmount = 0f, drive = 0f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 0f, presenceDb = 3f,
        )
        MONSTER -> base.copy(
            pitchSemitones = -8f, eqTiltDb = -3f, formantRatio = 0.8f,
            robotAmount = 0.15f, drive = 0.5f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 4f, presenceDb = 0f,
        )
        ROBOT -> base.copy(
            pitchSemitones = 0f, eqTiltDb = 1f, robotAmount = 0.85f, drive = 0.1f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0.15f, telephoneAmount = 0f,
            bassBoostDb = 0f, presenceDb = 0f,
        )
        ETHEREAL -> base.copy(
            pitchSemitones = 3f, eqTiltDb = 3f, robotAmount = 0f, drive = 0f,
            echoAmount = 0.45f, echoDelayMs = 180, echoFeedback = 0.35f,
            chorusAmount = 0.25f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 0f, presenceDb = 2f,
        )
        SINGER -> base.copy(
            pitchSemitones = 4f, eqTiltDb = 2f, formantRatio = 1.15f,
            robotAmount = 0f, drive = 0f, echoAmount = 0.25f, echoDelayMs = 110, echoFeedback = 0.2f,
            chorusAmount = 0.5f, flangerAmount = 0f, tremoloAmount = 0.25f, telephoneAmount = 0f,
            bassBoostDb = 1f, presenceDb = 3f,
        )
        TELEPHONE -> base.copy(
            pitchSemitones = 1f, eqTiltDb = 0f, formantRatio = 1f,
            robotAmount = 0f, drive = 0f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0.9f,
            bassBoostDb = -4f, presenceDb = 0f,
        )
        DEEP -> base.copy(
            pitchSemitones = -6f, eqTiltDb = -4f, formantRatio = 0.75f,
            robotAmount = 0f, drive = 0f, echoAmount = 0.2f, echoDelayMs = 160, echoFeedback = 0.25f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 8f, presenceDb = 0f,
        )
        ANGEL -> base.copy(
            pitchSemitones = 8f, eqTiltDb = 4f, formantRatio = 1.45f,
            robotAmount = 0f, drive = 0f, echoAmount = 0.35f, echoDelayMs = 200, echoFeedback = 0.3f,
            chorusAmount = 0.6f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0f,
            bassBoostDb = 0f, presenceDb = 4f,
        )
        RADIO -> base.copy(
            pitchSemitones = 2f, eqTiltDb = 2f, formantRatio = 1.05f,
            robotAmount = 0f, drive = 0f, echoAmount = 0f,
            chorusAmount = 0f, flangerAmount = 0f, tremoloAmount = 0f, telephoneAmount = 0.35f,
            bassBoostDb = -2f, presenceDb = 5f,
        )
        CUSTOM -> base
    }

    /** 根据参数反推匹配的预设（用于 UI 回显）。 */
    companion object {
        fun detect(params: EffectParams): VoicePreset {
            for (p in entries) {
                if (p == CUSTOM) continue
                val ref = p.apply(EffectParams())
                if (
                    kotlin.math.abs(ref.pitchSemitones - params.pitchSemitones) < 0.05f &&
                    kotlin.math.abs(ref.eqTiltDb - params.eqTiltDb) < 0.05f &&
                    kotlin.math.abs(ref.formantRatio - params.formantRatio) < 0.02f &&
                    kotlin.math.abs(ref.robotAmount - params.robotAmount) < 0.02f &&
                    kotlin.math.abs(ref.drive - params.drive) < 0.02f &&
                    kotlin.math.abs(ref.echoAmount - params.echoAmount) < 0.02f &&
                    kotlin.math.abs(ref.chorusAmount - params.chorusAmount) < 0.02f &&
                    kotlin.math.abs(ref.flangerAmount - params.flangerAmount) < 0.02f &&
                    kotlin.math.abs(ref.tremoloAmount - params.tremoloAmount) < 0.02f &&
                    kotlin.math.abs(ref.telephoneAmount - params.telephoneAmount) < 0.02f &&
                    kotlin.math.abs(ref.bassBoostDb - params.bassBoostDb) < 0.05f &&
                    kotlin.math.abs(ref.presenceDb - params.presenceDb) < 0.05f
                ) {
                    return p
                }
            }
            return CUSTOM
        }
    }
}