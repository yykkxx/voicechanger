package com.voicechanger.app.route

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.AudioTrack
import android.util.Log
import com.voicechanger.app.audio.PolicyTrackInjector

/**
 * 策略级回注后端（priv-app，plan/语音路由.md 第 8 章 + plan/02 第 3.1 节）。
 *
 * 链路：
 * ```
 * 注册 recorder 类型 AudioMix（匹配目标 UID / capture preset）
 *   → AudioPolicy.registerAudioPolicy
 *   → policy.createAudioTrackSource(mix) 得到 AudioTrack
 *   → 本应用写入处理后 PCM
 *   → 匹配的录音会话（目标应用 AudioRecord）读到该 PCM
 * ```
 *
 * 说明：
 * - compileSdk 35 公开 android.jar 无 audiopolicy 类 → **全部反射**；
 * - AudioMix 混音类型 / 路由标志 / 规则常量全部在运行时读取，尽量降低 ROM 差异；
 * - 本类仅在 `MODIFY_AUDIO_ROUTING` 已授予（priv-app 部署）时使用；
 * - 精确注入行为必须经 plan/02 第 10 节"标记音验证"。
 *
 * 真机验证结果应记录到 plan/06 与 plan/07 的验证清单。
 */
class PolicyRecorderMixBackend : RouteBackend {

    companion object {
        private const val TAG = "VC/PolicyMix"
        const val ID = "policy_recorder_mix"

        // 反射类名
        private const val CLS_POLICY = "android.media.audiopolicy.AudioPolicy"
        private const val CLS_POLICY_BUILDER = "android.media.audiopolicy.AudioPolicy\$Builder"
        private const val CLS_MIX = "android.media.audiopolicy.AudioMix"
        private const val CLS_MIX_BUILDER = "android.media.audiopolicy.AudioMix\$Builder"
        private const val CLS_RULE = "android.media.audiopolicy.AudioMixingRule"
        private const val CLS_RULE_BUILDER = "android.media.audiopolicy.AudioMixingRule\$Builder"
    }

    override val id: String = ID
    override val displayName: String = "策略回注（recorder mix）"

    // 会话资源（由 register 流程填充）
    private var policyObject: Any? = null
    private var mixObject: Any? = null
    private var registered: Boolean = false

    /** 记录最近一次失败原因（诊断用）。 */
    @Volatile
    var lastError: String? = null
        private set

    override fun probe(context: Context): CapabilityReport {
        val details = StringBuilder()
        val privileged = runCatching {
            val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and 0x00000080 /* FLAG_PRIVILEGED */) != 0
        }.getOrDefault(false)

        val routingGranted = context.checkSelfPermission(CapabilityProbe.PERM_MODIFY_AUDIO_ROUTING) ==
            PackageManager.PERMISSION_GRANTED

        details.append("privileged 部署: ").append(privileged).append("; ")
        details.append("MODIFY_AUDIO_ROUTING: ").append(routingGranted).append("; ")

        val classesPresent = listOf(CLS_POLICY, CLS_MIX, CLS_RULE).all {
            runCatching { Class.forName(it) }.isSuccess
        }
        details.append("audiopolicy 类: ").append(classesPresent).append("; ")

        val methodsPresent = runCatching {
            Class.forName(CLS_POLICY) // sanity
            AudioManager::class.java.getMethod("registerAudioPolicy", Class.forName(CLS_POLICY))
            AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", Class.forName(CLS_POLICY))
        }.isSuccess
        details.append("register 方法: ").append(methodsPresent)

