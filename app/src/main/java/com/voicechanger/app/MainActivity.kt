package com.voicechanger.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.voicechanger.app.domain.CurrentParams
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.ProcessorMode
import com.voicechanger.app.domain.VoicePreset
import com.voicechanger.app.service.VoiceChangerService
import com.voicechanger.app.ui.MainScreen
import com.voicechanger.app.ui.theme.VoiceChangerTheme

class MainActivity : ComponentActivity() {

    private var service: VoiceChangerService? = null

    /** 通知“绑定状态”供 UI 触发重组（Compose 无法直接观察普通字段）。 */
    private val serviceReady = androidx.compose.runtime.mutableStateOf(false)

    /** 挂起待执行的启动模式（用于授权后继续）。 */
    private var pendingStartMode: ProcessorMode? = null

    /** 用户选择的模式（默认内部变声）。 */
    private val selectedMode = androidx.compose.runtime.mutableStateOf(ProcessorMode.INTERNAL)

    private val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingStartMode != null) {
                val mode = pendingStartMode!!
                pendingStartMode = null
                startSessionInternal(mode)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? VoiceChangerService.LocalBinder
            service = local?.getService()
            serviceReady.value = service != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceReady.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            VoiceChangerTheme {
                MainScreen(
                    activity = this,
                    serviceProvider = { if (serviceReady.value) service else null },
                    selectedMode = selectedMode.value,
                    onSelectMode = { selectedMode.value = it },
                    onStart = { mode -> requestStartSession(mode) },
                    onStop = { service?.stopSession("ui") },
                    onMuteToggle = { muted -> service?.setMuted(muted) },
                    onMonitorToggle = { enabled -> service?.setMonitorEnabled(enabled) },
                    onNoiseToggle = { enabled -> service?.setNoiseEnabled(enabled) },
                    onOverlayToggle = { visible -> service?.setOverlayVisible(visible) },
                    onEffectsChanged = { params -> service?.updateEffects(params) },
                    onApplyPreset = { preset ->
                        val next = preset.apply(CurrentParams.effects)
                        CurrentParams.effects = next
                        CurrentParams.preset = preset
                        service?.updateEffects(next)
                        next
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, VoiceChangerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(connection)
        } catch (_: Throwable) {
        }
        service = null
        serviceReady.value = false
    }

    // ---------------- session control ----------------

    fun requestStartSession(mode: ProcessorMode) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartMode = mode
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startSessionInternal(mode)
    }

    private fun startSessionInternal(mode: ProcessorMode) {
        val intent = Intent(this, VoiceChangerService::class.java).apply {
            action = VoiceChangerService.ACTION_START
            putExtra(VoiceChangerService.EXTRA_MODE, mode.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** 供 Compose 读取当前服务实例（null = 未绑定）。 */
    fun currentService(): VoiceChangerService? = if (serviceReady.value) service else null

    /** 供 Compose 侧安全读取：当前生效的效应参数（含未启动时的 UI 缓存值）。 */
    fun currentEffects(): EffectParams = CurrentParams.effects

    fun currentPreset(): VoicePreset = CurrentParams.preset
}