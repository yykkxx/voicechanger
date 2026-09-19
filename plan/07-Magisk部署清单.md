# 07 Magisk priv-app 部署清单

> 依据：`plan/语音路由.md`（ColorOS 真机逆向与系统探测结论）。
> 结论：路由能力位于 framework 层，由标准权限守护；应用通过 Magisk 挂载到 `priv-app` 获得 `signature|privileged` 权限即可使用标准 AOSP 路由 API，无需厂商签名。
> 本文是"确保应用在 priv-app 下正常运行"的部署与验证执行清单。

## 1. 部署坐标（已锁定）

| 项目 | 值 |
|---|---|
| applicationId | `com.voicechanger.app` |
| APK 名称 | `VoiceChanger.apk`（debug 输出现名 `app-debug.apk`，打包时改名） |
| 挂载路径 | `/system/priv-app/VoiceChanger/VoiceChanger.apk` |
| 白名单文件 | `/system/etc/permissions/privapp-permissions-voicechanger.xml` |
| Magisk 模块 id | `voicechanger_priv` |
| 目标权限 | `MODIFY_AUDIO_ROUTING`、`CAPTURE_AUDIO_OUTPUT`、`MODIFY_AUDIO_SETTINGS` |

> ⚠️ 白名单 XML 中的 `package` 必须与 APK 内 `applicationId` **逐字节一致**，否则系统不会授予特权权限。

## 2. Magisk 模块完整结构

```text
voicechanger_priv/
├── module.prop                          # 模块元数据
├── system/
│   ├── priv-app/
│   │   └── VoiceChanger/
│   │       └── VoiceChanger.apk         # 构建产物改名放入
│   └── etc/
│       └── permissions/
│           └── privapp-permissions-voicechanger.xml
├── post-fs-data.sh                      # 可选：修复 APK 权限
└── sepolicy.rule                        # 仅当出现 avc: denied 才添加（默认不放）
```

### 2.1 module.prop

```properties
id=voicechanger_priv
name=VoiceChanger Privileged
version=0.1.0
versionCode=1
author=voicechanger
description=Mount VoiceChanger app into /system/priv-app to grant MODIFY_AUDIO_ROUTING / CAPTURE_AUDIO_OUTPUT
```

### 2.2 privapp-permissions-voicechanger.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.voicechanger.app">
        <permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
        <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
        <permission name="android.permission.MODIFY_AUDIO_SETTINGS"/>
    </privapp-permissions>
</permissions>
```

### 2.3 post-fs-data.sh（可选，防权限漂移）

```sh
#!/system/bin/sh
# Magisk 挂载后确保 APK 可读（多数情况下 magic mount 已处理）
chmod 644 /system/priv-app/VoiceChanger/VoiceChanger.apk 2>/dev/null
```

## 3. 部署步骤

1. 构建 APK：`./gradlew assembleDebug`（产物 `app/build/outputs/apk/debug/app-debug.apk`）；
2. 复制为 `VoiceChanger.apk` 到模块 `system/priv-app/VoiceChanger/`；
3. 将整个模块目录推送至设备：`/data/adb/modules/voicechanger_priv/`；
4. 重启设备；
5. 执行第 4 节验证清单（不通过则不进行后续 Phase 工作）。

> 建议提供 `tools/magisk_module/` 模板 + 打包脚本（Phase 5 任务，也可提前做），
> 让"构建 → 打包 → 推送"一键完成。模板已在本仓库同步创建。

## 4. 部署后验证清单（逐项必须通过）

```bash
# 4.1 安装路径必须在 priv-app（否则挂载失败）
pm path com.voicechanger.app
# 期望: package:/system/priv-app/VoiceChanger/VoiceChanger.apk

# 4.2 特权 flags
dumpsys package com.voicechanger.app | grep -iE "flags|PRIVILEGED|SYSTEM"

