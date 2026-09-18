#!/usr/bin/env bash
# 构建并部署 MineAudio（插件 jar + 资源包 + NBS）到 Paper 服务器
# 用法: ./deploy.sh [服务器根目录]   （默认 /home/ubuntu/minecraft）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVER="${1:-/home/ubuntu/minecraft}"

if [ ! -d "$SERVER/plugins" ]; then
    echo "找不到服务器目录: $SERVER（应包含 plugins/）" >&2
    exit 1
fi

echo "==> 构建 MineAudio"
(cd "$ROOT" && ./gradlew build -q)

JAR=$(ls -t "$ROOT"/mineaudio-paper/build/libs/MineAudio-*.jar | head -n1)
rm -f "$SERVER/plugins/"MineAudio-*.jar
cp "$JAR" "$SERVER/plugins/"

# NBS 曲目（无文件时跳过）
mkdir -p "$SERVER/plugins/MineAudio/nbs"
if compgen -G "$ROOT/nbs/*.nbs" > /dev/null; then
    cp "$ROOT"/nbs/*.nbs "$SERVER/plugins/MineAudio/nbs/"
fi

# 资源包交给 PackHost 合并下发（源目录 pack/，产物由 gen_pack.py 生成）
echo "==> 生成资源包"
python3 "$ROOT/tools/gen_pack.py"
if [ -f "$ROOT/pack/out/mineaudio.zip" ]; then
    mkdir -p "$SERVER/plugins/PackHost/packs"
    cp "$ROOT/pack/out/mineaudio.zip" "$SERVER/plugins/PackHost/packs/"
fi

echo
echo "部署完成："
echo "  $SERVER/plugins/$(basename "$JAR")"
echo "  $SERVER/plugins/MineAudio/nbs/  ($(ls "$ROOT"/nbs/*.nbs 2>/dev/null | wc -l) 个 NBS)"
if [ -f "$ROOT/pack/out/mineaudio.zip" ]; then
    echo "  $SERVER/plugins/PackHost/packs/mineaudio.zip"
fi
echo
echo "下一步："
echo "  1. 重启服务器（或游戏内 /mineaudio reload）"
echo "  2. 有资源包更新时执行 /packhost reload"
echo "  3. /mineaudio debug 检查运行状态"
