# VoiceChanger

Android 实时变声器应用，支持 DSP 数字信号处理和 RVC AI 语音转换。

## ✨ 功能特性

### 13 种音色预设

| 预设 | 说明 |
|------|------|
| 原声 | 直通不做处理 |
| 女声 | 男→女变调 +4 半音，共振峰搬移，临场感提升 |
| 男声 | 女→男变调 -4 半音 |
| 儿童 | +8 半音，明亮音色 |
| 怪兽 | -8 半音 + 失真 + 低音增强 |
| 机器人 | 环形调制，方波载波 |
| 空灵 | +5 半音 + 回声 + 合唱 |
| 歌者 | +4 半音 + 合唱 + 颤音 |
| 电话音 | 带通滤波 + 软饱和 |
| 浑厚低音 | -6 半音 + 低音增强 + 回声 |
| 天使 | +8 半音 + 合唱 + 回声 + 临场感 |
| 电台主播 | 电话音 + 临场感 + 低音衰减 |
| 自定义 | 用户自由调节参数 |

### DSP 效果链

实时音频处理流水线（零内存分配，线程安全）：

```
变调 → EQ倾斜 → 低音增强 → 临场感 → 环形调制 → 失真 → 电话音 → 回声 → 合唱 → 镶边 → 颤音 → 软限幅
```

| 效果 | 原理 |
|------|------|
| SmbPitchShifter | FFT 域频谱搬移，支持独立音高/共振峰比 |
| TiltEq | 一阶倾斜 EQ，高频亮度调整 |
| BassBoost | 一阶低通分频 + 低频增益 |
| Presence | 二阶峰值 EQ (3kHz, Q=1.2) |
| RingModulator | 环形调制（载波 50Hz，可调方波/正弦） |
| Distortion | 软饱和失真 (tanh 曲线) |
| Telephone | Chamberlin SVF 带通 (1.2kHz, Q=0.9) |
| Echo | 反馈延迟线 (250ms, 35% 反馈) |
| Chorus | 3 条延迟线 + 独立 LFO |
| Flanger | 短延迟 (1-12ms) + 正弦扫频 |
| Tremolo | 5.5Hz 幅度调制 |
| FloatSoftClip | 输出软限幅 |

### RVC AI 语音转换

基于 ONNX Runtime 的 RVC (Retrieval-based Voice Conversion) 引擎：

- **HuBERT**: 语音特征提取 (377MB)
- **RMVPE**: F0 基频估计 (362MB)
- **Net_G**: 语音合成解码器 (115MB)

> ⚠️ RVC 模型文件不包含在仓库中，需单独部署。

### 系统级音频路由

通过 Magisk 模块将应用安装为系统特权应用，获取：
- `CAPTURE_AUDIO_OUTPUT` - 捕获系统音频输出
- `MODIFY_AUDIO_ROUTING` - 修改音频路由
- `MODIFY_AUDIO_SETTINGS` - 修改音频设置

## 📁 项目结构

```
├── app/src/main/java/com/voicechanger/app/
│   ├── audio/              # 音频捕获、注入、策略路由
│   ├── core/
│   │   ├── audio/          # PCM 工具、重采样、降噪、环形缓冲
│   │   └── protocol/       # 本地端口协议 (VCP)
│   ├── domain/             # 数据模型、参数、预设
│   ├── overlay/            # 悬浮窗控制器
│   ├── processing/
│   │   ├── dsp/            # DSP 算法 (FFT, 变调, 效果器)
│   │   ├── female/         # 男→女专用引擎 (F0 平滑)
│   │   └── rvc/            # RVC ONNX 引擎
│   ├── route/              # 音频路由探测与后端
│   ├── service/            # 前台服务
│   └── ui/                 # Jetpack Compose 界面
├── plan/                   # 设计文档
├── tools/                  # 构建工具、MeanVC 导出脚本
└── gradle/                 # Gradle 配置
```

## 🛠️ 构建

```bash
# ARM64 环境需要先替换 aapt2
./setup_android_env.sh

# 构建 Debug APK
./gradlew assembleDebug --offline

# APK 输出
app/build/outputs/apk/debug/app-debug.apk
```

### 要求

- JDK 17+
- Android SDK (API 35)
- Kotlin 2.0+
- ARM64 环境：需 ReVanced aapt2 替换

## 📋 版本历史

### v1.3
- 新增 6 个 DSP 效果器 (Chorus, Flanger, Tremolo, Telephone, BassBoost, Presence)
- 新增 5 个音色预设 (歌者/电话音/浑厚低音/天使/电台主播)
- 强化男→女引擎：F0 时间平滑、临场感提升、输出软饱和
- RVC ONNX 模型集成
- UI 3 行预设布局

### v1.2
- 基础变声功能 (女声/男声/儿童/怪兽/机器人/空灵)
- SmbPitchShifter 变调引擎
- Magisk 特权模块部署
- 前台通知 + 悬浮窗

## 📄 License

MIT
