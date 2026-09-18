# MineAudio

> Paper 26.2 · Minecraft 统一音乐 / 环境音 / 游戏音效基础设施

MineAudio 不是点歌插件，而是整个服务器的 **Audio Orchestrator**：统一管理“什么时候、给谁、在哪里、播放什么”。

- **Resource Pack OGG**：高音质、原版客户端可听、支持位置声，适合游戏音效与环境音
- **Note Block（NBS）**：音符盒音乐，不增加大型资源包
- **Streaming（规划中）**：对接 MoeMusic / Concerto，服务端只发送播放控制与歌曲信息，不代理音频流

业务插件（MineUNO / MineChess / MineAgent 等）只依赖统一的 `com.mineaudio.api`，不关心声音由哪种 Backend 播放。

- 设计文档：[初步设计文档](初步设计文档)
- 落地方案与实施记录：[docs/PLAN.md](docs/PLAN.md)

## 特性（V1）

- 三种来源：`PACK` 资源包声音、`VANILLA` 原版声音、`NBS` 音符盒曲目（NoteBlockAPI）
- 四种 Bus：`MUSIC`（每人一条）、`AMBIENT`（多层）、`SFX` / `UI`（短音效）
- 范围：单玩家 / 全服 / 世界 / 区域 / 发声点（Emitter）
- 区域：Cuboid / Sphere、chunk 索引、优先级叠加、边界迟滞、世界层 BGM、环境音层数上限
- 发声点：世界坐标 + 半径，`ALWAYS` / `REDSTONE` / `COMMAND` / `INTERACT` 触发
- Fallback：资源包未加载或 NoteBlockAPI 未安装时自动降级；缺失只影响对应 Backend
- 每玩家能力查询（`AudioCapabilities`）与事件（播放 / 停止 / 进出区域 / Emitter 启动）
- Java API `com.mineaudio.api`：`play` / `playSfx` / `playSfxAt` / `stop` / `registerCue`

## 构建与部署

```bash
./gradlew build                                   # 构建 + 单测
./gradlew :mineaudio-api:publishToMavenLocal      # 供业务插件 compileOnly

./deploy.sh                                       # 部署到 /home/ubuntu/minecraft
```

`deploy.sh` 会构建插件、生成资源包、复制 NBS 曲目，并把 `mineaudio.zip` 放进
`plugins/PackHost/packs/`；MineAudio 不自己开 HTTP 端口，资源包由 PackHost 合并下发。

依赖：