        val status = when {
            !classesPresent -> CapabilityReport.CapabilityStatus.UNSUPPORTED
            !routingGranted -> CapabilityReport.CapabilityStatus.DEGRADED
            else -> CapabilityReport.CapabilityStatus.SUPPORTED
        }
        return CapabilityReport(status, details.toString(), requiresPrivileged = true)
    }

    /**
     * 注册策略并创建注入 AudioTrack。
     *
     * @param request.targetPackage 目标应用包名（解析为 UID 精确匹配）；为空则匹配 capture preset。
     */
    @Throws(RouteException::class)
    override fun open(context: Context, request: RouteRequest): RouteSessionResult {
        close(context)
        lastError = null
        try {
            val policyCls = Class.forName(CLS_POLICY)
            val mixCls = Class.forName(CLS_MIX)
            val ruleCls = Class.forName(CLS_RULE)
            val ruleBuilderCls = Class.forName(CLS_RULE_BUILDER)
            val mixBuilderCls = Class.forName(CLS_MIX_BUILDER)
            val policyBuilderCls = Class.forName(CLS_POLICY_BUILDER)

            // 1) 混音规则：优先按目标 UID；否则按 VOICE_COMMUNICATION capture preset
            val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
            val ruleMatchUid = ruleCls.getField("RULE_MATCH_UID").getInt(null)
            var matched = "capture_preset(VOICE_COMMUNICATION)"

            val targetUid = request.targetPackage?.let { pkg ->
                runCatching {
                    context.packageManager.getApplicationInfo(pkg, 0).uid
                }.getOrNull()
            }
            if (targetUid != null) {
                ruleBuilderCls.getMethod("addRule", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(ruleBuilder, targetUid, ruleMatchUid)
                matched = "uid($targetUid)"
            } else {
                // 无目标包：匹配常见录音源（OR 语义），让系统录音机/通话类都能收到 RENDER 注入。
                // 逐条容错：某 preset 不被 ROM 支持时跳过，不拖垮整条注册。
                val ruleMatchCapturePreset = ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
                val addRule = ruleBuilderCls.getMethod(
                    "addRule",
                    AudioAttributes::class.java,
                    Int::class.javaPrimitiveType,
                )
                val presets = listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                )
                val applied = mutableListOf<String>()
                for (preset in presets) {
                    runCatching {
                        addRule.invoke(ruleBuilder, buildCaptureAttributes(preset), ruleMatchCapturePreset)
                        applied.add(presetName(preset))
                    }.onFailure { t ->
                        Log.w(TAG, "addRule preset=$preset failed: ${t.message}")
                    }
                }
                if (applied.isEmpty()) {
                    throw RouteException("AudioMixingRule: 无法添加任何 capture preset 规则")
                }
                matched = "capture_preset(${applied.joinToString(",")})"
            }
            val rule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)

            // 2) AudioMix：recorder 类型 + 48k stereo（与目标录音会话协商格式）
            val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(rule)
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)

            // setMixType(MIX_TYPE_RECORDERS=1)：把本 mix 作为录音注入端
            val mixTypeRecorders = runCatching {
                mixCls.getField("MIX_TYPE_RECORDERS").getInt(null)
            }.getOrDefault(1)
            runCatching {
                mixBuilderCls.getMethod("setMixType", Int::class.javaPrimitiveType)
                    .invoke(mixBuilder, mixTypeRecorders)
            }.onFailure { t ->
                // 部分 ROM 无 setMixType；记录但不致命（注册结果决定成败）
                Log.w(TAG, "setMixType not available: ${t.message}")
            }

            // route flags：必须是 ROUTE_FLAG_RENDER(0x02) —— 把 track 写入的数据渲染给匹配的录音客户端。
            // 之前误用 ROUTE_FLAG_LOOP_BACK(0x01)（把播放流复制给自己读），导致本 track 被当作额外播放源
            // 参与主混音：全局播放被静音 + 数据没有到达任何录音客户端（见 plan/09 第 3.0 节）。
            val routeFlagRender = runCatching {
                mixCls.getField("ROUTE_FLAG_RENDER").getInt(null)
            }.getOrDefault(2)
            mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, routeFlagRender)

            val mix = mixBuilderCls.getMethod("build").invoke(mixBuilder)

            // 3) 注册策略
            val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(context.applicationContext)
            policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, mix)
            val policy = policyBuilderCls.getMethod("build").invoke(policyBuilder)

            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val registerMethod = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls)
            val registerResult = registerMethod.invoke(am, policy) as Int
            if (registerResult != 0 /* AudioManager.SUCCESS */) {
                lastError = "registerAudioPolicy -> $registerResult"
                throw RouteException(lastError!!)
            }

            policyObject = policy
            mixObject = mix
            registered = true

            // 4) 创建注入 AudioTrack（由 policy 拥有）
            val trackSourceMethod = policyCls.getMethod("createAudioTrackSource", mixCls)
            val track = trackSourceMethod.invoke(policy, mix) as? AudioTrack
                ?: throw RouteException("createAudioTrackSource returned null")

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                lastError = "track state=${track.state}"
                throw RouteException(lastError!!)
            }

            Log.i(TAG, "policy registered; matched=$matched; track=$track")
            return RouteSessionResult(
                backendId = id,
                injector = PolicyTrackInjector(track, stereoExpansion = true),
                stereoInjection = true,
                notes = "策略已注册（匹配 $matched）。目标应用需在注册后重新打开录音以获得注入流。",
            )
        } catch (e: RouteException) {
            close(context)
            throw e
        } catch (t: Throwable) {
            close(context)
            // InvocationTargetException 只报 null 无法定位：递归找真实 cause
            var cause = t
            val causes = mutableListOf<String>()
            while (cause != null && causes.size < 6) {
                causes.add("${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause ?: break
            }
            lastError = causes.joinToString(" <- ")
            Log.e(TAG, "open failed: $lastError")
            throw RouteException(lastError!!, t)
        }
    }

    override fun close(context: Context) {
        if (registered) {
            try {
                val policyCls = Class.forName(CLS_POLICY)
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val unregister = AudioManager::class.java
                    .getMethod("unregisterAudioPolicyAsync", policyCls)
                policyObject?.let { unregister.invoke(am, it) }
            } catch (t: Throwable) {
                Log.w(TAG, "unregister failed", t)
            }
        }
        registered = false
        policyObject = null
        mixObject = null
    }

    /** 构造带 capture preset 的 AudioAttributes（SDK 隐藏方法名在 ROM 间有差异，逐个尝试）。 */
    private fun buildCaptureAttributes(preset: Int): AudioAttributes {
        val builder = AudioAttributes.Builder()
        val methods = listOf("setCapturePreset", "setInternalCapturePreset")
        for (name in methods) {
            try {
                AudioAttributes.Builder::class.java
                    .getMethod(name, Int::class.javaPrimitiveType)
                    .invoke(builder, preset)
                break
            } catch (_: Throwable) {
                // try next
            }
        }
        return builder.build()
    }

    /** 录音源常量 → 可读名（诊断/日志）。 */
    private fun presetName(preset: Int): String = when (preset) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VC"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VR"
        else -> "src$preset"
    }
}