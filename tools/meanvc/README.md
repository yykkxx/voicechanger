# tools/meanvc — MeanVC 移植工作目录

本目录承载 MeanVC（[ASLP-lab/MeanVC](https://github.com/ASLP-lab/MeanVC)）移植的**所有外围资产**：
模型权重、ONNX 转换、离线验证脚本、测试音频与报告。**不包含 App 源码改动**（App 侧见
`app/src/main/java/com/voicechanger/app/processing/meanvc/`，Phase D 才创建）。

## 目录结构

```text
tools/meanvc/
├── README.md               # 本文件
├── models/                 # 权重（大文件，不入库）
│   ├── fastu2++.pt             # 91.9 MB  ASR 流式编码器（TorchScript, forward_encoder_chunk）
│   ├── meanvc_200ms.pt         # 56.4 MB  DiT 声学模型（TorchScript, 2-step mean flow）
│   ├── vocos.pt                # 33.2 MB  Vocos 声码器（TorchScript, decode）
│   └── wavlm_large_finetune.pth # 1.30 GB ECAPA-TDNN+WavLM 说话人嵌入（仅 PC 端预计算用）
├── onnx/                   # Phase B 导出的 ONNX 模型
├── py/                     # 转换/验证/预计算脚本
│   ├── env_check.py
│   ├── preprocess_ref.py   # 参考音频 → spk_emb.npy + prompt_mel.npy（PC 端一次执行）
│   ├── export_onnx.py      # (Phase B) TorchScript → ONNX
│   └── verify_onnx.py      # (Phase B) ONNX 与 PyTorch 数值比对
├── rt_onnx.py              # (Phase C) onnxruntime 流式链路复刻
├── assets/                 # 测试音频与参考输出
└── notes/                  # 算子支持清单、数值报告
```

## 模型来源

| 文件 | 来源 | 校验 |
|---|---|---|
| fastu2++.pt | HF `ASLP-lab/MeanVC` (hf-mirror) | TorchScript load OK |
| meanvc_200ms.pt | HF `ASLP-lab/MeanVC` (hf-mirror) | TorchScript load OK |
| vocos.pt | HF `ASLP-lab/MeanVC` (hf-mirror) | TorchScript load OK |
| wavlm_large_finetune.pth | hf-mirror `hidoba/wavlm_large_finetune`（官方 Google Drive 原链 quota exceeded） | zip 713 entries / 711 keys OK |

## 环境

Py3.12 venv：`~/venv-meanvc`（torch 2.5.1 / torchaudio 2.5.1 / onnx 1.23.0 / onnxruntime 1.30.0 / numpy 2.5.3 + librosa/fairseq/s3prl）

## 参考音频预计算（PC 端）

MeanVC 实时推理里，目标说话人的 `spk_emb` 与 `prompt_mel` 只计算一次（见 2.3.1 决策）。
端上只带 `spk_emb.bin` + `prompt_mel.bin` 即可，无需部署 WavLM。

```bash
~/venv-meanvc/bin/python py/preprocess_ref.py \
  --wav   assets/ref.wav \
  --out   assets/ref.npz
```

## 目标：Android 端 3 个 ONNX

1. `fastu2.onnx`  — ASR chunk 编码（fbank [1,23,80] + offset + att_cache + cnn_cache → bn [1,5,256] + caches）
2. `meanvc.onnx` — DiT 单步 velocity（x[1,20,80] + t + r + cache + cond[1,20,256] + spks[1,256] + prompts[1,T,80] + kv_cache → u[1,20,80] + kv_cache'）
3. `vocos.onnx`  — mel → wav（[1,80,T] → [1,T*256]）

推理后端优先级：NPU(NNAPI EP) → GPU(备选) → CPU(兜底)；统一 ORT。