- Paper `26.2`（`26.2.build.124-stable` 编译）
- 可选 [NoteBlockAPI](https://modrinth.com/plugin/noteblockapi) 1.7.0：只影响 NBS 曲目，未安装时 PACK / Vanilla 不受影响
- 可选 PackHost：资源包托管；没有时 PACK 声音按 `pack.assume-loaded` 处理

## 配置

数据文件位于 `plugins/MineAudio/`：`config.yml`、`tracks.yml`、`cues.yml`、`regions.yml`、`emitters.yml`。
`tracks.yml` / `cues.yml` / `regions.yml` / `emitters.yml` 使用原始 YAML 读取，**key 中的 `.` 不会被当作路径拆分**，
因此 `mineuno:card.play` 这类 ID 可以原样书写。

### config.yml

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `region-check-interval-ticks` | 10 | 区域检测间隔 |
| `region-enter-delay-ms` / `region-exit-delay-ms` | 300 / 500 | 边界迟滞默认值 |
| `max-ambient-layers` | 3 | AMBIENT 同时层数上限 |
| `emitter-poll-interval-ticks` | 5 | 红石 Emitter 轮询间隔 |
| `nbs.position-distance` | 32 | NBS 位置声默认可听距离 |
| `pack.assume-loaded` | true | 未跟踪到资源包状态时是否视为已加载 |
| `debug` | false | 输出播放选源等调试日志 |

### tracks.yml

```yaml
tracks:
  demo:
    type: PACK                  # PACK | VANILLA | NBS
    bus: MUSIC                  # MUSIC | AMBIENT | SFX | UI，默认 MUSIC
    sound: mineaudio:music.demo
    duration: 12000             # 毫秒；PACK / VANILLA 循环必须提供
    loop: true
    volume: 0.7
    fallback:                   # 可选：资源包未加载时降级
      type: VANILLA
      sound: minecraft:music.overworld.forest
  tavern:
    type: NBS
    file: tavern.nbs            # 只允许 plugins/MineAudio/nbs/ 下的文件名
    loop: true
```

### cues.yml

语义音效：业务插件只认 key，素材与 fallback 在这里或由插件代码注册。

```yaml
cues:
  mineuno:card.play:
    primary:  { type: PACK, sound: mineuno:card.play }
    fallback: { type: VANILLA, sound: minecraft:item.book.page_turn }
```

### regions.yml

```yaml
worlds:                         # 世界层 BGM（priority 0）
  world:
    music: spawn
    ambient: [birds]

regions:
  tavern:
    world: world
    shape:
      type: CUBOID              # CUBOID | SPHERE
      min: { x: 100, y: 60, z: -40 }
      max: { x: 150, y: 80, z: -10 }
    priority: 20                # 重叠时 MUSIC 取最高优先级
    music: tavern
    ambient: [fireplace, rain]  # 受 max-ambient-layers 限制
    fade-in-ms: 1000            # PACK / Vanilla 无法真正淡入淡出，仅记录（Best effort）
    fade-out-ms: 1000
    enter-delay-ms: 300         # 可选，覆盖 config 默认
    exit-delay-ms: 500
```

### emitters.yml

```yaml
emitters:
  tavern_radio:
    world: world
    x: 135
    y: 65
    z: -21
    track: tavern
    radius: 24                  # PACK 用音量近似；NBS 用 PositionSongPlayer distance
    trigger: REDSTONE           # ALWAYS | REDSTONE | COMMAND | INTERACT
    loop: true
```

## 命令

`/audio`，权限 `mineaudio.admin`（默认 op）。玩家名/世界名/曲目/区域均有 Tab 补全。

```text
/audio play <曲目> [self|player <玩家>|world <世界>|global]
/audio stop [MUSIC|AMBIENT|SFX|UI] [self|player <玩家>|world <世界>|global]

/audio region list
/audio region pos1 | pos2                       # 记录准星方块（5 格内）
/audio region create <id>                       # 由 pos1/pos2 生成 Cuboid
/audio region sphere <id> <半径>                # 以当前位置为中心
/audio region settrack <id> <曲目|clear>
/audio region setambient <id> <曲目...|clear>
/audio region setpriority <id> <值>
/audio region delete <id>

/audio emitter list
/audio emitter create <id> <曲目>               # 绑定准星方块
/audio emitter bind <id>                        # 重新绑定准星方块
/audio emitter settrack <id> <曲目>
/audio emitter settrigger <id> <ALWAYS|REDSTONE|COMMAND|INTERACT>
/audio emitter setradius <id> <半径>
/audio emitter start|stop <id>
/audio emitter delete <id>

/audio reload
/audio debug                                    # 曲目/音效/区域/发声点 + 在线玩家会话
```

区域与发声点命令会写回对应 YAML（注释会丢失，文件头说明保留）。

## 业务插件接入

```java
MineAudio audio = MineAudioProvider.get();
if (audio != null) {
    // 语义音效：key 由 cues.yml 或 registerCue 提供，素材换了业务代码不用改
    audio.playSfx(player, Key.key("mineuno", "card.play"));

    // 位置音效：附近玩家按距离衰减
    audio.playSfxAt(board.square("e4"), Key.key("minechess", "piece.move"));

    // 整曲：Audience 支持 player / players / world / global
    audio.play(Audience.player(player), Key.key("mineaudio", "spawn"));
}
```

业务插件自带音效时在 `onEnable` 注册、`onDisable` 注销：

```java
audio.registerCue(this, new AudioCue(
        Key.key("mineuno", "card.play"),
        AudioBus.SFX,
        new AudioSource.PackSound(Key.key("mineuno", "card.play")),
        new AudioSource.VanillaSound(Key.key("minecraft", "item.book.page_turn")),
        PlaybackOptions.DEFAULT));
```

`plugin.yml` 声明 `softdepend: [MineAudio]`，编译期依赖：

```groovy
compileOnly 'com.mineaudio:mineaudio-api:0.1.0'   // 先 ./gradlew :mineaudio-api:publishToMavenLocal
```

## 素材规范

- 全局 BGM 用 **stereo OGG**；需要 3D 衰减的位置声用 **mono OGG**（`attenuation_distance` 只对单声道生效）
- 长音乐在 `sounds.json` 标记 `"stream": true`
- 资源包源文件在 `pack/`，`python3 tools/gen_pack.py` 生成 `pack/out/mineaudio.zip`
- NBS 文件放 `nbs/`，部署到 `plugins/MineAudio/nbs/`
- 当前 `demo.ogg` / `test.ogg` 是占位示例，可直接替换

## 目录结构

```text
mineAudio/
├── mineaudio-api/      # 业务插件依赖的稳定 Java API（publishToMavenLocal）
├── mineaudio-paper/    # Paper 插件实现
│   └── com.mineaudio
│       ├── backend/    # SoundBackend（PACK/VANILLA）/ NbsBackend
│       ├── playback/   # AudioOrchestrator / 会话状态 / 选源
│       ├── region/     # 形状 / 索引 / 迟滞 / 优先级 / 持久化
│       ├── emitter/    # 发声点与触发
│       ├── track/      # Track / Cue 注册表与解析
│       ├── profile/    # 每玩家资源包状态
│       └── command/    # /audio
├── pack/               # 资源包源文件（OGG + sounds.json）
├── nbs/                # NBS 曲目源文件
├── tools/gen_pack.py
└── docs/PLAN.md
```

## 状态与后续

V1 已完成：PACK / Vanilla / NBS、四种 Bus、Player/Global/World/Region/Emitter 范围、
Cuboid/Sphere 区域与优先级、红石 Emitter、Cue 与 Fallback、Java API、`/audio debug`。

后续阶段见 [docs/PLAN.md](docs/PLAN.md)：

- Phase 2：MoeMusic / Concerto 流媒体 Adapter、Stream Track、同步进度
- Phase 3：MineUI 播放界面（正在播放 / 搜索 / 点歌 / 队列 / 音量）
- Phase 4：WorldGuard Adapter、时间与天气条件、播放列表
- Phase 5：MineAudio Client（真正的 per-player stream 与空间音频）
