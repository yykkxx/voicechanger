# 打包 Magisk 模块（复制 APK 到模块目录）
#
# 用法：在仓库根目录执行（需要 bash 终端环境）：
#   bash tools/magisk_module/pack.sh [debug|release]
#
# 产物：
#   tools/magisk_module/voicechanger_priv/system/priv-app/VoiceChanger/VoiceChanger.apk
#   tools/magisk_module/voicechanger_priv.zip  (可选，如需在手机上直接刷入)
#
# 部署（设备已装模块时的一键更新）：
#   cp <APK> /data/adb/modules/voicechanger_priv/system/priv-app/VoiceChanger/VoiceChanger.apk
#   然后重启（或按 plan/08 说明处理挂载快照问题）。
#
set -u

VARIANT="${1:-debug}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODULE_DIR="$SCRIPT_DIR/voicechanger_priv"

case "$VARIANT" in
  debug)   APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk" ;;
  release) APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk" ;;
  *) echo "用法: $0 [debug|release]"; exit 1 ;;
esac

if [ ! -f "$APK" ]; then
  echo "找不到 APK: $APK"
  echo "请先执行: ./gradlew assemble${VARIANT^}"
  exit 1
fi

DEST="$MODULE_DIR/system/priv-app/VoiceChanger/VoiceChanger.apk"
mkdir -p "$(dirname "$DEST")"
cp "$APK" "$DEST"
echo "已复制: $APK -> $DEST"

# 可选 zip 打包（排除 models/：ONNX 模型体积大，单独推送）
ZIP="$SCRIPT_DIR/voicechanger_priv.zip"
if command -v zip >/dev/null 2>&1; then
  (cd "$MODULE_DIR" && zip -r "$ZIP" . -x '.*' -x 'models/*') >/dev/null
  echo "已打包: $ZIP（不含 models/）"
else
  echo "未找到 zip 命令；跳过打包（可手动复制模块目录到手机 /data/adb/modules/）"
fi

echo ""
echo "安装步骤："
echo "1. 将 $MODULE_DIR 推送到手机: /data/adb/modules/voicechanger_priv/"
echo "2. 重启设备"
echo "3. 验证: pm path com.voicechanger.app  (应含 priv-app)"
echo "   验证: dumpsys package com.voicechanger.app | grep MODIFY_AUDIO_ROUTING  (granted=true)"