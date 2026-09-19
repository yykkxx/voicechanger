package com.voicechanger.app.route

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.util.Log

/**
 * AudioPolicy 注册探测（plan/02 第 3.1 节、plan/10 第 10 节、plan/07）。
 *
 * 背景：compileSdk 35 的公开 android.jar 不包含 android.media.audiopolicy.*，
 * 因此本文件**全部通过反射**访问 system/hidden API，避免编译期依赖：
 * - android.media.audiopolicy.AudioPolicy / AudioPolicy$Builder
 * - android.media.audiopolicy.AudioMix / AudioMix$Builder
 * - android.media.audiopolicy.AudioMixingRule / AudioMixingRule$Builder
 * - AudioManager.registerAudioPolicy / unregisterAudioPolicyAsync（hidden）
 *
 * 目标：以最小副作用验证：
 * 1. 相关类与方法在当前 ROM 是否存在；
 * 2. 注册一个策略是否成功（权限、hidden 策略、ROM 行为）；
 * 3. 注销路径是否干净。
 *
 * 该探针**不尝试注入、不读取音频**，只验证基础设施。
 * 真机执行结果应记录到 plan/06-实施进度.md 的 Phase 1 表格。
 */
class AudioPolicyProbe(context: Context) {

    companion object {
        private const val TAG = "VC/PolicyProbe"
        private const val CLS_POLICY = "android.media.audiopolicy.AudioPolicy"
        private const val CLS_POLICY_BUILDER = "android.media.audiopolicy.AudioPolicy\$Builder"
        private const val CLS_MIX = "android.media.audiopolicy.AudioMix"
        private const val CLS_MIX_BUILDER = "android.media.audiopolicy.AudioMix\$Builder"
        private const val CLS_RULE = "android.media.audiopolicy.AudioMixingRule"
        private const val CLS_RULE_BUILDER = "android.media.audiopolicy.AudioMixingRule\$Builder"

        /** AudioManager.SUCCESS 在公开 SDK 不可见；AOSP 源码中恒为 0。 */
        private const val AUDIO_REGISTER_SUCCESS = 0
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    data class Result(
        val apiAvailable: Boolean,
        val registerResult: Int,
        val registerOk: Boolean,
        val unregisterOk: Boolean,
        val error: String? = null,
    )

    /**
     * 注册一个空策略并立即注销。
     * 注：空策略在部分 ROM 上可能被拒绝；返回码用于记录，不视为失败。
     */
    fun probeEmptyPolicy(): Result {
        var policy: Any? = null
        return try {
            val builderCls = Class.forName(CLS_POLICY_BUILDER)
            val builder = builderCls.getConstructor(Context::class.java).newInstance(appContext)
            policy = builderCls.getMethod("build").invoke(builder)
            val result = registerPolicy(policy!!)
            val unregistered = if (result == AUDIO_REGISTER_SUCCESS) {
                unregisterPolicy(policy!!)
                true
            } else false
            Result(
                apiAvailable = true,
                registerResult = result,
                registerOk = result == AUDIO_REGISTER_SUCCESS,
                unregisterOk = unregistered,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "probeEmptyPolicy failed", t)
            Result(
                apiAvailable = false,
                registerResult = Int.MIN_VALUE,
                registerOk = false,
                unregisterOk = false,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            try {
                policy?.let { unregisterPolicy(it) }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 注册一个 LOOP_BACK 播放捕获策略（仅能力验证，立即注销）。
     * 注意：这是“捕获播放”路径，不代表麦克风注入可用（plan/02 第 1 节）。
     */
    fun probeLoopbackPlayerCapture(): Result {
        var policy: Any? = null
        return try {
            val ruleCls = Class.forName(CLS_RULE)
            val ruleBuilderCls = Class.forName(CLS_RULE_BUILDER)
            val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()

            // RULE_MATCH_ATTRIBUTE_USAGE（AOSP 常量 = 1）；反射读取更稳
            val ruleMatchAttrUsage = ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .build()
            ruleBuilderCls.getMethod(
                "addRule",
                AudioAttributes::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(ruleBuilder, attributes, ruleMatchAttrUsage)
            val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)

            val mixBuilderCls = Class.forName(CLS_MIX_BUILDER)
            val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(rule)

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)

            val mixCls = Class.forName(CLS_MIX)
            val routeFlagLoopBack = mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, routeFlagLoopBack)

            val mix = mixBuilderCls.getMethod("build").invoke(mixBuilder)

            val builderCls = Class.forName(CLS_POLICY_BUILDER)
            val builder = builderCls.getConstructor(Context::class.java).newInstance(appContext)
            builderCls.getMethod("addMix", mixCls).invoke(builder, mix)
            policy = builderCls.getMethod("build").invoke(builder)

            val result = registerPolicy(policy!!)
            val unregistered = if (result == AUDIO_REGISTER_SUCCESS) {
                unregisterPolicy(policy!!)
                true
            } else false
            Result(
                apiAvailable = true,
                registerResult = result,
                registerOk = result == AUDIO_REGISTER_SUCCESS,
                unregisterOk = unregistered,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "probeLoopbackPlayerCapture failed", t)
            Result(
                apiAvailable = false,
                registerResult = Int.MIN_VALUE,
                registerOk = false,
                unregisterOk = false,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            try {
                policy?.let { unregisterPolicy(it) }
            } catch (_: Throwable) {
            }
        }
    }

    /** 反射调用 AudioManager.registerAudioPolicy(AudioPolicy)（hidden API）。 */
    private fun registerPolicy(policy: Any): Int {
        val policyCls = Class.forName(CLS_POLICY)
        val method = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls)
        return method.invoke(audioManager, policy) as Int
    }

    /** 反射调用 AudioManager.unregisterAudioPolicyAsync(AudioPolicy)（hidden API）。 */
    private fun unregisterPolicy(policy: Any) {
        val policyCls = Class.forName(CLS_POLICY)
        val method = AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policyCls)
        method.invoke(audioManager, policy)
    }
}