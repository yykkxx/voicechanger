# models/ — MeanVC 推理模型与参考音色

本目录由 App 在进入「AI 声线转换（MeanVC）」模式时自动读取，并拷贝到应用私有目录
`/data/user/0/com.voicechanger.app/files/models/meanvc/`。

## 需要的文件

| 文件 | 内容 | 状态 |
|---|---|---|
| `asr.onnx` | Wenet U2++ 流式 ASR 编码器（fbank → BN 特征） | ✅ 已提供（86.5 MB） |
| `meanvc.onnx` | MeanVC DiT 声学模型（2 步 mean-flow，含 kv cache） | ⬜ 待补 |
| `vocos.onnx` | Vocos 声码器（mel → 16 kHz wav） | ⬜ 待补 |
| `voice.bin` | 目标说话人参考音色（spk_emb + prompt_mel） | ⬜ 待补 |

> 任一文件缺失时，AI 模式**不会崩溃**：`MeanCvProcessor` 自动退化为直通，并在日志中输出
> `VC/MeanCv: 模型未安装（…）`。

## 生成方式

```bash
cd tools/meanvc
~/venv-meanvc/bin/python export_onnx_v2.py                      # 导出 ONNX（asr 已可导出）
~/venv-meanvc/bin/python py/preprocess_ref.py --wav assets/ref.wav --out assets/ref.npz
~/venv-meanvc/bin/python py/make_voice_bin.py --npz assets/ref.npz --out assets/voice.bin
```

`voice.bin` 二进制格式（小端）：

```
"MEANVC01"            8 bytes
int32 spkDim  = 256
int32 melBins = 80
int32 melFrames (T)
float32[256]          spk_emb
float32[T*80]         prompt_mel（行优先）
```

## 部署

```bash
cp tools/meanvc/onnx/*.onnx      /data/adb/modules/voicechanger_priv/models/
cp tools/meanvc/assets/voice.bin /data/adb/modules/voicechanger_priv/models/
```

模型体积较大，`pack.sh` 打包模块 zip 时会自动排除本目录（`-x 'models/*'`），请单独推送。

## 后端优先级

App 使用 ONNX Runtime，执行提供器按 **NPU → GPU → CPU** 顺序尝试：

1. `NPU`  — NNAPI EP（FP16）
2. `GPU`  — NNAPI EP（FP16 + CPU_DISABLED，强制加速器）
3. `CPU`  — XNNPACK

实际命中的后端见 `logcat -s VC/MeanCvEP VC/MeanCv`，UI 状态由 `CurrentParams.meanCvBackend` 提供。
