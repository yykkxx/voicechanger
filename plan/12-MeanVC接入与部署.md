# 12 MeanVC 接入与部署（会话 #7）

> 2026-09-19 · 目标：把 MeanVC 变声器接入 App（后端优先级 **NPU → GPU → CPU**），构建 APK → 安装 → 同步进 Magisk 模块 `/data/adb/modules/voicechanger_priv/`。
> 关联：[10-MeanVC移植计划](./10-MeanVC移植计划.md) · [10-MeanVC状态总结](./10-MeanVC状态总结.md) · [06-实施进度](./06-实施进度.md) · [07-Magisk部署清单](./07-Magisk部署清单.md)

---

## 1. 结论摘要

| 项 | 状态 |
|---|---|
| App 侧 `AI 声线转换（MeanVC）` 处理模式 | ✅ 已实现、已编译、已安装（v1.2 / versionCode 3） |
| 推理底座 + 后端优先级 NPU→GPU→CPU | ✅ ONNX Runtime + NNAPI(NPU) → NNAPI-FP16/CPU_DISABLED(GPU) → XNNPACK(CPU)，逐级真实探测降级 |
| APK 安装 | ✅ `pm install -r -d` 成功，versionName 1.2 |
| Magisk 模块更新 | ✅ APK 已同步（MD5 `32e5ddaa…` 与构建产物一致），并新建 `models/` 目录 |
| **模型 ONNX 导出** | ⚠️ **部分完成**：`asr.onnx` ✅（86.5 MB 已就位）；`meanvc.onnx`/`vocos.onnx` 受 PyTorch/ONNX 导出器限制阻塞（见 §4） |

> 由于第三个模型尚未导出，AI 模式在真机上会以 **“模型未安装 → 自动直通”** 方式降级（不会崩溃、不影响其它模式）。

---

## 2. App 侧新增/修改

### 2.1 新增（全部在 `processing/meanvc/`，不改动既有处理器）

| 文件 | 作用 |
|---|---|
| `MeanCvConstants.kt` | 与 `run_rt.py` 对齐的全部常量（chunk 3200/720、fbank 23×80、ASR 缓存形状、DiT 20 帧/2 步、Vocos overlap 3 等） |
| `MeanCvBackend.kt` | 后端枚举与优先级：`NPU(NNAPI) → GPU(NNAPI+FP16+CPU_DISABLED) → CPU(XNNPACK)`，`openFirstAvailable()` 逐级真实建会话探测 |
| `MeanCvModels.kt` | 模型/参考音色文件管理（`files/models/meanvc/`，缺失时从 `/data/adb/modules/voicechanger_priv/models/`、`/sdcard/Download/VoiceChanger/models/` 拷贝）；解析 `voice.bin` |
| `MeanFbank.kt` | kaldi 风格 fbank（去直流 → 预加重 0.97 → povey 窗 → 512 点 FFT → 80 维 mel → ln），对齐 `torchaudio.compliance.kaldi.fbank` |
| `MeanCvSession.kt` | 流式链路复刻：ASR(chunk+att/cnn cache) → enc 拼接/线性插值 → DiT 2 步 → Vocos → 交叉淡化 |
| `MeanCvProcessor.kt` | `ProcessorEngine` 实现：48k↔16k、200 ms 分块、输出环形缓冲；模型缺失时直通兜底 |

### 2.2 修改

- `domain/PipelineModels.kt`：`ProcessorMode` 新增 `AI_MEANVC`
- `domain/CurrentParams.kt`：新增 `meanCvStatus` / `meanCvBackend`（UI 可读）
- `service/VoiceChangerService.kt`：模式构造分支、模式循环、通知标签、状态回填
- `ui/MainScreen.kt`、`overlay/OverlayController.kt`：模式标签
- `gradle/libs.versions.toml` + `app/build.gradle.kts`：
  - 新增 `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
  - `ndk.abiFilters = ["arm64-v8a"]`（APK 85 MB → 30 MB）
  - 版本 1.1(2) → **1.2(3)**

---

## 3. 部署结果（真机）

```bash
# 构建
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk  30,475,544 bytes  md5=32e5ddaa476b422aaac5d204e02822d9

# 安装
pm install -r -d app-debug.apk          # Success；versionName=1.2

