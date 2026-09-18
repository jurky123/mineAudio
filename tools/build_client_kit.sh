#!/usr/bin/env bash
# 构建 MineAudio 客户端安装包（MineUI + MoeMusic 及依赖 + Fabric 安装器 + 中文说明）
# 用法: tools/build_client_kit.sh [输出目录]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/tools/out}"
CACHE="$ROOT/tools/cache"
FABRIC_INSTALLER_VERSION="1.1.2"

MINEUI_DIR="$ROOT/../mineUI"
MINEUI_VERSION="$(grep -E '^mineui_version=' "$MINEUI_DIR/gradle.properties" | cut -d= -f2)"
MINEUI_JAR="$MINEUI_DIR/mineui-client/build/libs/mineui-client-$MINEUI_VERSION.jar"
FABRIC_INSTALLER_JAR="$CACHE/fabric-installer-$FABRIC_INSTALLER_VERSION.jar"

if [ ! -f "$MINEUI_JAR" ]; then
    echo "缺少 MineUI 客户端 jar，先构建 MineUI ..."
    (cd "$MINEUI_DIR" && ./gradlew :mineui-client:build -q)
fi

mkdir -p "$CACHE" "$OUT_DIR"
STAGE="$(mktemp -d /tmp/opencode/mineaudio-kit.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT

# 从 Modrinth 解析 Fabric/26.2 的最新版本并下载，输出缓存文件路径
fetch_modrinth() {
    local slug="$1"
    local info
    info="$(python3 - "$slug" <<'PY'
import json, sys, urllib.parse, urllib.request
slug = sys.argv[1]
query = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": '["26.2"]'})
versions = json.load(urllib.request.urlopen(
    "https://api.modrinth.com/v2/project/%s/version?%s" % (slug, query), timeout=60))
if not versions:
    raise SystemExit("Modrinth 上没有 %s 的 Fabric/26.2 版本" % slug)
version = versions[0]
file = next((f for f in version["files"] if f.get("primary")), version["files"][0])
print("%s %s %s" % (version["version_number"], file["filename"], file["url"]))
PY
)"
    read -r MOD_VERSION MOD_FILE MOD_URL <<< "$info"
    local dest="$CACHE/$MOD_FILE"
    if [ ! -f "$dest" ]; then
        echo "下载 $slug $MOD_VERSION ..." >&2
        curl -fsSL -o "$dest" "$MOD_URL"
    fi
    echo "$dest"
}

MODS=()
MODS+=("$(fetch_modrinth moemusic)")
MODS+=("$(fetch_modrinth badpackets)")
MODS+=("$(fetch_modrinth fabric-language-kotlin)")
MODS+=("$(fetch_modrinth fabric-api)")
MODS+=("$(fetch_modrinth cloth-config)")
MODS+=("$(fetch_modrinth modmenu)")

if [ ! -f "$FABRIC_INSTALLER_JAR" ]; then
    echo "下载 Fabric 安装器 $FABRIC_INSTALLER_VERSION ..."
    curl -fsSL -o "$FABRIC_INSTALLER_JAR" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$FABRIC_INSTALLER_VERSION/fabric-installer-$FABRIC_INSTALLER_VERSION.jar"
fi

mkdir -p "$STAGE/mods"
cp "$MINEUI_JAR" "$STAGE/mods/"
for path in "${MODS[@]}"; do
    cp "$path" "$STAGE/mods/"
done
cp "$FABRIC_INSTALLER_JAR" "$STAGE/"

cat > "$STAGE/安装说明.txt" <<EOF
MineAudio 客户端安装说明
==========================================
用于连接服务器 43.160.211.42:25565 的音乐与界面功能。

包内容（mods/ 目录）：
- mineui-client-$MINEUI_VERSION.jar   MineUI 客户端（/audio ui 音乐界面必需）
- $(basename "${MODS[0]}")   MoeMusic 客户端（流媒体播放必需）
- $(basename "${MODS[1]}")   Bad Packets（MoeMusic 前置）
- $(basename "${MODS[2]}")   Fabric Language Kotlin（MoeMusic 前置）
- $(basename "${MODS[3]}")   Fabric API（必需）
- $(basename "${MODS[4]}")   Cloth Config（可选，MoeMusic 设置界面）
- $(basename "${MODS[5]}")   Mod Menu（可选，模组列表入口）
- fabric-installer-$FABRIC_INSTALLER_VERSION.jar  Fabric 安装器（未装 Fabric 时才需要）

前提：已安装 Minecraft Java 版 26.2，且至少启动过一次。

第 1 步：安装 Fabric（已装过可跳过）
1) 双击 fabric-installer-$FABRIC_INSTALLER_VERSION.jar
   （双击无效时用命令行：java -jar fabric-installer-$FABRIC_INSTALLER_VERSION.jar）
2) 选择 Client 页签，Minecraft Version 选 26.2，点击 Install
3) 安装完成后启动器里会出现 fabric-loader 的 26.2 版本

第 2 步：放入 mod
Windows: Win+R 输入 %appdata%\\.minecraft\\mods 回车（没有 mods 文件夹就新建）
macOS:   ~/Library/Application Support/minecraft/mods
Linux:   ~/.minecraft/mods
把 mods/ 里的全部 jar 复制进去。

第 3 步：启动与验证
1) 启动器选择 Fabric 26.2 启动游戏，进入服务器
2) 输入 /audio ui：打开点歌/播放界面（需要 MineUI 客户端）
3) 按 M：打开 MoeMusic 播放器（搜索、队列、歌词）
4) /audio debug 里 "流媒体客户端：已连接" 即 MineAudio 已识别你的 MoeMusic

排查：
- /mineaudio ui 提示需要 MineUI 客户端：确认 mineui-client jar 已放入 mods
- 流媒体歌曲听不到：确认 MoeMusic 与前置都已放入，且歌曲本身可播放（部分 VIP 曲目需要登录）
- 崩溃/进不去：把 .minecraft/logs/latest.log 发给管理员

提示：播放时左上角的旋转唱片卡片是 MoeMusic 本地 HUD，可自行调整：
- 游戏内按 M → 设置：anchor（屏幕四角）、vertical_size（调小）、show_cover / spin_cover、enabled（关闭）
- 或编辑 .minecraft/config/moemusic/moemusic.toml 的 [client.now_playing_hud]
- 服务器计分板会显示正在播放，不需要卡片时可把 HUD 关掉
EOF

KIT_ZIP="$OUT_DIR/mineaudio-client-kit.zip"
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
echo
echo "安装包: $KIT_ZIP"
unzip -l "$KIT_ZIP"
