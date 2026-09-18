#!/usr/bin/env bash
# 构建 MineAudio 客户端安装包（只含本插件产物，前置 mod 由玩家自行安装）
# 产物命名带版本号：tools/out/mineaudio-client-kit-26.2-<版本>.zip
# 用法: tools/build_client_kit.sh [输出目录]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/tools/out}"
VERSION="$(grep -E '^mineaudio_version=' "$ROOT/gradle.properties" | cut -d= -f2)"
MOD_JAR="$ROOT/mineaudio-client-fabric-26.2/build/libs/mineaudio-client-26.2-$VERSION.jar"

if [ ! -f "$MOD_JAR" ]; then
    echo "缺少客户端 jar，先构建 ..."
    (cd "$ROOT" && ./gradlew :mineaudio-client-fabric-26.2:build -q)
fi

mkdir -p "$OUT_DIR"
STAGE="$(mktemp -d /tmp/opencode/mineaudio-kit.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/mods"
cp "$MOD_JAR" "$STAGE/mods/"

cat > "$STAGE/安装说明.txt" <<EOF
MineAudio Client 安装说明（26.2 · v$VERSION）
==========================================
包内容：
- mods/mineaudio-client-26.2-$VERSION.jar  MineAudio 流媒体客户端

前置（已安装可跳过，本包不再附带）：
- Fabric API
- MineUI 客户端（/mineaudio ui 界面用）
- 可选：MoeMusic 客户端及其前置（Legacy 流媒体路径）

安装：
1) 把 mods/ 里的 jar 放入 .minecraft/mods
2) 启动 Fabric 26.2 进服
3) /mineaudio debug 出现 "MineAudio Client $VERSION mc=26.2 caps=[...]" 即握手成功

说明：本客户端只负责音频播放与状态上报，界面与 HUD 由 MineUI 提供。
EOF

KIT_ZIP="$OUT_DIR/mineaudio-client-kit-26.2-$VERSION.zip"
rm -f "$KIT_ZIP"
python3 - "$STAGE" "$KIT_ZIP" <<'PY'
import os, sys, zipfile

stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
    for base, _dirs, files in os.walk(stage):
        for name in sorted(files):
            full = os.path.join(base, name)
            zf.write(full, os.path.relpath(full, stage))
PY
echo "安装包: $KIT_ZIP"
unzip -l "$KIT_ZIP"
