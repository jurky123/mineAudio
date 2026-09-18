# MineAudio 资源包

```text
pack/
├── assets/mineaudio/
│   ├── sounds.json
│   └── sounds/
│       ├── test.ogg            # 短音效（mono，可做位置声）
│       └── music/demo.ogg      # 长音乐（stereo，sounds.json 标 stream）
└── out/mineaudio.zip           # tools/gen_pack.py 生成，deploy.sh 交给 PackHost
```

约定：

- **全局 BGM 用 stereo OGG**；**需要 3D 衰减的位置声用 mono OGG**（Minecraft 的 `attenuation_distance` 只对单声道生效）
- 长音乐在 `sounds.json` 条目标记 `"stream": true`，避免整段载入内存
- `sounds.json` 事件名即 `tracks.yml`/`cues.yml` 里的 `mineaudio:<事件名>`（如 `mineaudio:music.demo`）
- 修改素材后执行 `python3 tools/gen_pack.py`，再 `./deploy.sh` 并游戏内 `/packhost reload`
- 当前 `test.ogg` / `music/demo.ogg` 是占位示例（oggenc 生成的正弦音），可直接删除或替换为正式素材
