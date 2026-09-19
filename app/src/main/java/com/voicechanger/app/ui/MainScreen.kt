package com.voicechanger.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechanger.app.MainActivity
import com.voicechanger.app.domain.CurrentParams
import com.voicechanger.app.domain.EffectParams
import com.voicechanger.app.domain.PipelineMetrics
import com.voicechanger.app.domain.PipelineState
import com.voicechanger.app.domain.ProcessorMode
import com.voicechanger.app.domain.VoicePreset
import com.voicechanger.app.route.AudioPolicyProbe
import com.voicechanger.app.route.CapabilityProbe
import com.voicechanger.app.route.RouteRegistry
import com.voicechanger.app.service.VoiceChangerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------------------------------------------------------------- root

@Composable
fun MainScreen(
    activity: MainActivity,
    serviceProvider: () -> VoiceChangerService?,
    selectedMode: ProcessorMode,
    onSelectMode: (ProcessorMode) -> Unit,
    onStart: (ProcessorMode) -> Unit,
    onStop: () -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onMonitorToggle: (Boolean) -> Unit,
    onNoiseToggle: (Boolean) -> Unit,
    onOverlayToggle: (Boolean) -> Unit,
    onEffectsChanged: (EffectParams) -> Unit,
    onApplyPreset: (VoicePreset) -> EffectParams,
) {
    var tab by remember { mutableStateOf(0) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 顶部标题 + 动态渐变条
            HeaderBar()

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("变声") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("诊断") })
            }

            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(140))
                },
                label = "tab",
            ) { current ->
                when (current) {
                    0 -> VoiceTab(
                        serviceProvider = serviceProvider,
                        selectedMode = selectedMode,
                        onSelectMode = onSelectMode,
                        onStart = onStart,
                        onStop = onStop,
                        onMuteToggle = onMuteToggle,
                        onMonitorToggle = onMonitorToggle,
                        onNoiseToggle = onNoiseToggle,
                        onOverlayToggle = onOverlayToggle,
                        onEffectsChanged = onEffectsChanged,
                        onApplyPreset = onApplyPreset,
                    )
                    else -> DiagnosticsTab()
                }
            }
        }
    }
}

@Composable
private fun HeaderBar() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Voice Changer",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF52E5C9),
                            Color(0xFF7CE577),
                            Color(0xFFFFE28A),
                            Color(0xFFB58CFF),
                            Color(0xFFFF7AC9),
                        )
                    )
                )
        )
    }
}

