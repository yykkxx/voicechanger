# tools/route_receiver_test

Phase 1 路由注入验证的**独立测试接收端**（见 plan/02 第 10 节、plan/05 Phase 1）。

## 为什么需要它

不能拿商业语音 App 的听感作为验证依据——无法区分：

1. 数字域注入成功（本应用写入的 PCM 被 receiver 读到）；
2. 声学回录（扬声器播放后被麦克风拾取）；
3. 系统降噪/AGC 造成的假象。

测试 receiver 用**数字标记序列**消除歧义：注入端写入已知伪随机码，receiver 检查
收到的 PCM 与预期序列是否逐样本一致（或高相关），同时检查物理麦克风敲击是否
"不直接穿透"。

## 目标形式（待实现）

一个最小 Android 应用（可独立于主应用模块，或作为 `androidTest` 变体）：

- `AudioRecord` 以 `MIC` / `VOICE_COMMUNICATION` 打开；
- 统计：RMS、频谱峰值、与预期标记序列的匹配度；
- 结果显示 + 日志输出；
- 可选：录制 10 秒 PCM 到 `getExternalFilesDir` 供离线分析（仅测试用途）。

## 验证流程

1. 主应用（priv-app 部署后）注册 recorder-mix 或以虚拟设备方式注入 1 kHz + 伪随机标记；
2. 本 receiver 启动录音；
3. 判定：
   - 标记匹配度 > 阈值 → 数字注入成功；
   - 只有 1 kHz 能量、无标记 → 可能是声学路径或系统处理；
   - 无信号 → 注入未生效，检查策略注册与设备选择。

## 状态

- [ ] 待实现（Phase 1 随主应用一起真机验证时开发）。

> 备注：也可先以更轻量的方式在 Linux 环境用 `sox`/`ffmpeg` 生成标记波形，
> 与主应用注入端配合完成部分验证；但"目标应用 AudioRecord 能否读到"必须
> 在 Android 设备上用真实的 AudioRecord 验证。