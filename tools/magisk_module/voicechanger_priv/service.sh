#!/system/bin/sh
# Magisk late_start service：
# 把 RVC 模型从模块目录同步到应用私有目录。
# 放在 service.sh（而非 post-fs-data）是因为 /data/data 在 FBE 解锁后才可写；
# 模型较大（约 850 MB），用尺寸比对跳过重复拷贝。

MODDIR=${0%/*}
MODEL_SRC="$MODDIR/models"
APP_DATA=/data/data/com.voicechanger.app
DEST="$APP_DATA/files/models/rvc"
FILES="hubert.onnx rmvpe.onnx net_g32k_L32.onnx net_g48k_L32.onnx net_g48k_L50.onnx net_g48k.onnx rmvpe_mel.bin"

# 等待应用数据目录可写（最多 60 s）
i=0
while [ ! -d "$APP_DATA" ] && [ $i -lt 60 ]; do
  sleep 1
  i=$((i + 1))
done

[ -d "$MODEL_SRC" ] && [ -d "$APP_DATA" ] || exit 0

mkdir -p "$DEST" 2>/dev/null

for f in $FILES; do
  src="$MODEL_SRC/$f"
  dst="$DEST/$f"
  [ -f "$src" ] || continue
  if [ -f "$dst" ] && [ "$(stat -c %s "$src" 2>/dev/null)" = "$(stat -c %s "$dst" 2>/dev/null)" ]; then
    continue
  fi
  cp -f "$src" "$dst" 2>/dev/null
done

UID_APP=$(stat -c %u "$APP_DATA" 2>/dev/null)
GID_APP=$(stat -c %g "$APP_DATA" 2>/dev/null)
if [ -n "$UID_APP" ]; then
  chown -R "$UID_APP:$GID_APP" "$DEST" 2>/dev/null
fi
chmod 700 "$DEST" 2>/dev/null
chmod 600 "$DEST"/* 2>/dev/null

# 兼容首次安装：应用自己也可从 /sdcard/Download/VoiceChanger/models 兜底拷贝
exit 0