// ---------------------------------------------------------------- voice tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceTab(
    serviceProvider: () -> VoiceChangerService?,
    selectedMode: ProcessorMode,
    onSelectMode: (ProcessorMode) -> Unit,
    onStart: (ProcessorMode) -> Unit,
    onStop: () -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onMonitorToggle: (Boolean) -> Unit,
    onNoiseToggle: (Boolean) -> Unit,
    onOverlayToggle: (Boolean) -> Unit,
    onEffectsChanged: (EffectParams) -> Unit,
    onApplyPreset: (VoicePreset) -> EffectParams,
) {
    val context = LocalContext.current
    val service = serviceProvider()

    val state = service?.state?.collectAsState()?.value ?: PipelineState.Idle
    val metrics = service?.metrics?.collectAsState()?.value ?: PipelineMetrics()
    val muted = service?.muted?.collectAsState()?.value ?: false
    val monitor = service?.monitorEnabled?.collectAsState()?.value ?: false
    val overlayOn = service?.overlayVisible?.collectAsState()?.value ?: true
    val routeNotes = service?.lastRouteNotes?.collectAsState()?.value
    var noiseOn by remember { mutableStateOf(true) }

    // 判断：服务绑定且处于活动状态时，视为“正在变声”
    val isBusy = state is PipelineState.Checking || state is PipelineState.PreparingRoute || state is PipelineState.Starting
    val isRunning = state is PipelineState.Running || isBusy

    // UI 参数（本地编辑 + 发送到服务）
    var effects by remember { mutableStateOf(CurrentParams.effects) }
    var preset by remember { mutableStateOf(CurrentParams.preset) }

    fun pushEffects(next: EffectParams) {
        effects = next
        CurrentParams.effects = next
        onEffectsChanged(next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 状态卡 ----
        val stateColor by animateColorAsState(
            targetValue = when {
                state is PipelineState.Error -> MaterialTheme.colorScheme.error
                isRunning -> Color(0xFF2EBD85)
                else -> MaterialTheme.colorScheme.outline
            },
            animationSpec = tween(400),
            label = "stateColor",
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(stateColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isBusy -> "正在启动…"
                            state is PipelineState.Running -> "正在变声"
                            state is PipelineState.Error -> "出错"
                            else -> "未启动"
                        },
                        fontWeight = FontWeight.Bold, color = stateColor,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }

                val label = when (val s = state) {
                    is PipelineState.Running -> "模式 ${modeLabel(s.mode)} · 后端 ${s.backendId}"
                    is PipelineState.Error -> "${s.code}: ${s.message}"
                    is PipelineState.Recovering -> "恢复中: ${s.reason}"
                    else -> null
                }
                label?.let {
                    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                routeNotes?.let {
                    Text(
                        "路由: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        // ---- 主控按钮 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val startEnabled = !isRunning
            Button(
                onClick = { onStart(selectedMode) },
                enabled = startEnabled,
                modifier = Modifier.weight(1.6f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2EBD85)),
            ) {
                Text(if (startEnabled) "开始变声" else "运行中", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onStop,
                enabled = isRunning,
                modifier = Modifier.weight(1f),
            ) { Text("停止") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onMuteToggle(!muted) },
                enabled = state is PipelineState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(if (muted) "取消静音" else "静音") }

            OutlinedButton(
                onClick = { onMonitorToggle(!monitor) },
                enabled = state is PipelineState.Running,
                modifier = Modifier.weight(1f),
            ) { Text(if (monitor) "关闭返听" else "开启返听") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("输入降噪", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = noiseOn,
                onCheckedChange = { on -> onNoiseToggle(on) },
                enabled = true,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "高通+噪声门，去环境底噪",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("悬浮窗", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = overlayOn,
                onCheckedChange = { on -> onOverlayToggle(on) },
                enabled = true,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "运行时可拖动/缩小到右上角",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (state is PipelineState.Running && monitor) {
            Text(
                "返听：将变声结果同时播放到耳机（避免外放回授）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // ---- 模式选择 ----
        if (!isRunning) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("处理模式", style = MaterialTheme.typography.titleMedium)
                    ProcessorMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(selected = selectedMode == mode, onClick = {
                                    onSelectMode(mode)
                                    // AI 声线转换首版聚焦「男声→女声」：切换到该模式且音调未调整时，
                                    // 自动应用 +5 半音 / +2 dB 明亮度 / 1.25 共振峰 的女性化预处理
                                    if (mode == ProcessorMode.AI_MEANVC && effects.pitchSemitones == 0f) {
                                        preset = VoicePreset.CUSTOM
                                        pushEffects(
                                            effects.copy(
                                                pitchSemitones = 12f,
                                                eqTiltDb = 2f,
                                                formantRatio = 1.25f,
                                            )
                                        )
                                    }
                                })
                                .padding(vertical = 6.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedMode == mode,
                                onClick = null,
                            )
                            Text(modeLabel(mode), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // ---- 音色预设 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("音色", style = MaterialTheme.typography.titleMedium)
                    // 预设 chips（3 行布局）
                    val presets = VoicePreset.entries
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.take(4).forEach { p ->
                            PresetChip(p, preset == p) {
                                preset = p
                                pushEffects(onApplyPreset(p))
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.drop(4).take(4).forEach { p ->
                            PresetChip(p, preset == p) {
                                preset = p
                                pushEffects(onApplyPreset(p))
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.drop(8).forEach { p ->
                            PresetChip(p, preset == p) {
                                preset = p
                                pushEffects(onApplyPreset(p))
                            }
                        }
                    }

                    // ---- 参数滑杆 ----
                    if (selectedMode == ProcessorMode.INTERNAL || selectedMode == ProcessorMode.AI_MEANVC) {
                        ParamSlider("音调", effects.pitchSemitones, -12f, 12f, "半音") { v ->
                            preset = VoicePreset.detect(effects.copy(pitchSemitones = v))
                            pushEffects(effects.copy(pitchSemitones = v))
                        }
                        ParamSlider("明亮度", effects.eqTiltDb, -12f, 12f, "dB") { v ->
                            pushEffects(effects.copy(eqTiltDb = v))
                        }
                        ParamSlider("共振峰", effects.formantRatio, 0.5f, 2f, "x") { v ->
                            preset = VoicePreset.detect(effects.copy(formantRatio = v))
                            pushEffects(effects.copy(formantRatio = v))
                        }
                    }
                    // ---- AI 模式：RVC 音色选择 ----
                    if (selectedMode == ProcessorMode.AI_MEANVC) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "AI 声线（RVC Speaker）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val rvcPresets = com.voicechanger.app.processing.rvc.RvcProcessor.RvcVoicePreset.entries
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rvcPresets.take(4).forEach { p ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        service?.let { svc ->
                                            val proc = svc.currentProcessor
                                            if (proc is com.voicechanger.app.processing.rvc.RvcProcessor) {
                                                proc.setVoicePreset(p)
                                                pushEffects(effects.copy(pitchSemitones = p.f0Semitones))
                                            }
                                        }
                                    },
                                    label = { Text(p.label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF7C4DFF).copy(alpha = 0.2f),
                                    ),
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rvcPresets.drop(4).forEach { p ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        service?.let { svc ->
                                            val proc = svc.currentProcessor
                                            if (proc is com.voicechanger.app.processing.rvc.RvcProcessor) {
                                                proc.setVoicePreset(p)
                                                pushEffects(effects.copy(pitchSemitones = p.f0Semitones))
                                            }
                                        }
                                    },
                                    label = { Text(p.label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF7C4DFF).copy(alpha = 0.2f),
                                    ),
                                )
                            }
                        }
                        // RVC 状态信息
                        val rvcProc = service?.let { svc ->
                            svc.currentProcessor as? com.voicechanger.app.processing.rvc.RvcProcessor
                        }
                        rvcProc?.let { rp ->
                            Text(
                                "状态: ${rp.status} | 后端: ${rp.backendLabel} | 推理: ${rp.inferMs.toInt()}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } ?: run {
                            val rvcStatus = com.voicechanger.app.processing.rvc.RvcStatus.describe(context)
                            Text(
                                "模型: $rvcStatus",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (selectedMode == ProcessorMode.INTERNAL) {
                        ParamSlider("机器人感", effects.robotAmount, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(robotAmount = v))
                        }
                        ParamSlider("失真(怪兽)", effects.drive, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(drive = v))
                        }
                        ParamSlider("回声(空灵)", effects.echoAmount, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(echoAmount = v))
                        }
                        ParamSlider("合唱", effects.chorusAmount, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(chorusAmount = v))
                        }
                        ParamSlider("镶边", effects.flangerAmount, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(flangerAmount = v))
                        }
                        ParamSlider("颤音", effects.tremoloAmount, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(tremoloAmount = v))
                        }
                        ParamSlider("电话音", effects.telephoneAmount, 0f, 1f, "") { v ->
                            pushEffects(effects.copy(telephoneAmount = v))
                        }
                        ParamSlider("低音增强", effects.bassBoostDb, -12f, 12f, "dB") { v ->
                            pushEffects(effects.copy(bassBoostDb = v))
                        }
                        ParamSlider("临场感", effects.presenceDb, -12f, 12f, "dB") { v ->
                            pushEffects(effects.copy(presenceDb = v))
                        }
                    }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("噪声门", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = effects.noiseGateThresholdDb > -55f,
                                onCheckedChange = { on ->
                                    pushEffects(
                                        effects.copy(noiseGateThresholdDb = if (on) -45f else -60f)
                                    )
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            Text("输入增益", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = effects.inputGainDb > 0f,
                                onCheckedChange = { on ->
                                    pushEffects(effects.copy(inputGainDb = if (on) 6f else 0f))
                                },
                            )
                        }
                }
            }
        }

        // ---- 实时指标 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("实时指标", style = MaterialTheme.typography.titleMedium)
                val rms by animateFloatAsState(targetValue = metrics.rms, label = "rms")
                LinearProgressIndicator(
                    progress = { rms },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                MetricRow("采集 / 处理帧", "${metrics.captureFrames} / ${metrics.processedFrames}")
                MetricRow("丢帧(入/出)", "${metrics.inputDropped} / ${metrics.outputDropped}")
                MetricRow("underrun / 断流", "${metrics.underruns} / ${metrics.discontinuities}")
                MetricRow("处理耗时", "${metrics.avgProcessUs} µs")
            }
        }
    }
}

@Composable
private fun PresetChip(preset: VoicePreset, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(preset.label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF2EBD85).copy(alpha = 0.25f),
        ),
    )
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                formatValue(value) + if (unit.isNotEmpty()) unit else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

private fun formatValue(v: Float): String {
    val rounded = (v * 100).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- diagnostics tab

@Composable
private fun DiagnosticsTab() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        errorText = null
                        report = runCatching {
                            withContext(Dispatchers.IO) {
                                buildString {
                                    append("=== 能力报告 ===\n")
                                    append(CapabilityProbe.buildReportScoped(context))
                                    append("\n=== 路由后端评估 ===\n")
                                    append(RouteRegistry.buildReports(context))
                                }
                            }
                        }.onFailure { t ->
                            android.util.Log.e("VC/Diag", "buildReport failed", t)
                            errorText = "诊断失败: ${t.javaClass.simpleName}: ${t.message}"
                        }.getOrNull()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("生成能力报告") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        errorText = null
                        val probeText = runCatching {
                            withContext(Dispatchers.IO) {
                                val probe = AudioPolicyProbe(context)
                                val r1 = probe.probeEmptyPolicy()
                                val r2 = probe.probeLoopbackPlayerCapture()
                                buildString {
                                    append("=== AudioPolicy 注册探测 ===\n")
                                    append("空策略: rc=${r1.registerResult} ok=${r1.registerOk}")
                                    append(" unreg=${r1.unregisterOk} err=${r1.error ?: "-"}\n")
                                    append("LOOP_BACK: rc=${r2.registerResult} ok=${r2.registerOk}")
                                    append(" unreg=${r2.unregisterOk} err=${r2.error ?: "-"}")
                                }
                            }
                        }.onFailure { t ->
                            android.util.Log.e("VC/Diag", "probe failed", t)
                            errorText = "注册探测失败: ${t.javaClass.simpleName}: ${t.message}"
                        }.getOrNull()
                        if (probeText != null) {
                            report = (report ?: "") + "\n\n" + probeText
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("注册探测") }
        }

        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        errorText?.let { err ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    err,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        report?.let { text ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
            TextButton(onClick = {
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("VoiceChanger诊断", text))
                }
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制全部") }
        } ?: Text(
            "点击上方按钮生成诊断报告。\n包含：安装路径、特权权限、音频设备、音频路由 API 情况。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---------------------------------------------------------------- helpers

private fun stateText(state: PipelineState): String = when (state) {
    is PipelineState.Idle -> "已停止"
    is PipelineState.Checking -> "检查权限中…"
    is PipelineState.PreparingRoute -> "准备路由…"
    is PipelineState.Starting -> "启动音频…"
    is PipelineState.Running -> "运行中"
    is PipelineState.Recovering -> "恢复中"
    is PipelineState.Stopping -> "停止中…"
    is PipelineState.Error -> "错误：${state.code}"
}

private fun modeLabel(mode: ProcessorMode): String = when (mode) {
    ProcessorMode.PASSTHROUGH -> "直通（无处理）"
    ProcessorMode.INTERNAL -> "内部变声（推荐）"
    ProcessorMode.AI_MEANVC -> "AI 声线转换（RVC）"
    ProcessorMode.LOOPBACK_SOCKET -> "外部端口处理"
    ProcessorMode.MUTE -> "静音（测试用）"
}