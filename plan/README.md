# Android 实时变声器：架构规划

> 状态：v0.2，**核心代码已实现并通过构建与单元测试**（详见 [06-实施进度.md](./06-实施进度.md)）。  
> 规划依据：现有 Compose 模板项目，以及 [语音路由.md](./语音路由.md) 中记录的 ColorOS 虚拟音频设备、AOSP `AudioPolicy/AudioMix`、priv-app 与 Magisk 部署信息。

## 0. 当前项目状态（2026-09-19）

| 产物 | 位置 |
|---|---|
| 应用代码 | `app/src/main/java/com/voicechanger/app/`（领域/缓冲/协议/处理/采集/注入/流水线/路由/服务/UI） |
| 单元测试（27 个，全绿） | `app/src/test/java/com/voicechanger/app/` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`（构建命令 `./gradlew assembleDebug`） |
| Magisk 模块模板 | `tools/magisk_module/voicechanger_priv/`（含 privapp-permissions 白名单） |
| 部署清单 | [07-Magisk部署清单.md](./07-Magisk部署清单.md) |

关键事实（已经从真机文档与 SDK 验证）：

- `android.media.audiopolicy.*` **不在** compileSdk 35 公开 jar 中 → 应用侧全部**反射**调用；
- 应用须经 Magisk 挂载到 `/system/priv-app/` 才能获得 `MODIFY_AUDIO_ROUTING` 等特权权限；
- proot ARM64 构建环境需要 `tools/patch_aapt2.sh` 修复 aapt2。

## 1. 产品目标

构建一个在 Android 上持续运行的实时变声器：

1. 从物理麦克风或系统允许的输入端采集语音；
2. 在低延迟流水线中处理 PCM；
3. 将处理后的声音送入目标语音应用可读取的输入路由；
4. 提供两种互斥的处理模式：
   - **内部处理**：前台服务内运行本地 DSP；
   - **本地端口处理**：把 PCM 发往 `127.0.0.1` 上的处理程序，再接收处理后的 PCM；
5. 支持能力检测、路由诊断、实时指标和可靠清理。

## 2. 最重要的技术边界

普通 Android 应用不能把自己的 `AudioTrack` 透明地伪装成其他应用的麦克风。该项目的“回注到目标应用”依赖：

- 应用确实以 `priv-app` 运行；
- `MODIFY_AUDIO_ROUTING` 等权限确实被系统授予；
- 目标 ROM 支持可用的 recorder-mix 注入或厂商虚拟音频设备路由；
- SELinux 和 framework 没有额外阻断。

原始路由文档证明了可尝试的基础设施，但**没有单独证明 `AudioTrack.preferredDevice` 就能成为任意第三方应用的麦克风输入**。此外，文档中的 `USAGE_GAME + ROUTE_FLAG_LOOP_BACK` 示例捕获的是播放音频，不等于麦克风替换。实施时必须先完成“注入验证里程碑”，再投入完整 DSP 和 UI。

## 3. 已确定的架构决策

| 项目 | 当前决策 |
|---|---|
| 服务模型 | 单一 `VoiceChangerService` 持有路由和音频资源，运行时必须为前台服务 |
| 处理模式 | `InternalProcessor` 与 `LoopbackSocketProcessor` 实现同一处理接口，可停流切换 |
| 内部标准格式 | PCM signed 16-bit little-endian、48 kHz、单声道、10 ms/帧 |
| 路由格式适配 | 在边界完成重采样及 mono/stereo 转换；厂商虚拟设备预计要求 48 kHz stereo |
| 外部传输 | v1 使用 `127.0.0.1` TCP 全双工二进制帧；不逐帧请求-响应阻塞 |
| 实时策略 | 预分配缓冲、有限 SPSC 队列、音频线程不做 UI/磁盘/大日志操作 |
| 故障默认行为 | 外部处理器超时或断开时输出静音并提示；可由用户显式改为原声旁路 |
| 路由后端 | recorder `AudioPolicy/AudioMix`、ColorOS 虚拟设备、remote-submix 适配器、仅监听调试后端 |
| 最低实现顺序 | 能力探测 → 纯透传回注 → 前台服务 → 内部 DSP → 本地端口 → 完整 UI |
| 项目组织 | 第一阶段保持单 `:app` 模块、按边界分包；路由跑通后再按需拆 Gradle 模块 |

## 4. 目标链路

```text
物理麦克风
    │
    ▼
CaptureEndpoint (AudioRecord)
    │  PCM 48k/mono/S16LE/10ms
    ▼
有界输入环形队列
    │
    ├── InternalProcessor ─────────────┐
    │                                  │
    └── LoopbackSocketProcessor        │
          127.0.0.1:port               │
                                       ▼
                              有界输出/抖动队列
                                       │
                                       ▼
                         InjectionEndpoint + RouteBackend
                                       │
                                       ▼
                              目标应用 AudioRecord
```

“监听自己的变声结果”是独立可选支路，只允许路由到耳机等安全设备，不能与目标注入路径混为一谈，以免扬声器回授。

## 5. 文档索引

1. [01-总体架构.md](./01-总体架构.md)：组件、线程、状态机、代码边界和目录建议。
2. [02-音频路由设计.md](./02-音频路由设计.md)：路由后端、启动顺序、格式协商及关键可行性验证。
3. [03-处理引擎与本地端口协议.md](./03-处理引擎与本地端口协议.md)：内部 DSP、实时约束和 TCP 协议 v1。
4. [04-权限部署与安全.md](./04-权限部署与安全.md)：Manifest、priv-app、Magisk、SELinux 与隐私边界。
5. [05-实施路线图与验收.md](./05-实施路线图与验收.md)：分阶段任务、测试矩阵、验收指标和风险。
6. [06-实施进度.md](./06-实施进度.md)：**进度追踪（已做/未做/将做）与构建验证记录**。
7. [07-Magisk部署清单.md](./07-Magisk部署清单.md)：priv-app 部署坐标、模块结构、验证清单。
8. [语音路由.md](./语音路由.md)：ColorOS 真机逆向整理的底层路由技术文档（原始输入）。

## 6. 实施前需要确认但不阻塞架构的事项

- 首要支持的手机型号、ColorOS/Android 版本；
- 首要目标语音应用及其包名；
- root/Magisk 是否是明确前提；
- 是否已有本地处理程序及既有端口协议；若没有，采用本文定义的 v1；
- 第一版需要的变声效果：仅音调/共振峰，还是还需降噪、机器人声、混响；
- 外部处理故障时，用户更偏好“静音保护”还是“原声不断话”。

## 7. 非目标（首版）

- 不承诺无 root、普通应用安装即可给第三方应用替换麦克风；
- 不在首版支持公网音频传输；
- 不在路由验证前引入大型 AI 声线转换模型；
- 不录制或持久化用户 PCM；
- 不尝试调用或绕过厂商 `MagicVoiceService` 的私有验证链。
