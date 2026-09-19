#!/system/bin/sh
# Magisk post-fs-data:
# 1) 确保挂载后的 APK 可被 PackageManager 读取；
# 2) 把 RVC 模型从模块目录同步到应用私有目录
#    （应用进程无权读取 /data/adb/modules，必须由 root 在开机时复制）。

MODDIR=${0%/*}

chmod 644 /system/priv-app/VoiceChanger/VoiceChanger.apk 2>/dev/null
chmod 755 /system/priv-app/VoiceChanger 2>/dev/null

MODEL_SRC="$MODDIR/models"
APP_DATA=/data/data/com.voicechanger.app
DEST="$APP_DATA/files/models/rvc"
FILES="hubert.onnx rmvpe.onnx net_g32k_L32.onnx net_g48k_L32.onnx net_g48k_L50.onnx net_g48k.onnx rmvpe_mel.bin"

if [ -d "$MODEL_SRC" ] && [ -d "$APP_DATA" ]; then
  mkdir -p "$DEST"
  for f in $FILES; do
    src="$MODEL_SRC/$f"
    dst="$DEST/$f"
    [ -f "$src" ] || continue
    # 尺寸一致则跳过（避免每次开机重复拷贝大文件）
    if [ -f "$dst" ] && [ "$(stat -c %s "$src" 2>/dev/null)" = "$(stat -c %s "$dst" 2>/dev/null)" ]; then
      continue
    fi
    cp -f "$src" "$dst" 2>/dev/null
  done
  UID_APP=$(stat -c %u "$APP_DATA" 2>/dev/null)
  GID_APP=$(stat -c %g "$APP_DATA" 2>/dev/null)
  if [ -n "$UID_APP" ]; then
    chown -R "$UID_APP:$GID_APP" "$APP_DATA/files" 2>/dev/null
  fi
  chmod 700 "$DEST" 2>/dev/null
  chmod 600 "$DEST"/* 2>/dev/null
fi