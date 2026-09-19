#!/usr/bin/env python3
"""
MeanVC TorchScript -> ONNX 导出（V2）

关键点：
- 直接对已是 TorchScript 的 .pt 模型编写 **scripted wrapper**，
  `offset: int` 等标量以真实图输入形式保留（trace 会退化成常量，故不用 trace）。
- ASR：动态 offset + 稳态零长 cache（att[7,4,0,128] / cnn[7,1,256,0]，与 zeros(0,0,0,0) 等价，已验证）。
- DiT：分 3 个变体（kv_cache=None / [None]*4 / 真实 kv），规避 ONNX 无法表达 `Optional[List[Optional[Tuple]]]`。
- Vocos：mel[1,80,T] -> wav[1,(T-1)*160]。

输出：tools/meanvc/onnx/*.onnx
"""
import os
import torch
import torch.nn as nn
from torch import Tensor
from pathlib import Path
from typing import List, Optional, Tuple

WORKSPACE = Path("/data/user/0/com.ai.assistance.operit/files/workspace/d0a7439b-c97a-4614-9d35-174e80f10cf2")
MODELS = WORKSPACE / "tools" / "meanvc" / "models"
OUT = WORKSPACE / "tools" / "meanvc" / "onnx"
OUT.mkdir(parents=True, exist_ok=True)

REQ_CACHE = 40
N_BLOCKS = 4          # DiT transformer_blocks 数（实测 kv_cache list 长度）
KV_HEADS = 2
KV_DIM = 64

torch.set_num_threads(1)


def _sym_isinstance(g, *inputs, **kwargs):
    """TorchScript `isinstance` 无法直接导出；MeanVC 的 offset 恒以 int 传入，
    故 `isinstance(offset, Tensor)` 恒为 False。"""
    return g.op("Constant", value_t=torch.tensor(False))


torch.onnx.register_custom_op_symbolic("prim::isinstance", _sym_isinstance, 17)


