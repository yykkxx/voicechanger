# 10 MeanVC 实时语音转换移植计划

> 状态：v0.1 · 2026-09-19 · 起草（会后按执行进度更新）
> 目标：将 [ASLP-lab/MeanVC](https://github.com/ASLP-lab/MeanVC)（轻量级流式零样本语音转换）移植进本 App（Voice Changer，`com.voicechanger.app`）。
> 关联：[01-总体架构](./01-总体架构.md) · [03-处理引擎与本地端口协议](./03-处理引擎与本地端口协议.md) · [06-实施进度](./06-实施进度.md)

---

## 1. 背景与目标

现有 App 已实现实时变声链路：采集(48k) → ProcessorEngine → 注入(48k)，其中
`InternalDspProcessor` 目前为 DSP 类音效（音高/共振峰），**不具备 AI 声线转换/音色克隆能力**。

本计划引入 MeanVC，提供**零样本音色转换**：
输入任意说话人语音 + 一句目标说话人参考音频，实时把音色转为目标说话人，
保留语言内容与韵律。

### 1.1 范围（首版）

| 项 | 范围 |
|---|---|
| 推理模型 | MeanVC 完整链路：ASR(fastu2) + DiT(meanvc_200ms) + Vocoder(vocos) + 参考音色编码(WavLM→仅初始化时) |
| 运行设备 | Android 真机（当前为 OnePlus/ColorOS，aarch64），离线推理 |
| 处理模式 | 新增 `AI 声线转换` 处理模式，与现有 Internal/Loopback 并列（第三条 ProcessorEngine 实现） |
| 采样率 | 兼容现有 48kHz 管线：48k → 16k 降采样输入 → MeanVC(16k) → 48k 升采样输出 |
| 后端优先级 | **NPU（NNAPI/QNN/专用 EP）→ GPU（Vulkan/OpenCL）→ CPU（XNNPACK/NEON）** |

### 1.2 非目标（首版）

- 不在本阶段训练/微调模型（用官方预训练权重）；
- 不实现多说话人实时热切换（首版切换参考音色 = 重建会话/重载缓存）；
- 不承诺所有 ROM/NPU 均可用：NPU 算子覆盖不足时按计划降级 GPU/CPU；
- 不做云端推理，纯端侧。

---

## 2. MeanVC 技术拆解

### 2.1 推理链路（来自 `src/runtime/run_rt.py` 实测源码）

```
16kHz int16 PCM（CHUNK=3200 samples ≈ 200ms）
  → samples_cache 拼接（保留尾部 720 samples）
  → extract_fbanks(frame_shift=10ms, kaldi fbank, 80 bins)   # [1, 23, 80]
  → ASR: fastu2++.pt forward_encoder_chunk(fbanks, offset, required_cache_size, att_cache, cnn_cache)
        （Wenet 风格 chunk 推理：decoding_chunk_size=5, left_chunks=2, subsampling=4, context=7）
  → encoder_output（5 frame × 256）
  → encoder_output_cache 帧拼接
  → interpolate ↑ 4× → [6→21 frame × 256]（vc_chunk=20 + 1）
  → DiT: meanvc_200ms.pt(x_noise, t, r, cache, cond=bn, spks, prompts, offset, kv_cache)
        （默认 steps=2：t∈[1,0.8,0]，velocity u，x = x - (t-r)*u）
  → mel = x.transpose(1,2)  # [1, 80, 20]
  → Vocos: vocos.pt decode(mel)  # → 16kHz wav
  → 与 last_wav 尾部 320 samples 交叉淡化（down/up linspace）
  → 输出处理后人声（16kHz float32）
```

### 2.2 模型清单与尺寸预期

| 模型 | 文件 | 类型 | 输入 | 输出 | 说明 |
|---|---|---|---|---|---|
| ASR 特征 | `fastu2++.pt` | TorchScript (Wenet U2++) | fbank chunk [1,23,80] + 状态 | bn [1,5,256] + att_cache + cnn_cache | 流式编码器 |
| 声学模型 | `meanvc_200ms.pt` | TorchScript (DiT + MRTE) | noise[1,20,80] + cond[1,20,256] + spks[1,256] + prompts[1,P,80] + t/r + kv_cache | velocity u [1,20,80] + kv_cache | 2 步采样 |
| 声码器 | `vocos.pt` | TorchScript (Vocos) | mel [1,80,T] | wav [1,16k*T/s] | 波形生成 |
| 音色嵌入 | `wavlm_large_finetune.pth` | PyTorch state_dict | 参考 wav | spk_emb [1,256] | 仅初始化执行一次 |

### 2.3 关键实现难点

1. **TorchScript → ONNX**：3 个 .pt 需用 `torch.onnx.export` 导出；流式 ASR 与 DiT 都带**可变状态缓存**，需固定导出时的时序长度（chunk 模式）或动态轴；
2. **前端特征（fbank/Mel）**：kaldi fbank(torchaudio) 与 librosa mel 的 STFT → 需用 ONNX 可表达算子重建或直接用 Kotlin/JNI 实现（在线程内零分配）；
3. **状态缓存管理**：att_cache/cnn_cache/kv_cache 均需在 Java/Kotlin 侧维护并在推理间隙保存；
4. **DiT 自定义算子**：RotaryEmbedding、scaled_dot_product_attention、RMSNorm、chunk mask → 关注算子是否落入 NNAPI/QNN 支持集；
5. **Vocos 解码**：ConvNeXt 等卷积算子对 NPU 支持较友好，但仍需验证；
6. **数值一致性**：ONNX 输出必须与 PyTorch 输出做逐元素/余弦比对。

### 2.3.1 关键简化：WavLM 说话人嵌入只在端外预计算

`run_rt.py` 中 `spk_emb` 与 `prompt_mel` **仅对参考音频计算一次**（构造 `VCRunner` 时），
不在每 chunk 重新推理。因此：

- **端上不需要部署 WavLM/ECAPA 模型**（依赖 fairseq/s3prl，体积大、转换复杂）；
- 转换参考音频到 spk_emb[1,256] + prompt_mel[1,T,80] 可在 **PC/Python 端完成**（或在 App 首次设置参考音色时调用一次）；
- 输出为几十 KB 的张量文件（`.npz`），随参考音色一并管理；
- 若无法获得 `wavlm_large_finetune.pth`，仍有备用方案：用 HF 上等价的 ECAPA-WavLM 微调权重，或纯 ECAPA-TDNN(fbank) 版本自行微调——但这些会改变音色质量，优先级排在官方权重之后。

> 这使 Android 端实际推理模型收敛为 **3 个 TorchScript/VONNX：fastu2++（ASR）、meanvc_200ms（DiT）、vocos（Vocoder）**，大幅降低部署体积与复杂度。

### 2.4 后端选型（按用户指定优先级）

| 优先级 | 后端 | 实现 | 适用 | 风险 |
|---|---|---|---|---|
| 1 | NPU | ONNX Runtime NNAPI EP / Qualcomm QNN EP / 昇腾 HiAI | 高通/MTK/麒麟设备 | 算子覆盖不全 → 需回退拆分算子组或降级 |
| 2 | GPU | ONNX Runtime(无官方 GPU EP on Android，实际用 ncnn/Vulkan 或 MNN/OpenCL 路线) | 通用 | Transformer 深度需调优；功耗高 |
| 3 | CPU | ONNX Runtime CPU EP（XNNPACK/ArmNN-NNAPI 关闭）/ 手写 JNI | 所有设备 | RTF 需 < 1（实测目标 ≤0.7） |

> 首版采用 **ONNX Runtime Android** 作为统一推理底座（支持 NNAPI EP + CPU EP 双路径，一个 .onnx 两用），
> 若 NPU EP 算子覆盖不足，将 DiT 大算子按"支持/不支持"拆分，不支持部分 CPU 兜底（混合 EP 策略），
> 仍不可行再评估 ncnn/Vulkan（GPU）路线。该决策在 Phase A 用真实 .onnx 算子清单验证。

---

## 3. 接入现有 App（不干扰主代码）

### 3.1 新增模块/文件（全部新建，不改动现有文件）

```text
app/src/main/
├── java/com/voicechanger/app/
│   ├── processing/
│   │   └── meanvc/                      # 新 AI VC 处理器（与 InternalDspProcessor 并列）
│   │       ├── MeanCvProcessor.kt       # implements ProcessorEngine（48k↔16k + 分块 + 状态机）
│   │       ├── MeanCvSession.kt         # 会话：模型加载、参考音色、缓存状态
│   │       ├── MeanCvBackend.kt         # 后端抽象：NNAPI/GPU/CPU 枚举与策略
│   │       ├── MeanCvConstants.kt       # chunk/帧/缓存常量（对齐 run_rt.py）
│   │       └── Frontend.kt              # fbank/mel 前端（由 ONNX 图或 Kotlin 实现）
│   ├── data/                            # 模型文件位置与下载管理（assets 或首启拷贝）
│   └── ui/effects/                      # （后续）AI 模式 UI 接入
├── jniLibs/                             # onnxruntime .so（aar 自带）或手动放置
└── assets/models/                       # 打包模型（首版可外部下载到 filesDir）
```

### 3.2 数据流集成

```
CaptureEndpoint(48k) ── ring ──> ProcessorEngine.process(short[], 480/10ms)
        │  MeanCvProcessor 内部：
        │    48k float 累积 → 16k 降采样缓冲（≥3200 samples）
        │    → 每 200ms 触发一次 MeanVC chunk 推理
        │    → 输出 16k wav → 48k 升采样 → 输出 ring
        └──────────────────> InjectionEndpoint(48k)
```

- 首版可接受 **200ms 块延迟 + 处理耗时**（目标端到端 P95 ≤ 800ms，AI 模式不以 100ms 实时为验收线）；
- 处理线程不阻塞音频线程：chunk 就绪后投递给推理工作线程，输出经交叉淡化写回。

### 3.3 模式选择

- `SessionConfig` 增加 `mode = AI_MEANVC`；
- `Promise`：切换参照音频时重建 `MeanCvSession`（重新推理 spk_emb/prompt_mel），会话切换期间输出静音或原声兜底。

---

## 4. 落地阶段划分

| 阶段 | 内容 | 产出 | 验收 |
|---|---|---|---|
| **A. 环境与模型**（本次开始） | 装 Python 依赖；下载 4 个权重；离线 infer 跑通一条 wav | `tools/meanvc/models/*.pt|*.pth`；参考输出 wav | 转换后的 wav 音色与原始 run_rt 一致 |
| **B. ONNX 转换** | 导出 fastu2/meanvc/vocos 三个 ONNX；数值比对 | `tools/meanvc/onnx/*.onnx` | max-abs/余弦误差 < 1e-4（fp32） |
| **C. 桌面端集成验证** | onnxruntime Python 复刻完整流式链路（含缓存） | `tools/meanvc/rt_onnx.py` | 与 PyTorch 输出一致，RTF 记录 |
| **D. Android 接入** | Gradle 引入 onnxruntime-android；MeanCvProcessor 实现；后端策略（NNAPI→CPU） | app 内可运行 AI 模式 | 真机 WAV 转换成功；RTF 达标 |
| **E. 后端调优** | 按设备实测：NNAPI EP → GPU(如引入) → CPU；算子拆分/混合 EP | 性能报告 | 按 2.4 优先级逐级落地 |
| **F. UI 与发布** | AI 模式 UI、参考音频选择、模型管理、打包 | 完整功能 | 验收清单通过 |

> 当前会话执行 **Phase A（含 A/B 边界）** 与文档落盘；不触碰 app 主代码。

---

## 5. 目录规划（本次前端工作放 tools/，不污染 app）

```text
tools/meanvc/
├── README.md                 # 本目录说明
├── models/                   # 权重（.pt/.pth，gitignore）
│   ├── meanvc_200ms.pt
│   ├── fastu2++.pt
│   ├── vocos.pt
│   └── wavlm_large_finetune.pth
├── onnx/                     # 导出的 ONNX 模型
├── py/                       # 转换/验证脚本
│   ├── env_check.py
│   ├── download_models.py
│   ├── export_onnx.py
│   └── verify_onnx.py
├── rt_onnx.py                # onnxruntime 流式链路复刻（Phase C）
├── assets/                   # 测试音频与参考输出
└── notes/                    # 算子支持清单、数值报告
```

---

## 6. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| NPU 不支持关键算子（RotaryEmbedding/SDPA/RMSNorm） | 高 | 算子拆分 + CPU 兜底；若仍失败降 GPU/纯 CPU 并如实报告 |
| WavLM 权重需 Google Drive 手动下载 | 中 | 从 HF 镜像找等价权重（wavlm-large 微调版）或 hf 缓存替代 |
| onnxruntime-android 体积（~20-40MB/abi） | 中 | 按 abi 分包投递；release 用 abiFilters |
| 200ms 延迟对通话体验影响 | 中 | AI 模式定位"声线切换"，默认降采样到 100ms chunk 尝试；文档记录 RTF |
| 模型文件大（fastu2+vocos ≈ 数百 MB） | 中 | 首版模型放外部下载（filesDir），不打包进 APK；由 UI 引导下载 |
| 数值精度（fp16 加速 vs fp32 一致） | 低 | 先 fp32 保正确；性能不足再试 fp16 并记录误差 |

---

## 7. 与既有文档的衔接

- 处理引擎契约沿用 [plan/03 第 1 节](./03-处理引擎与本地端口协议.md) 的 `ProcessorEngine`；
- 标准帧格式仍为 48k/16bit/mono/10ms（[plan/01 5.1](./01-总体架构.md)），MeanVC 内部 16k 是引擎边界内的格式适配；
- 部署方式（Magisk priv-app / 普通安装）不因本次改变，模型文件放置于应用私有目录；
- 本计划执行不修改（或尽量少改）既有 `InternalDspProcessor` 等文件，全部以新增文件方式接入。

---

## 8. 验收标准（Phase A/B 里程碑）

- [ ] `tools/meanvc/models/` 下 4 个权重齐全（wavlm 若 HF 无法获取，记录替代方案）；
- [ ] 一条 3s 测试 wav 经 `infer_ref.py`（PyTorch）成功转换，输出正常；
- [ ] 三个 ONNX 导出成功且与 PyTorch 输出数值比对通过（误差阈值见阶段 B）；
- [ ] 本计划文档随进度更新；每步操作先落盘再执行，且不触碰 app 主源码。

---

## 9. 执行日志（Phase A 进行中）

### 2026-09-19 会话 #6 · Phase A 环境与模型

### 2026-09-19 会话 #6 · Phase A 环境与模型

**已完成**：
- [x] 下载 MeanVC 源码 → `tools/MeanVC-src/`（62 文件，含 runtime/infer/model/vocos）；
- [x] 通读推理链路 `run_rt.py` / `infer_ref.py` / `dit_kvcache.py` / `prompt_vp.py` / `modules.py`，确认流式 chunk 参数：
  ASR `decoding_chunk_size=5, left_chunks=2, subsampling=4, context=7, stride=20, CHUNK=3200 samples`；DiT `vc_chunk=20, steps=2, kv_cache_max_len=100`；Vocos `overlap=3, wav_overlap=320 samples`；
- [x] Py3.12 venv 创建：`~/venv-meanvc`（torch 2.5.1 / torchaudio 2.5.1 / onnx 1.23.0 / onnxruntime 1.30.0 / numpy 2.5.3 / matplotlib 3.11.2 / fairseq 0.12.2 / s3prl 0.4.1 / accelerate 1.15.0 / ema_pytorch 0.8.3）；
- [x] 模型下载到 `tools/meanvc/models/`：
  - `fastu2++.pt` 91.9 MB ✅（TorchScript，`forward_encoder_chunk` 可用）
  - `meanvc_200ms.pt` 56.4 MB ✅（TorchScript DiT，输入 x/t/r/cache/cond/spks/prompts/offset/kv_cache）
  - `vocos.pt` 33.2 MB ✅（TorchScript，`decode` 可用）
  - `wavlm_large_finetune.pth` 1.30 GB ✅（zip 713 entries / 711 state_dict keys，来源 hf-mirror `hidoba/wavlm_large_finetune`）
- [x] `tools/meanvc/README.md` 创建；
- [x] `tools/meanvc/py/preprocess_ref.py` 创建；
- [x] `tools/meanvc/export_onnx.py` 创建（ONNX 导出遇到 TorchScript 限制，需要进一步调整）。

**关键决策**：
- WavLM 仅 PC 端预计算 spk_emb/prompt_mel（见 2.3.1），端上只需 3 个 ONNX；
- 后端统一 ORT（NNAPI EP + CPU EP 双路径），DiT 算子不支持时混合拆分（见 2.4）；
- ONNX 导出策略调整：因 TorchScript 模型无法直接 trace/export，改用 Python 模块类加载权重后导出。

**下一步**：
- [x] wavlm 下载完成（1.30 GB），zip 校验通过；
- [x] `preprocess_ref.py` 创建；
- [x] `export_onnx.py` 创建（V2：使用 Python 模块类而非 TorchScript 包装器）；
- [ ] 安装 funasr（infer_ref 依赖，用于 WER 评估）；
- [ ] 重新运行 infer_ref，获得 PyTorch 基准输出；
- [ ] 运行 ONNX 导出脚本（使用 Python 模块类方式）；
- [ ] 运行 ONNX 数值比对验证。