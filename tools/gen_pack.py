#!/usr/bin/env python3
"""MineAudio 资源包生成：把 pack/assets 与 pack.mcmeta 打成 pack/out/mineaudio.zip。

产物交给 PackHost：deploy.sh 会复制到 plugins/PackHost/packs/，由 PackHost 合并下发。
不依赖第三方库；音频素材直接放置，长音乐在 sounds.json 中标记 stream。
"""
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAMESPACE = "mineaudio"
ASSETS = os.path.join(ROOT, "pack", "assets")
OUT = os.path.join(ROOT, "pack", "out")
ZIP_PATH = os.path.join(OUT, "mineaudio.zip")


def check_sounds():
    """校验 sounds.json 中本命名空间的引用都有对应 ogg 文件。"""
    sounds_json = os.path.join(ASSETS, NAMESPACE, "sounds.json")
    if not os.path.exists(sounds_json):
        raise SystemExit("缺少 %s" % sounds_json)
    with open(sounds_json, encoding="utf-8") as f:
        sounds = json.load(f)
    missing = []
    for event, data in sounds.items():
        for entry in data.get("sounds", []):
            name = entry["name"] if isinstance(entry, dict) else entry
            if ":" in name:
                namespace, path = name.split(":", 1)
                if namespace != NAMESPACE:
                    continue
            else:
                path = name
            ogg = os.path.join(ASSETS, NAMESPACE, "sounds", path + ".ogg")
            if not os.path.exists(ogg):
                missing.append("%s -> %s" % (event, ogg))
    if missing:
        raise SystemExit("sounds.json 引用了不存在的文件：\n  " + "\n  ".join(missing))


def main():
    check_sounds()
    os.makedirs(OUT, exist_ok=True)
    mcmeta = json.dumps({"pack": {
        "description": "MineAudio 材质包（自定义音乐与环境音）",
        "min_format": [88, 0],
        "max_format": 88,
    }}, ensure_ascii=False, indent=2)

    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(ASSETS):
            for name in sorted(files):
                full = os.path.join(root, name)
                z.write(full, os.path.relpath(full, os.path.join(ROOT, "pack")))
        z.writestr("pack.mcmeta", mcmeta)

    print("生成完成: %s (%d KB)" % (ZIP_PATH, os.path.getsize(ZIP_PATH) // 1024))


if __name__ == "__main__":
    main()