# ---------------- ASR ----------------
class AsrWrap(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.asr = m

    def forward(self, xs: Tensor, offset: int, att_cache: Tensor, cnn_cache: Tensor):
        return self.asr.forward_encoder_chunk(xs, offset, 40, att_cache, cnn_cache)


# ---------------- DiT ----------------
class DitFirst(nn.Module):
    """第一块：kv_cache=None, cache=None, offset=0"""
    def __init__(self, m):
        super().__init__()
        self.vc = m

    def forward(self, x: Tensor, t: Tensor, r: Tensor, cond: Tensor, spks: Tensor, prompts: Tensor):
        u, _kv = self.vc(x, t, r, cond, spks, prompts, None, 0, None)
        return u


class DitStepNoneKv(nn.Module):
    """第二块：kv_cache=[None]*N（返回真实 kv）"""
    def __init__(self, m):
        super().__init__()
        self.vc = m

    def forward(self, x: Tensor, t: Tensor, r: Tensor, cond: Tensor, spks: Tensor,
                prompts: Tensor, cache: Tensor, offset: int):
        kv = [None, None, None, None]
        u, nk = self.vc(x, t, r, cond, spks, prompts, cache, offset, kv)
        k = torch.stack([nk[i][0] for i in range(4)], dim=0)
        v = torch.stack([nk[i][1] for i in range(4)], dim=0)
        return u, k, v


class DitStepKv(nn.Module):
    """稳态：kv_cache 由叠加张量还原为 [(k,v)]*N"""
    def __init__(self, m):
        super().__init__()
        self.vc = m

    def forward(self, x: Tensor, t: Tensor, r: Tensor, cond: Tensor, spks: Tensor,
                prompts: Tensor, cache: Tensor, offset: int, kv_k: Tensor, kv_v: Tensor):
        kv: List[Tuple[Tensor, Tensor]] = []
        for i in range(4):
            kv.append((kv_k[i], kv_v[i]))
        u, nk = self.vc(x, t, r, cond, spks, prompts, cache, offset, kv)
        k = torch.stack([nk[i][0] for i in range(4)], dim=0)
        v = torch.stack([nk[i][1] for i in range(4)], dim=0)
        return u, k, v


# ---------------- Vocos ----------------
class VocosWrap(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.voc = m

    def forward(self, mel: Tensor):
        return self.voc.decode(mel)


def export(mod, args, path, input_names, output_names, dynamic_axes):
    torch.onnx.export(
        mod, args, str(path),
        input_names=input_names, output_names=output_names,
        dynamic_axes=dynamic_axes, opset_version=17,
        do_constant_folding=False,
    )
    print(f"  ✓ {path.name}  {os.path.getsize(path)/1e6:.1f} MB")


def main():
    print("=" * 66)
    # ---- ASR ----
    print("[1/5] asr.onnx")
    m = torch.jit.load(str(MODELS / "fastu2++.pt"), map_location="cpu").eval()
    w = torch.jit.script(AsrWrap(m))
    xs = torch.randn(1, 23, 80)
    att = torch.zeros(7, 4, 5, 128)
    cnn = torch.zeros(7, 1, 256, 8)
    export(w, (xs, 5, att, cnn), OUT / "asr.onnx",
           ["xs", "offset", "att_cache", "cnn_cache"],
           ["encoder_output", "att_cache_out", "cnn_cache_out"],
           {"xs": {1: "seq"}, "att_cache": {2: "cache_seq"}, "cnn_cache": {3: "wav_cache_seq"},
            "encoder_output": {1: "out_len"}})
    del m, w

    # ---- DiT ----
    v = torch.jit.load(str(MODELS / "meanvc_200ms.pt"), map_location="cpu").eval()
    x = torch.randn(1, 20, 80)
    cond = torch.randn(1, 20, 256)
    spks = torch.randn(1, 256)
    prompts = torch.randn(1, 50, 80)
    t = torch.full((1,), 1.0)
    r = torch.full((1,), 0.8)
    cache = torch.randn(1, 20, 80)
    kvk = torch.randn(N_BLOCKS, 1, KV_HEADS, 20, KV_DIM)
    kvv = torch.randn(N_BLOCKS, 1, KV_HEADS, 20, KV_DIM)

    print("[2/5] meanvc_first.onnx")
    wf = torch.jit.script(DitFirst(v))
    export(wf, (x, t, r, cond, spks, prompts), OUT / "meanvc_first.onnx",
           ["x", "t", "r", "cond", "spks", "prompts"], ["u"],
           {"cond": {1: "seq"}, "prompts": {1: "prompt_len"}, "u": {1: "seq"}})

    print("[3/5] meanvc_step1.onnx")
    w1 = torch.jit.script(DitStepNoneKv(v))
    export(w1, (x, t, r, cond, spks, prompts, cache, 0), OUT / "meanvc_step1.onnx",
           ["x", "t", "r", "cond", "spks", "prompts", "cache", "offset"], ["u", "kv_k", "kv_v"],
           {"cond": {1: "seq"}, "prompts": {1: "prompt_len"}, "cache": {1: "cache_seq"},
            "u": {1: "seq"}, "kv_k": {3: "kv_seq"}, "kv_v": {3: "kv_seq"}})

    print("[4/5] meanvc_step.onnx")
    w2 = torch.jit.script(DitStepKv(v))
    export(w2, (x, t, r, cond, spks, prompts, cache, 0, kvk, kvv), OUT / "meanvc_step.onnx",
           ["x", "t", "r", "cond", "spks", "prompts", "cache", "offset", "kv_k", "kv_v"],
           ["u", "kv_k_out", "kv_v_out"],
           {"cond": {1: "seq"}, "prompts": {1: "prompt_len"}, "cache": {1: "cache_seq"},
            "u": {1: "seq"}, "kv_k": {3: "kv_seq"}, "kv_v": {3: "kv_seq"},
            "kv_k_out": {3: "kv_seq_out"}, "kv_v_out": {3: "kv_seq_out"}})
    del v, wf, w1, w2

    # ---- Vocos ----
    print("[5/5] vocos.onnx")
    vo = torch.jit.load(str(MODELS / "vocos.pt"), map_location="cpu").eval()
    wv = torch.jit.script(VocosWrap(vo))
    mel = torch.randn(1, 80, 20)
    export(wv, (mel,), OUT / "vocos.onnx", ["mel"], ["wav"],
           {"mel": {2: "frames"}, "wav": {1: "samples"}})

    print("=" * 66)
    for f in sorted(OUT.glob("*.onnx")):
        print(f"  - {f.name} ({f.stat().st_size/1e6:.1f} MB)")
    print("=" * 66)


if __name__ == "__main__":
    main()