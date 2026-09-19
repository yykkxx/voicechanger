# MeanVC 移植状态总结（供后续会话参考）

## 当前状态（2026-09-19 会话 #7 开始）

### Phase A: ✅ 完成
- 四个模型权重全部下载并验证：
  - `fastu2++.pt` 91.9 MB（TorchScript，ASR 编码器）
  - `meanvc_200ms.pt` 56.4 MB（TorchScript，DiT 声学模型）
  - `vocos.pt` 33.2 MB（TorchScript，Vocos 声码器）
  - `wavlm_large_finetune.pth` 1.30 GB（仅 PC 端预计算使用）

### Phase B: ⚠️ 部分完成（ONNX 导出遇到问题）
- `export_onnx.py` 已创建，但遇到 TorchScript 导出限制
- **核心问题**：`torch.onnx.export` 无法直接追踪 TorchScript 模型的内部方法（如 `forward_encoder_chunk`、`decode`）
- **已尝试方案**：
  1. 使用包装器类（失败：`Method 'forward' is not defined`）
  2. 使用 `dynamo=True`（失败：缺少 `onnxscript`）
  3. 安装 `onnxscript` 后重试（正在执行中）
- **替代方案**（如果上述失败）：
  - 使用 `torch.jit.trace` 直接 trace
  - 将模型转换为 ONNX 的 subgraph（仅导出 forward 部分）
  - 使用 `torch.onnx.symbolic_registry` 注册自定义 op

### 已创建文件
```
tools/meanvc/
├── README.md                    # 项目说明
├── models/                      # 模型权重目录
│   ├── fastu2++.pt              # ✅ 91.9 MB
│   ├── meanvc_200ms.pt          # ✅ 56.4 MB
│   ├── vocos.pt                 # ✅ 33.2 MB
│   └── wavlm_large_finetune.pth # ✅ 1.30 GB
├── py/
│   ├── preprocess_ref.py        # ✅ 参考音频预处理脚本
│   └── export_onnx.py           # ⚠️ ONNX 导出脚本（需要修复）
└── assets/
    ├── ref.wav                  # 参考音频
    └── outputs/                 # 输出目录（当前为空）
```

### 环境状态
- venv: `~/venv-meanvc`（torch 2.5.1 / onnx 1.23.0 / onnxruntime 1.30.0 / numpy 2.5.3 / matplotlib 3.11.2 / fairseq 0.12.2 / s3prl 0.4.1 / accelerate 1.15.0 / onnxscript 0.7.2）
- 已修复 `src/eval/run_wer.py` 的 jiwer 兼容性问题（添加 try/except 降级）

### 正在运行的任务
- infer_ref8（pid=14660）：正在运行，等待 WavLM 加载完成
- export_onnx：可能已超时或正在后台运行

---

## 下一步行动

### 优先级 1：完成 ONNX 导出（Phase B）
```bash
# 方案 1：直接使用 torch.onnx.export 的 symbolic 模式
cd tools/meanvc && ~/venv-meanvc/bin/python export_onnx.py

# 方案 2：如果失败，手动 trace 每个模型的关键方法
# fastu2: trace forward_encoder_chunk
# meanvc: trace forward
# vocos: trace decode
```

### 优先级 2：验证 PyTorch 推理基准
- 等待 infer_ref8 完成
- 检查输出 wav 是否符合预期（音色转换正确）

### 优先级 3：数值比对验证
- 导出 ONNX 后，用 onnxruntime 运行同一输入
- 与 PyTorch 输出做逐元素比对（误差 < 1e-4）

---

## 技术细节备忘

### TorchScript 模型结构
```python
# fastu2++.pt
model.forward_encoder_chunk(xs, offset, required_cache_size, att_cache, cnn_cache)
# 输入：fbank chunk [1, 23, 80] + 状态缓存
# 输出：encoder_output [1, 5, 256] + att_cache_out + cnn_cache_out

# meanvc_200ms.pt (DiT)
model(x, t, r, cache=cache, cond=cond, spks=spks, prompts=prompts, offset=offset, is_inference=is_inference, kv_cache=kv_cache)
# 输入：noise + 时序 + 条件
# 输出：velocity + kv_cache_out

# vocos.pt
model.decode(mel)
# 输入：mel [1, 80, T]
# 输出：wav [1, 16000*T/220]
```

### Chunk 参数（来自 run_rt.py）
```python
ASR: decoding_chunk_size=5, left_chunks=2, subsampling=4, context=7, stride=20, CHUNK=3200 samples
DiT: vc_chunk=20, steps=2, kv_cache_max_len=100
Vocos: overlap=3, wav_overlap=320 samples
```

---

## 文档位置
- 主计划：`plan/10-MeanVC移植计划.md`
- 总体架构：`plan/01-总体架构.md`
- 处理引擎协议：`plan/03-处理引擎与本地端口协议.md`
- 实施进度：`plan/06-实施进度.md`