# 同步进 Magisk 模块
cp app-debug.apk /data/adb/modules/voicechanger_priv/system/priv-app/VoiceChanger/VoiceChanger.apk
mkdir -p /data/adb/modules/voicechanger_priv/models
cp tools/meanvc/onnx/asr.onnx /data/adb/modules/voicechanger_priv/models/
```

- 模块 APK MD5 与构建产物一致 ✅
- `/data/adb/modules/voicechanger_priv/models/` 已建立（当前仅 `asr.onnx`）
- 说明：模块内 `/system/priv-app` 是**开机快照**，需重启后系统才会重新挂载扫描；即时生效走 `pm install`。

### 3.1 ONNX 模型放置位置（链路顺序）

```
/data/adb/modules/voicechanger_priv/models/
├── asr.onnx       ✅ 已就位（86.5 MB）
├── meanvc.onnx    ⬜ 待补（DiT）
├── vocos.onnx     ⬜ 待补（声码器）
└── voice.bin      ⬜ 待补（参考音色，PC 端预计算）
```

App 首次进入 AI 模式时会自动把上述文件从模块目录拷到应用私有目录 `files/models/meanvc/`。

---

## 4. 模型导出进展（Phase B）

### 4.1 已解决

1. **`prim::isinstance` 无法导出**：ASR 的 `position_encoding` 内 `isinstance(offset, Tensor)` 触发 `UnsupportedOperatorError`。
   → 注册自定义 ONNX 符号，恒返回 `False`（本实现中 `offset` 恒为 int）：
   ```python
   torch.onnx.register_custom_op_symbolic("prim::isinstance", lambda g,*a,**k: g.op("Constant", value_t=torch.tensor(False)), 17)
   ```
2. **示例输入必须自洽**：`att_cache` 非空时 `offset` 必须等于已有帧数（`offset=5, cache_t=5`），否则注意力掩码维度不匹配。
3. **cache 初值等价性**：`zeros(7,4,0,128)/zeros(7,1,256,0)` 与 `zeros(0,0,0,0)` 输出逐元素一致 → Kotlin 侧可固定形状。

导出脚本：`tools/meanvc/export_onnx_v2.py`（scripted wrapper + 动态轴）。

### 4.2 仍阻塞（需后续会话处理）

| 模型 | 阻塞点 | 错误 | 可行方向 |
|---|---|---|---|
| DiT `meanvc.onnx` | PyTorch 内部断言 | `outerNode->outputs().size() == node->inputs().size() INTERNAL ASSERT FAILED (dead_code_elimination.cpp:138)` | ① 图改写：把 `prim::TupleUnpack` 换成 `prim::TupleIndex`（脚本已写，需在子模块方法图上执行且避免段错误）；② 升级/降级 PyTorch 版本；③ 用 `TS2EPConverter` 走新导出器 |
| Vocos `vocos.onnx` | ONNX 不支持复数 | `Unknown number type: complex`（`decode` 内 iSTFT） | ① 仅导出 backbone+head 的实部部分、iSTFT 在 Kotlin 侧实现；② 换成 ONNX 友好的声码器（如 vocos 的 `torch.istft` 重写版）；③ 保留 TorchScript，用 pytorch_android 单独跑 vocos |

> DiT 的 kv_cache 语义已在探测中确认：`forward(x,t,r,cond,spks,prompts,cache,offset,kv_cache)`，`kv_cache` 为 4 层 `(k,v)`，形状 `[1,2,S,64]`；首块传 `None`，第二块传 `[None]*4` 才返回真实缓存（Kotlin 侧已按此实现）。

### 4.3 参考音色（`voice.bin`）

```bash
cd tools/meanvc
~/venv-meanvc/bin/python py/preprocess_ref.py --wav assets/ref.wav --out assets/ref.npz   # 需要 wavlm_large_finetune.pth (1.3G)
~/venv-meanvc/bin/python py/make_voice_bin.py --npz assets/ref.npz --out assets/voice.bin
cp assets/voice.bin /data/adb/modules/voicechanger_priv/models/
```
`voice.bin` 格式：`"MEANVC01"` + int32(spkDim=256, melBins=80, melFrames) + float32 spk + float32 mel(T×80)。

---

## 5. 会话 #8 追加修复

### 5.1 返听开关失效（关闭后仍能听到自己）

**现象**：返听开关处于关闭状态，仍持续听到处理后的自己。

**根因**：`AudioPipeline.outputEnabled` 默认 `true`。在监听/返听后端（`monitor`）下，
**主注入端就是返听端**，而服务启动会话时从未把该闸门归零，只有用户手动点开关才会写它。
于是「开关显示关闭」与「实际输出开启」脱节 → 关闭状态也在返听。

**修复**（`VoiceChangerService.startInternal`，第 6 步）：

```kotlin
_monitorEnabled.value = false
pipe.outputEnabled = selected.id != MonitorOnlyBackend.ID
```

- monitor 后端：会话启动即输出闸门关闭，**只有用户打开返听开关才开始返听**；
- 其它后端：主注入是送给目标应用的人声，保持开启（返听由独立 `monitorInjector` 承担）。

### 5.2 MeanVC 聚焦「男声 → 女声」

零样本 VC 只改音色、不改基频，男声送入后常得到「女声音色 + 男声基频」的不自然结果。
为此在模型前增加**女性化预处理链**（48 kHz 实时线程内整帧处理，保证连续性）：

```
48k 输入 → SMB 相位声码器变调(+5 半音, 共振峰 1.25) → TiltEq 明亮度(+2 dB) → 软限幅
        → 3:1 降采样到 16k → MeanVC(音色转换) → 1:3 升采样 → 输出
```

- 默认值在**切换到 AI 模式**时自动应用（UI 侧 `MainScreen` 模式单选；服务侧
  `startInternal` 兜底，覆盖通知/Intent 等其它入口）；
- 「音调 / 明亮度 / 共振峰」三个滑杆在 AI 模式下同样可用，可实时微调；
- 音色预设 chips（女声 +7 半音等）在 AI 模式下同样生效，作为更激进的备选。

### 5.3 本轮验证

- `./gradlew :app:assembleDebug` ✅（APK md5 `065ea3012254d4d2334e77282663a8b0`）
- `pm install -r -d` ✅（versionName 1.2）
- 模块 APK 同步 ✅（md5 一致）
- 启动冒烟：`am start` 成功，无 FATAL/崩溃

---

## 6. 后续 TODO

1. 补齐 `meanvc.onnx` / `vocos.onnx`（§4.2），再跑数值比对（与 PyTorch 逐元素误差 < 1e-4）。
2. 生成 `voice.bin` 并做端到端真机验证（含 RTF、NPU/GPU/CPU 实际命中情况，看 `logcat -s VC/MeanCvEP VC/MeanCv`）。
3. `MeanCvProcessor` 目前**在实时线程内同步推理**：若 RTF 不达标，改为独立推理线程 + 双缓冲（`plan/10` §3.2 原始设计）。
4. `pack.sh` 增加 `models/` 支持，模块 zip 一并分发模型。
5. 支持多参考音色热切换（`plan/10` §1.2 非目标，后续）。
