#!/usr/bin/env bash
# 修复 proot ARM64 环境下的 aapt2（供 build-tools 36 与 Gradle 缓存使用）
set -u

AAPT2_SRC="/data/user/0/com.ai.assistance.operit/files/workspace/d0a7439b-c97a-4614-9d35-174e80f10cf2/tools/aapt2/aapt2-arm64-v8a"
JAR="/root/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2/9.0.0-14304508/ebaf8ea0051e6e61f9cd82d16f3c2bef107cf0d/aapt2-9.0.0-14304508-linux.jar"

echo "==> patch build-tools 36"
cp "$AAPT2_SRC" /root/Android/build-tools/36.0.0/aapt2
chmod +x /root/Android/build-tools/36.0.0/aapt2

echo "==> patch jar: $JAR"
cd /tmp
rm -rf aapt2jar
mkdir aapt2jar
cd aapt2jar
unzip -o -q "$JAR"
cp "$AAPT2_SRC" ./aapt2
chmod +x ./aapt2
zip -q -f "$JAR" aapt2
echo "jar updated"

echo "==> patch transformed copies"
find /root/.gradle/caches -path '*transformed*' -name aapt2 -type f 2>/dev/null | while read -r f; do
  cp "$AAPT2_SRC" "$f"
  chmod +x "$f"
  echo "patched: $f"
done

echo "==> verify transformations dir used by last build"
ls -la /root/.gradle/caches/9.1.0/transforms/d0a8c06996fb551ff1c32cfc0b670264/transformed/ 2>/dev/null

echo "==> done"