package com.voicechanger.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voicechanger.app.MainActivity
import com.voicechanger.app.audio.AudioDeviceLookup
import com.voicechanger.app.audio.AudioPipeline
import com.voicechanger.app.audio.CaptureEndpoint
import com.voicechanger.app.audio.InjectionEndpoint
import com.voicechanger.app.domain.CurrentParams
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.ErrorCode
import com.voicechanger.app.domain.PipelineMetrics
import com.voicechanger.app.domain.PipelineState
import com.voicechanger.app.domain.ProcessorMode
import com.voicechanger.app.domain.SessionConfig
import com.voicechanger.app.processing.InternalDspProcessor
import com.voicechanger.app.processing.LoopbackSocketProcessor
import com.voicechanger.app.processing.MuteProcessor
import com.voicechanger.app.processing.PassthroughProcessor
import com.voicechanger.app.processing.ProcessorEngine
import com.voicechanger.app.processing.female.FemaleVoiceProcessor
import com.voicechanger.app.processing.rvc.RvcProcessor
import com.voicechanger.app.route.MonitorOnlyBackend
import com.voicechanger.app.route.RouteBackend
import com.voicechanger.app.route.RouteException
import com.voicechanger.app.route.RouteRegistry
import com.voicechanger.app.route.RouteRequest
import com.voicechanger.app.route.RouteSessionResult
import com.voicechanger.app.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 前台服务：唯一的音频资源所有者（plan/01 第 3.1 节）。
 *
 * 本版本改进（会话 #4）：
 * - 状态持久化：服务在系统内存中常驻运行，UI 通过绑定读取真实状态；
 * - 返听：可选的独立注入通道（耳机优先），与主注入互不影响；
 * - 参数：从 [CurrentParams] 读取效应链参数，运行中可实时更新；
 * - 通知：使用应用图标，标题 Voice Changer。
 */
class VoiceChangerService : Service() {

    companion object {
        private const val TAG = "VC/Service"
        private const val CHANNEL_ID = "vc_session"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.voicechanger.app.action.START"
        const val ACTION_STOP = "com.voicechanger.app.action.STOP"
        const val ACTION_MUTE = "com.voicechanger.app.action.MUTE"
        const val ACTION_UNMUTE = "com.voicechanger.app.action.UNMUTE"
        const val ACTION_TOGGLE_MONITOR = "com.voicechanger.app.action.TOGGLE_MONITOR"
        const val ACTION_TOGGLE_MUTE = "com.voicechanger.app.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_NOISE = "com.voicechanger.app.action.TOGGLE_NOISE"
        const val ACTION_CYCLE_MODE = "com.voicechanger.app.action.CYCLE_MODE"
        const val ACTION_TOGGLE_OVERLAY = "com.voicechanger.app.action.TOGGLE_OVERLAY"

        const val EXTRA_MODE = "mode"
        const val EXTRA_BACKEND_ID = "backend_id"
        const val EXTRA_TARGET_PACKAGE = "target_package"

        /** 服务实例（绑定失败时的兜底读取状态；正常路径用 LocalBinder）。 */
        @Volatile
        var instance: VoiceChangerService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceChangerService = this@VoiceChangerService
    }

    private val binder = LocalBinder()

    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val controlScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(ProcessorMode.PASSTHROUGH)
    val mode: StateFlow<ProcessorMode> = _mode.asStateFlow()

    private val _metrics = MutableStateFlow(PipelineMetrics())
    val metrics: StateFlow<PipelineMetrics> = _metrics.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _monitorEnabled = MutableStateFlow(false)
    val monitorEnabled: StateFlow<Boolean> = _monitorEnabled.asStateFlow()

    private val _noiseEnabled = MutableStateFlow(true)
    val noiseEnabled: StateFlow<Boolean> = _noiseEnabled.asStateFlow()

