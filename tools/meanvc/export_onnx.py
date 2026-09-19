#!/usr/bin/env python3
"""
ONNX 转换脚本：将 MeanVC 的 PyTorch 模型转换为 ONNX。

策略：
1. 不使用 TorchScript 导出，而是直接加载源码中的 Python 类
2. 加载预训练权重到 Python 类中
3. 使用 torch.onnx.export 导出为 ONNX

模型：
- fastu2: ASR 编码器 (Wenet U2++)
- meanvc: DiT 声学模型
- vocos: Vocos 声码器

注意：需要 meanvc 源码和配置文件。
"""

import os
import sys
import json
import torch
from pathlib import Path

# 设置路径
WORKSPACE = Path("/data/user/0/com.ai.assistance.operit/files/workspace/d0a7439b-c97a-4614-9d35-174e80f10cf2")
MEANVC_SRC = WORKSPACE / "tools" / "MeanVC-src"
MODELS_DIR = WORKSPACE / "tools" / "meanvc" / "models"
OUTPUT_DIR = WORKSPACE / "tools" / "meanvc" / "assets" / "outputs"

# 添加源码路径
sys.path.insert(0, str(MEANVC_SRC))
sys.path.insert(0, str(MEANVC_SRC / "src"))
sys.path.insert(0, str(MEANVC_SRC / "src" / "infer"))
sys.path.insert(0, str(MEANVC_SRC / "src" / "model"))

# 导入模型类
try:
    from model.dit_kvcache import DiT
    print("✓ DiT imported")
except ImportError as e:
    print(f"✗ Cannot import DiT: {e}")
    DiT = None

try:
    from model.asr_model import ASRModel
    print("✓ ASRModel imported")
except ImportError as e:
    print(f"✗ Cannot import ASRModel: {e}")
    ASRModel = None

try:
    from vocos.pretrained import Vocos
    print("✓ Vocos imported")
except ImportError as e:
    print(f"✗ Cannot import Vocos: {e}")
    Vocos = None

def load_checkpoint(model, ckpt_path):
    """加载检查点到模型"""
    if not os.path.exists(ckpt_path):
        raise FileNotFoundError(f"Checkpoint not found: {ckpt_path}")
    
    state_dict = torch.load(ckpt_path, map_location='cpu', weights_only=True)
    
    # 处理不同的检查点格式
    if isinstance(state_dict, dict):
        if 'state_dict' in state_dict:
            state_dict = state_dict['state_dict']
        elif 'model' in state_dict:
            state_dict = state_dict['model']
    
    # 加载权重
    model.load_state_dict(state_dict, strict=False)
    print(f"  ✓ Loaded checkpoint from {ckpt_path}")
    return model

def convert_fastu2():
    """转换 fastu2 onnx"""
    print("\n[fastu2] Converting...")
    
    # 需要配置文件
    config_path = MEANVC_SRC / "src" / "config" / "config_200ms.json"
    if not config_path.exists():
        print(f"  ✗ Config not found: {config_path}")
        return None
    
    with open(config_path) as f:
        config = json.load(f)
    
    # 创建模型
    model = ASRModel(**config["model"])
    model.eval()
    
    # 加载权重
    ckpt_path = MODELS_DIR / "fastu2++.pt"
    load_checkpoint(model, ckpt_path)
    
    # 创建示例输入
    batch_size = 1
    seq_len = 23
    n_mels = 80
    
    xs = torch.randn(batch_size, seq_len, n_mels)
    offset = torch.tensor([0])
    required_cache_size = torch.tensor([40])
    att_cache = torch.randn(batch_size, 12, 4, 0, 256)
    cnn_cache = torch.randn(batch_size, 12, 0, 256)
    
    print(f"  Input: xs={xs.shape}")
    
    # 导出
    output_path = OUTPUT_DIR / "fastu2.onnx"
    torch.onnx.export(
        model,
        (xs, offset, required_cache_size, att_cache, cnn_cache),
        output_path,
        input_names=["xs", "offset", "required_cache_size", "att_cache", "cnn_cache"],
        output_names=["encoder_output", "att_cache_out", "cnn_cache_out"],
        dynamic_axes={
            "xs": {1: "seq_len"},
            "att_cache": {3: "att_seq_len"},
            "cnn_cache": {2: "cnn_seq_len"},
            "encoder_output": {1: "out_len"}
        },
        opset_version=17
    )
    
    size_mb = os.path.getsize(output_path) / 1e6
    print(f"  ✓ Saved: {output_path} ({size_mb:.1f} MB)")
    return output_path