# 4.3 关键权限授予状态
dumpsys package com.voicechanger.app | grep -E "MODIFY_AUDIO_ROUTING|CAPTURE_AUDIO_OUTPUT|MODIFY_AUDIO_SETTINGS"
# 期望全部 granted=true

# 4.4 运行时权限（RECORD_AUDIO 仍需用户授权）
appops get com.voicechanger.app RECORD_AUDIO

# 4.5 路由注册生效（启动服务后）
dumpsys media.audio_policy | grep -A10 "Audio Policy Mix"
dumpsys media.audio_policy | grep -iE "voicechanger|audiopolicy"

# 4.6 应用内诊断页：
#    - "安装路径" 显示 priv-app
#    - "MODIFY_AUDIO_ROUTING / CAPTURE_AUDIO_OUTPUT" granted=true
#    - AudioPolicy 类/方法存在性检查全 OK
```

## 5. 与应用的接口约定

应用侧通过 `route/CapabilityProbe` 和 `route/AudioPolicyProbe`（纯反射）自动检测：

| 检查项 | 说明 |
|---|---|
| 安装路径前缀 | `pm.path` 是否含 `priv-app` |
| 特权权限 granted | `checkSelfPermission` 结果 |
| AudioPolicy 类存在 | 反射 `Class.forName` |
| registerAudioPolicy 调用 | 反射调用返回码 |
| 诊断报告 | 一键复制，含以上全部结论 |

**降级策略**（保证任何环境都不崩溃）：

| 环境 | 行为 |
|---|---|
| priv-app + 权限就绪 | 启用 `PolicyRecorderMixBackend`（recorder mix 注入） |
| 普通安装 | 自动降级到 `MonitorOnlyBackend`（耳机监听），UI 明确提示"未注入" |
| 权限部分缺失 | 诊断页给出精确缺失项与修复指引（Magisk 步骤） |

## 6. hidden API 说明

`android.media.audiopolicy.*` 在 compileSdk 35 公开 SDK 中不存在：
- 编译期：本应用全部通过**反射**访问（`AudioPolicyProbe` / `PolicyRecorderMixBackend`），无需 system stub；
- 运行期：特权应用通常可访问 system API；若目标 ROM 对 hidden API 有额外限制且出现 `NoSuchMethodException` / `SecurityException`：
  1. 首先确认应用确实在 priv-app（验证 4.1/4.2）；
  2. 其次收集 `logcat | grep -iE "hidden|api"` 证据；
  3. 备选：通过 Magisk 额外挂载 `hiddenapi-package-whitelist` sysconfig XML（**仅在必要时**，记录到本文件变更日志）。

## 7. SELinux 说明

- 默认在 `Enforcing` 下运行，**不预设**任何 sepolicy.rule；
- 若出现 `avc: denied`：`dmesg | grep avc | grep -iE "audio|priv_app"`；
- 仅添加针对本应用操作的最小规则，并记录到本文件"变更日志"；
- 严禁 `allow * * * *` 或全局 Permissive。

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `pm path` 显示 `/data/app/` | 模块未挂载/路径错误 | 检查模块目录、重启；确认 Magisk 日志 |
| 权限 `granted=false` | 白名单缺失或包名不匹配 | 核对 XML 包名与 applicationId |
| 权限列表为空 | APK 未声明该权限 | 检查 AndroidManifest（已含两个特权权限声明） |
| 启动时系统提示 privileged permission 未列入白名单 | Android 9+ 强制检查 | 必须补白名单 XML |
| registerAudioPolicy 返回错误码 | 权限未授予 / API 名不同 | 先过 4.3，再跑诊断页看反射结果 |
| 重启后应用数据丢失 | Magisk 挂载的是 APK，数据在 /data | 正常现象；设置持久化在应用私有目录 |

## 9. 变更日志

- 2026-09-19：初版；基于 `plan/语音路由.md` 与 SDK 35 反射约束建立。