    private val _lastRouteNotes = MutableStateFlow<String?>(null)
    val lastRouteNotes: StateFlow<String?> = _lastRouteNotes.asStateFlow()

    /** 悬浮窗显示开关（通知/主界面可切换；会话运行中即时生效）。 */
    private val _overlayVisible = MutableStateFlow(true)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    // ---- 会话资源 ----
    private var config: SessionConfig = SessionConfig()
    private var backend: RouteBackend? = null
    private var pipeline: AudioPipeline? = null
    private var capture: CaptureEndpoint? = null
    private var processor: ProcessorEngine? = null
    /** 暴露当前处理器供 UI 查询状态。 */
    val currentProcessor: ProcessorEngine? get() = processor
    private var monitorInjector: InjectionEndpoint? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var metricsJob: Job? = null
    private var overlay: OverlayController? = null

    /** 当前是否有一个活动会话（含准备阶段）。供 UI 判断显示。 */
    val isSessionActive: Boolean
        get() = _state.value.let {
            it is PipelineState.Running || it is PipelineState.Starting ||
                it is PipelineState.PreparingRoute || it is PipelineState.Checking
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: ProcessorMode.PASSTHROUGH.name
                val newConfig = SessionConfig(
                    mode = runCatching { ProcessorMode.valueOf(modeName) }.getOrDefault(ProcessorMode.PASSTHROUGH),
                    backendId = intent.getStringExtra(EXTRA_BACKEND_ID),
                    targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE),
                    effects = CurrentParams.effects,
                )
                startSession(newConfig)
            }
            ACTION_STOP -> stopSession(reason = "user")
            ACTION_MUTE -> controlScope.launch { setMutedInternal(true) }
            ACTION_UNMUTE -> controlScope.launch { setMutedInternal(false) }
            ACTION_TOGGLE_MONITOR -> controlScope.launch { setMonitorInternal(!_monitorEnabled.value) }
            ACTION_TOGGLE_MUTE -> controlScope.launch { setMutedInternal(!_muted.value) }
            ACTION_TOGGLE_NOISE -> controlScope.launch { setNoiseInternal(!_noiseEnabled.value) }
            ACTION_CYCLE_MODE -> controlScope.launch { cycleModeInternal() }
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
            else -> Log.w(TAG, "unknown action=$intent?.action")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopSession(reason = "destroy")
        controlScope.cancel()
        super.onDestroy()
    }

    // ---------------- public control ----------------

    fun startSession(newConfig: SessionConfig) {
        controlScope.launch {
            try {
                startInternal(newConfig)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                failInternal(classify(t), t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun stopSession(reason: String) {
        controlScope.launch { stopInternal(reason) }
    }

    fun setMuted(muted: Boolean) {
        controlScope.launch { setMutedInternal(muted) }
    }

    fun setMonitorEnabled(enabled: Boolean) {
        controlScope.launch { setMonitorInternal(enabled) }
    }

    /** 输入降噪开关（实时生效，所有模式）。 */
    fun setNoiseEnabled(enabled: Boolean) {
        controlScope.launch { setNoiseInternal(enabled) }
    }

    /** 悬浮窗开关（主界面调用）。 */
    fun setOverlayVisible(visible: Boolean) {
        controlScope.launch { applyOverlayVisible(visible) }
    }

    /** 切换悬浮窗显示（通知/悬浮窗按钮调用）。 */
    private fun toggleOverlay() {
        controlScope.launch { applyOverlayVisible(!_overlayVisible.value) }
    }

    private suspend fun applyOverlayVisible(visible: Boolean) {
        if (_overlayVisible.value == visible) return
        _overlayVisible.value = visible
        if (visible) {
            if (isSessionActive) showOverlay()
        } else {
            hideOverlay()
        }
        updateNotification()
        Log.i(TAG, "overlayVisible=$visible")
    }

    private suspend fun setNoiseInternal(enabled: Boolean) {
        if (enabled == _noiseEnabled.value && pipeline?.noiseEnabled == enabled) return
        _noiseEnabled.value = enabled
        pipeline?.noiseEnabled = enabled
        updateNotification()
        Log.i(TAG, "noise=${if (enabled) "on" else "off"}")
    }

    private suspend fun cycleModeInternal() {
        val current = _mode.value
        val next = when (current) {
            ProcessorMode.PASSTHROUGH -> ProcessorMode.INTERNAL
            ProcessorMode.INTERNAL -> ProcessorMode.AI_MEANVC
            ProcessorMode.AI_MEANVC -> ProcessorMode.LOOPBACK_SOCKET
            ProcessorMode.LOOPBACK_SOCKET -> ProcessorMode.MUTE
            ProcessorMode.MUTE -> ProcessorMode.PASSTHROUGH
        }
        updateNotification()
        Log.i(TAG, "mode cycle: ${current.name} -> $next")
        // 启动新会话（会先停止当前再启动）
        startSession(config.copy(mode = next))
    }

    fun updateEffects(params: EffectParams) {
        controlScope.launch {
            config = config.copy(effects = params)
            CurrentParams.effects = params
            processor?.update(params)
        }
    }

    // ---------------- internal state machine ----------------

    private suspend fun startInternal(newConfig: SessionConfig) {
        when (_state.value) {
            is PipelineState.Running, is PipelineState.Starting, is PipelineState.PreparingRoute -> {
                Log.i(TAG, "already active; restarting")
                stopInternal("restart")
            }
            else -> Unit
        }

        _state.value = PipelineState.Checking
        config = newConfig
        _mode.value = newConfig.mode
        _muted.value = false

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failInternal(ErrorCode.PERMISSION_RECORD_AUDIO_MISSING, "缺少 RECORD_AUDIO，请先在界面授权")
            return
        }

        startForegroundCompat()

        // 2. 选择后端（策略注册失败时回退监听模式）
        var selected = RouteRegistry.selectAuto(this, newConfig.backendId, newConfig.allowMonitorFallback)
        var noteSuffix = ""
        _state.value = PipelineState.PreparingRoute(selected.id)

        var routeResult: RouteSessionResult? = null
        var openError: Throwable? = null
        try {
            routeResult = selected.open(this, RouteRequest(targetPackage = newConfig.targetPackage))
        } catch (t: Throwable) {
            openError = t
        }

        if (routeResult == null && selected.id != MonitorOnlyBackend.ID && newConfig.allowMonitorFallback) {
            Log.w(TAG, "route open failed: ${openError?.message}; fallback to monitor")
            selected = RouteRegistry.byId(MonitorOnlyBackend.ID)!!
            noteSuffix = "（原后端失败，已回退监听：${openError?.message}）"
            routeResult = try {
                selected.open(this, RouteRequest(targetPackage = newConfig.targetPackage))
            } catch (t2: Throwable) {
                failInternal(ErrorCode.ROUTE_REGISTRATION_FAILED, "回退监听也失败: ${t2.message}")
                return
            }
        }

        if (routeResult == null) {
            val msg = (openError as? RouteException)?.reason ?: openError?.message ?: "unknown"
            failInternal(ErrorCode.ROUTE_REGISTRATION_FAILED, "路由打开失败: $msg")
            return
        }

        backend = selected
        _lastRouteNotes.value = routeResult.notes + noteSuffix
        Log.i(TAG, "route opened: ${routeResult.notes}")

        // 3. 处理器
        val engine: ProcessorEngine = when (newConfig.mode) {
            ProcessorMode.PASSTHROUGH -> PassthroughProcessor()
            ProcessorMode.INTERNAL -> InternalDspProcessor()
            ProcessorMode.LOOPBACK_SOCKET -> LoopbackSocketProcessor(host = "127.0.0.1", port = 18765)
            // AI 声线转换：RVC（开源模型，近乎真实音色）；模型缺失时回退内置女声引擎
            ProcessorMode.AI_MEANVC ->
                if (com.voicechanger.app.processing.rvc.Rvc.isReady(this)) {
                    com.voicechanger.app.processing.rvc.RvcProcessor(this)
                } else {
                    com.voicechanger.app.processing.female.FemaleVoiceProcessor()
                }
            ProcessorMode.MUTE -> MuteProcessor()
        }
        // AI 声线转换默认「男声→女声」：升八度（男 ~110Hz → 女 ~220Hz）
        val effectParams = if (newConfig.mode == ProcessorMode.AI_MEANVC && newConfig.effects.pitchSemitones == 0f) {
            newConfig.effects.copy(pitchSemitones = 12f, eqTiltDb = 2f, formantRatio = 1.25f)
                .also {
                    config = config.copy(effects = it)
                    CurrentParams.effects = it
                }
        } else {
            newConfig.effects
        }
        engine.configure(com.voicechanger.app.domain.StreamFormat.STANDARD, effectParams)
        processor = engine
        when (engine) {
            is RvcProcessor -> {
                CurrentParams.aiStatus = engine.status
                CurrentParams.aiBackend = engine.backendLabel
                Log.i(TAG, "RVC status=${engine.status} active=${engine.active}")
            }
            is FemaleVoiceProcessor -> {
                CurrentParams.aiStatus = "内置女声引擎（RVC 模型未安装，自动回退）"
                CurrentParams.aiBackend = "DSP"
                Log.i(TAG, "AI mode fallback -> built-in female engine")
            }
            else -> Unit
        }

        // 4. 主注入端
        _state.value = PipelineState.Starting
        val injector = routeResult.injector
        try {
            injector.open()
            injector.start()
        } catch (t: Throwable) {
            Log.e(TAG, "injector open failed", t)
            failInternal(ErrorCode.INJECT_INIT_FAILED, "注入端初始化失败: ${t.message}")
            return
        }

        // 5. 采集
        val captureEndpoint = CaptureEndpoint()
        try {
            captureEndpoint.open()
        } catch (t: Throwable) {
            Log.e(TAG, "capture open failed", t)
            try { injector.close() } catch (_: Throwable) {}
            failInternal(ErrorCode.CAPTURE_INIT_FAILED, "采集端初始化失败: ${t.message}")
            return
        }
        capture = captureEndpoint

        // 6. 流水线
        val pipe = AudioPipeline(engine)
        pipe.injector = injector
        pipeline = pipe
        // 返听默认关闭：AudioPipeline.outputEnabled 默认 true，而监听/返听后端下
        // 「主注入 == 返听」，若不显式归零会出现「返听开关是关的，但一直能听到自己」
        // （会话 #8 修复）。非监听后端下主注入是给目标应用的人声，必须保持开启。
        _monitorEnabled.value = false
        pipe.outputEnabled = selected.id != MonitorOnlyBackend.ID
        pipe.start()

        // 7. 采集回调
        captureEndpoint.start(object : CaptureEndpoint.Listener {
            override fun onFrame(frame: ShortArray, timestampUs: Long) {
                pipe.pushCaptureFrame(frame)
            }

            override fun onStopped(reason: String) {
                Log.w(TAG, "capture stopped: $reason")
                controlScope.launch {
                    if (_state.value is PipelineState.Running) {
                        _state.value = PipelineState.Recovering(reason)
                    }
                }
            }
        })

        acquireWakeLock()
        startMetricsLoop()

        _state.value = PipelineState.Running(newConfig.mode, selected.id)
        updateNotification()
        showOverlay()
        Log.i(TAG, "session running: mode=${newConfig.mode} backend=${selected.id}")
    }

    private suspend fun stopInternal(reason: String) {
        if (_state.value is PipelineState.Idle && pipeline == null) return
        _state.value = PipelineState.Stopping

        metricsJob?.cancel()
        metricsJob = null

        stopMonitorInternal()

        try { capture?.close() } catch (t: Throwable) { Log.w(TAG, "capture close", t) }
        capture = null

        try { pipeline?.stop() } catch (t: Throwable) { Log.w(TAG, "pipeline stop", t) }
        pipeline = null

        try { processor?.close() } catch (t: Throwable) { Log.w(TAG, "processor close", t) }
        processor = null

        try { backend?.close(this) } catch (t: Throwable) { Log.w(TAG, "backend close", t) }
        backend = null

        releaseWakeLock()
        _muted.value = false
        _state.value = PipelineState.Idle
        hideOverlay()
        stopForegroundCompat()

        Log.i(TAG, "session stopped: $reason")
    }

    private fun setMonitorInternal(enabled: Boolean) {
        if (enabled == _monitorEnabled.value) return
        val isMonitorBackend = backend?.id == MonitorOnlyBackend.ID

        if (enabled) {
            if (_state.value !is PipelineState.Running) {
                Log.w(TAG, "monitor requested but session not running")
                return
            }
            // monitor 后端本就把处理流输出到耳机（主注入=返听），开关实体是
            // 输出闸门 pipeline.outputEnabled（plan/09 第 7.7 节：此前开关无实体，
            // 无论开/关都一直有返听）。
            if (isMonitorBackend) {
                Log.i(TAG, "monitor=on (backend already monitor; gate output)")
                pipeline?.outputEnabled = true
                _monitorEnabled.value = true
                updateNotification()
                return
            }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val safe = AudioDeviceLookup.findSafeMonitorDevice(am)
            val inj = InjectionEndpoint(preferredDevice = safe, stereoExpansion = true)
            try {
                inj.open()
                inj.start()
            } catch (t: Throwable) {
                Log.e(TAG, "monitor open failed", t)
                try { inj.close() } catch (_: Throwable) {}
                return
            }
            monitorInjector = inj
            pipeline?.monitorInjector = inj
            pipeline?.outputEnabled = true
            _monitorEnabled.value = true
            Log.i(TAG, "monitor enabled, device=${safe?.productName ?: "default"}")
        } else {
            stopMonitorInternal()
            _monitorEnabled.value = false
        }
        updateNotification()
    }

    /** 上锁环境内的简化版本（controlScope 内调用）。 */
    private fun stopMonitorInternal() {
        pipeline?.monitorInjector = null
        pipeline?.outputEnabled = false
        try { monitorInjector?.close() } catch (_: Throwable) {}
        monitorInjector = null
        _monitorEnabled.value = false
    }

    private suspend fun setMutedInternal(muted: Boolean) {
        if (_state.value !is PipelineState.Running) return
        _muted.value = muted
        val p = config.effects.copy(
            outputGainDb = if (muted) -120f else config.effects.outputGainDb,
            limiterEnabled = true,
        )
        processor?.update(p)
        updateNotification()
    }

    private fun failInternal(code: ErrorCode, message: String) {
        Log.e(TAG, "session error: $code $message")
        _state.value = PipelineState.Error(code, message)
        updateNotification()
        stopMonitorInternal()
        try { capture?.close() } catch (_: Throwable) {}
        capture = null
        try { pipeline?.stop() } catch (_: Throwable) {}
        pipeline = null
        try { backend?.close(this) } catch (_: Throwable) {}
        backend = null
        releaseWakeLock()
    }

    private fun classify(t: Throwable): ErrorCode = when (t) {
        is SecurityException -> ErrorCode.PERMISSION_RECORD_AUDIO_MISSING
        is IllegalStateException -> ErrorCode.CAPTURE_INIT_FAILED
        else -> ErrorCode.INTERNAL_ERROR
    }

    // ---------------- metrics ----------------

    private fun startMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = controlScope.launch {
            while (true) {
                pipeline?.let { _metrics.value = it.snapshotMetrics() }
                overlay?.update(
                    state = _state.value,
                    mode = _mode.value,
                    muted = _muted.value,
                    monitor = _monitorEnabled.value,
                    noise = _noiseEnabled.value,
                    metrics = _metrics.value,
                )
                kotlinx.coroutines.delay(500)
            }
        }
    }

    // ---------------- overlay ----------------

    private fun showOverlay() {
        if (!_overlayVisible.value) return
        if (overlay == null) overlay = OverlayController(this)
        overlay?.show()
    }

    private fun hideOverlay() {
        overlay?.dismiss()
        overlay = null
    }

    // ---------------- foreground / notification ----------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "变声会话",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示实时变声会话状态与停止按钮"
                }
            )
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关键：**不含 FOREGROUND_SERVICE_TYPE_MICROPHONE**。
            // Android 14+ 对含 MICROPHONE 类型的后台服务会冻结麦克风输入（后台返听直接没声）；
            // 仅用 MEDIA_PLAYBACK（返听输出）+ 普通 RECORD_AUDIO 采集即可在后台持续工作
            // （plan/09 第 7.8 节：ColorOS 后台返听消失的根因 = MICROPHONE 前台类型）。
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceChangerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val monitorIntent = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceChangerService::class.java).setAction(ACTION_TOGGLE_MONITOR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val muteIntent = PendingIntent.getService(
            this, 3,
            Intent(this, VoiceChangerService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val noiseIntent = PendingIntent.getService(
            this, 4,
            Intent(this, VoiceChangerService::class.java).setAction(ACTION_TOGGLE_NOISE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val overlayIntent = PendingIntent.getService(
            this, 5,
            Intent(this, VoiceChangerService::class.java).setAction(ACTION_TOGGLE_OVERLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stateText = when (val s = _state.value) {
            is PipelineState.Running -> {
                val flags = buildList {
                    if (_muted.value) add("已静音")
                    if (_monitorEnabled.value) add("返听开")
                    if (!_noiseEnabled.value) add("降噪关")
                }
                val extra = if (flags.isEmpty()) "" else "（${flags.joinToString("、")}）"
                "运行中：${modeLabel(s.mode)}$extra"
            }
            is PipelineState.Error -> "错误：${s.code}"
            PipelineState.Idle -> "已停止"
            else -> s.toString()
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.voicechanger.app.R.drawable.ic_stat_voice)
            .setContentTitle("Voice Changer")
            .setContentText(stateText)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            // 快捷操作按钮
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .addAction(
                if (_monitorEnabled.value) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (_monitorEnabled.value) "返听关" else "返听开",
                monitorIntent,
            )
            .addAction(
                if (_muted.value) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_media_play,
                if (_muted.value) "取消静音" else "静音",
                muteIntent,
            )
            .addAction(
                if (_noiseEnabled.value) android.R.drawable.ic_menu_search
                else android.R.drawable.ic_menu_report_image,
                if (_noiseEnabled.value) "降噪关" else "降噪开",
                noiseIntent,
            )
            .addAction(
                if (_overlayVisible.value) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_menu_view,
                if (_overlayVisible.value) "关悬浮窗" else "开悬浮窗",
                overlayIntent,
            )
            .build()
    }

    private fun modeLabel(mode: ProcessorMode): String = when (mode) {
        ProcessorMode.PASSTHROUGH -> "直通"
        ProcessorMode.INTERNAL -> "变声"
        ProcessorMode.AI_MEANVC -> "AI声线"
        ProcessorMode.LOOPBACK_SOCKET -> "外部"
        ProcessorMode.MUTE -> "静音"
    }

    // ---------------- wake lock ----------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceChanger::Session").apply {
            setReferenceCounted(false)
            // 无限期持有（停止时 releaseWakeLock 释放），配合 START_STICKY 保证后台存活
            // （plan/09 第 7.7 节：ColorOS 杀进程 + 30min 超时锁 = 后台返听消失根因）。
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }
}