def convert_meanvc():
    """转换 meanvc onnx"""
    print("\n[meanvc] Converting...")
    
    if DiT is None:
        print("  ✗ DiT class not available")
        return None
    
    # 加载配置
    config_path = MEANVC_SRC / "src" / "config" / "config_200ms.json"
    with open(config_path) as f:
        config = json.load(f)
    
    # 创建模型
    model = DiT(**config["model"])
    model.eval()
    
    # 加载权重
    ckpt_path = MODELS_DIR / "meanvc_200ms.pt"
    load_checkpoint(model, ckpt_path)
    
    # 创建示例输入
    batch_size = 1
    seq_len = 20
    mel_dim = 80
    bn_dim = 256
    prompt_len = 50
    
    x = torch.randn(batch_size, seq_len, mel_dim)
    t = torch.tensor([1.0])
    r = torch.tensor([0.8])
    cache = torch.randn(batch_size, seq_len, mel_dim)
    cond = torch.randn(batch_size, seq_len, bn_dim)
    spks = torch.randn(batch_size, bn_dim)
    prompts = torch.randn(batch_size, prompt_len, mel_dim)
    offset = torch.tensor([0])
    mask = None
    
    print(f"  Input: x={x.shape}, cond={cond.shape}, spks={spks.shape}")
    
    # 导出
    output_path = OUTPUT_DIR / "meanvc.onnx"
    torch.onnx.export(
        model,
        (x, t, r, cache, cond, spks, prompts, offset, mask),
        output_path,
        input_names=["x", "t", "r", "cache", "cond", "spks", "prompts", "offset", "mask"],
        output_names=["output"],
        dynamic_axes={
            "x": {1: "seq_len"},
            "cond": {1: "cond_len"},
            "prompts": {1: "prompt_len"},
            "output": {1: "out_len"}
        },
        opset_version=17
    )
    
    size_mb = os.path.getsize(output_path) / 1e6
    print(f"  ✓ Saved: {output_path} ({size_mb:.1f} MB)")
    return output_path

def convert_vocos():
    """转换 vocos onnx"""
    print("\n[vocos] Converting...")
    
    if Vocos is None:
        print("  ✗ Vocos class not available")
        return None
    
    # Vocos 可以直接加载
    ckpt_path = MODELS_DIR / "vocos.pt"
    
    # Vocos 是一个封装模型，需要特殊处理
    # 这里我们直接使用 torch.jit.load 的 wrapper
    # 因为 Vocos 的导出比较复杂
    
    print("  Note: Vocos uses custom decode method, need special handling")
    print("  Skipping Vocos export for now, will use TorchScript directly")
    return None

def main():
    print("=" * 60)
    print("ONNX 转换 - MeanVC")
    print("=" * 60)
    
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    
    results = {}
    
    # 转换 fastu2
    try:
        results["fastu2"] = convert_fastu2()
    except Exception as e:
        print(f"  ✗ Error: {e}")
        import traceback
        traceback.print_exc()
        results["fastu2"] = None
    
    # 转换 meanvc
    try:
        results["meanvc"] = convert_meanvc()
    except Exception as e:
        print(f"  ✗ Error: {e}")
        import traceback
        traceback.print_exc()
        results["meanvc"] = None
    
    # 转换 vocos（跳过）
    results["vocos"] = None
    
    # 结果汇总
    print("\n" + "=" * 60)
    print("结果:")
    for name, path in results.items():
        if path:
            print(f"  {name}.onnx ✓ ({os.path.getsize(path)/1e6:.1f} MB)")
        else:
            print(f"  {name}.onnx ✗")
    
    print("\n输出目录:")
    for f in OUTPUT_DIR.glob("*.onnx"):
        print(f"  - {f.name} ({f.stat().st_size/1e6:.1f} MB)")
    print("=" * 60)

if __name__ == "__main__":
    main()