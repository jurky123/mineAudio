# MineAudio

> Paper 26.2 · Minecraft 统一音乐 / 环境音 / 游戏音效基础设施

MineAudio 不是点歌插件，而是整个服务器的 **Audio Orchestrator**：统一管理“什么时候、给谁、在哪里、播放什么”。

- **Resource Pack OGG**：高音质、原版客户端可听、支持位置声，适合游戏音效与环境音
- **Note Block（NBS）**：音符盒音乐，不增加大型资源包
- **Streaming（MoeMusic）**：服务端只发播放控制与歌曲引用，装了 MoeMusic 客户端的玩家直连网易云/QQ 等音源，服务端不代理音频流

业务插件（MineUNO / MineChess / MineAgent 等）只依赖统一的 `com.mineaudio.api`，不关心声音由哪种 Backend 播放。

- 设计文档：[初步设计文档](初步设计文档)
- 落地方案与实施记录：[docs/PLAN.md](docs/PLAN.md)
- 配套流媒体客户端 Mod 需求：[docs/CLIENT_MOD_REQUIREMENTS.md](docs/CLIENT_MOD_REQUIREMENTS.md)
- MineUI 新增能力需求（音乐 UI 驱动）：[docs/MINEUI_REQUIREMENTS.md](docs/MINEUI_REQUIREMENTS.md)

## 特性（V1）

- 三种来源：`PACK` 资源包声音、`VANILLA` 原版声音、`NBS` 音符盒曲目（NoteBlockAPI）
- 流媒体：`STREAM` 曲目经 MoeMusic 命令桥全服同步播放，按玩家客户端能力自动 fallback
- 四种 Bus：`MUSIC`（每人一条）、`AMBIENT`（多层）、`SFX` / `UI`（短音效）
- 范围：单玩家 / 全服 / 世界 / 区域 / 发声点（Emitter）
- 区域：Cuboid / Sphere、chunk 索引、优先级叠加、边界迟滞、世界层 BGM、环境音层数上限
- 发声点：世界坐标 + 半径，`ALWAYS` / `REDSTONE` / `COMMAND` / `INTERACT` 触发
- Fallback：资源包未加载或 NoteBlockAPI 未安装时自动降级；缺失只影响对应 Backend
- MineUI 音乐界面：`/mineaudio ui` 查看当前播放、控制暂停/停止、点播曲目与停止环境音
- 每玩家能力查询（`AudioCapabilities`）与事件（播放 / 停止 / 进出区域 / Emitter 启动）
- Java API `com.mineaudio.api`：`play` / `playSfx` / `playSfxAt` / `stop` / `registerCue`

## 构建与部署

```bash
./gradlew build                                   # 构建 + 单测
./gradlew :mineaudio-api:publishToMavenLocal      # 供业务插件 compileOnly

./deploy.sh                                       # 部署到 /home/ubuntu/minecraft

./tools/build_client_kit.sh                       # 生成玩家客户端安装包（MineUI + MoeMusic 及依赖）
```

`deploy.sh` 会构建插件、生成资源包、复制 NBS 曲目，并把 `mineaudio.zip` 放进
`plugins/PackHost/packs/`；MineAudio 不自己开 HTTP 端口，资源包由 PackHost 合并下发。
`tools/build_client_kit.sh` 从 Modrinth 解析 Fabric 26.2 最新版本，产出
`tools/out/mineaudio-client-kit.zip`（含安装说明、Fabric 安装器）。

依赖：

- Paper `26.2`（`26.2.build.124-stable` 编译）
- 可选 [NoteBlockAPI](https://modrinth.com/plugin/noteblockapi) 1.7.0：只影响 NBS 曲目，未安装时 PACK / Vanilla 不受影响
- 可选 [MoeMusic](https://modrinth.com/mod/moemusic)（Spigot/Paper 插件 + 客户端 mod）：只影响 STREAM 曲目
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
| `stream.assume-available` | false | 未检测到 MoeMusic 客户端 mod 时是否仍尝试流播放 |
| `stream.http-enabled` | false | 是否允许 `uri` 直链（仍交由 MoeMusic 权限与媒体防火墙） |
| `stream.allowed-hosts` | `[]` | `uri` 直链主机白名单，留空表示不限制 |
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
  radio:
    type: STREAM                # 需要服务端 MoeMusic + 玩家 MoeMusic 客户端
    provider: moemusic
    source: netease             # 音源插件 ID（netease / qqmusic / kugou ...）
    id: "1234567890"            # 曲目 ID；也可用 uri: "https://..."（默认禁用）
    fallback:                   # 客户端/服务端不支持流媒体时降级
      type: PACK
      sound: mineaudio:music.demo
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

## 流媒体（MoeMusic）

MineAudio 不代理音频，只负责“什么时候播什么”：

```text
MineAudio  --/music addById <source> <id> --now-->  MoeMusic 服务端（共享队列）
                                                        │ 只发同步与控制信令
                                          ┌─────────────┼─────────────┐
                                          ▼             ▼             ▼
                                     客户端 A       客户端 B       客户端 C
                                        └──── 各自直连网易云/QQ/酷狗 CDN ────┘
```

使用步骤：

1. 服务端安装 [MoeMusic](https://modrinth.com/mod/moemusic)（Spigot/Paper 1.18.2+）并把音源插件放入 `plugins/MoeMusic/plugins/`（网易云/QQ/酷狗/Bilibili 等，见其 [插件列表](https://github.com/lolicode-org/MoeMusic/wiki)）
2. 玩家安装对应 Minecraft 版本的 MoeMusic 客户端 mod，进服后 MineAudio 通过 `moemusic:client_handshake` 通道自动识别
3. 在 `tracks.yml` 定义 `type: STREAM` 曲目，`/mineaudio play <曲目> global` 即可全服点播

能力与限制（`AudioCapabilities` 如实反映）：

| 能力 | MoeMusic | 说明 |
| --- | --- | --- |
| 同步播放 | ✓ | MoeMusic 负责进度对齐，晚进服也能跟上 |
| 暂停 / 继续 / 停止 | ✓ | 转成 `/music pause|resume|stop` |
| Seek | ✗ | 外部命令桥无此能力 |
| 多会话 | ✗ | 服务端是**一个共享队列**，因此 STREAM 只支持 `global` 播放 |
| 位置声 / 歌词 | ✗ | 客户端 mod 内部能力，未对外开放 |
| 无客户端降级 | ✓ | 配了 `fallback` 的玩家走资源包/NBS；未装且无 fallback 则静默跳过（不报错） |

注意事项：

- 客户端 IP 会直接暴露给音源 CDN，MoeMusic 客户端内置 Media Firewall 黑白名单负责校验；MineAudio 不绕过
- `uri` 直链默认禁用；开启 `stream.http-enabled` 后仍受 `stream.allowed-hosts` 白名单和 MoeMusic 自身权限约束
- 内容过滤、限流、单曲时长策略由 MoeMusic 服务端配置负责，MineAudio 不重复实现

## 音乐界面（MineUI）

`/mineaudio ui` 打开声明式音乐界面（页面 JSON 随插件 jar 发布，改界面不用重发 mod）：

```text
┌─────────────── MineAudio ───────────────┐
│  ♪ MineAudio        流媒体客户端：已连接 │
│  ┌────────────────────────────────────┐ │
│  │ 💿  稻香                            │ │
│  │     周杰伦                          │ │
│  │     ● PLAYING  stream  API          │ │
│  └────────────────────────────────────┘ │
│  [暂停] [继续] [停止音乐] [停止环境音]   │
│  曲目（自己 / 全服）                     │
│  稻香      MUSIC  STREAM   [自己][全服] │
│  demo      MUSIC  PACK     [自己][全服] │
│  环境音（AMBIENT）                       │
│  rain                                    │
│                  [关闭]                  │
└──────────────────────────────────────────┘
```

- 打开时下发完整状态，之后每秒与每次操作后增量推送（服务端权威状态）
- 未安装 MineUI 客户端的玩家回退为聊天提示，不影响其他功能
- MoeMusic 自带的客户端界面（搜索 / 队列 / 歌词，按 `M` 打开）是 mod 内置界面，
  无法并入 MineUI 页面；MineAudio 界面只做服务端可控的状态与控制
- 页面定义：`mineaudio-paper/src/main/resources/assets/mineaudio/ui/mineaudio/player.json`
- 后续（Phase 3 剩余）：搜索、队列、音量、歌词（依赖 MoeMusic 对外能力开放）

### MoeMusic 客户端 HUD（左上角旋转唱片卡片）

那张卡片是 MoeMusic **客户端 mod 本地渲染**的 HUD，服务端（包括 MineAudio）无法把它移进计分板或从服务端缩放；玩家可以自行调整：

- 游戏内按 `M` → 设置：`anchor`（屏幕四角）、`vertical_size`（大小）、`show_cover` / `spin_cover`（唱片与旋转）、`enabled`（关闭）
- 或编辑 `.minecraft/config/moemusic/moemusic.toml` 的 `[client.now_playing_hud]`：
  ```toml
  enabled = true
  anchor = "TOP_RIGHT"
  vertical_size = 32
  show_cover = false
  ```
- 只想看计分板的话，把 `enabled = false` 关掉 HUD 即可

计分板的 `%mineaudio:nowplaying%`：优先显示 MineAudio 自己发起的播放；如果是通过 MoeMusic 界面/命令点歌，MineAudio 会解析 `/music queue` 输出尽力获取当前曲目（best effort，MoeMusic 改输出格式时可能失效，此时退化为 `未在播放`）。

### 计分板占位符（PlaceholderAPI）

MineAudio 注册 `mineaudio` 扩展（需服务器安装 PlaceholderAPI），可在 TAB 等计分板配置中使用：

| 占位符 | 说明 |
| --- | --- |
| `%mineaudio:nowplaying%` | 当前播放，如 `稻香 - 周杰伦`；无播放时为 `未在播放` |
| `%mineaudio:title%` / `%mineaudio:artist%` | 标题 / 作者（流媒体曲目取自 tracks.yml 的 `title` / `author`） |
| `%mineaudio:playing%` | `yes` / `no` |
| `%mineaudio:stream%` | 该玩家是否装了 MoeMusic 客户端：`yes` / `no` |

TAB 示例（本服已配置）：

```yaml
lines:
  - "&6♪ &f%mineaudio:nowplaying%"
```

## 命令

`/mineaudio`，权限 `mineaudio.admin`（默认 op）。玩家名/世界名/曲目/区域均有 Tab 补全。

```text
/mineaudio play <曲目> [self|player <玩家>|world <世界>|global]
/mineaudio stop [MUSIC|AMBIENT|SFX|UI] [self|player <玩家>|world <世界>|global]
/mineaudio pause [玩家]                              # 暂停当前音乐（下发客户端执行）
/mineaudio resume [玩家]                             # 继续
/mineaudio seek <毫秒|mm:ss> [玩家]                  # 定位，如 90000 或 1:30
/mineaudio volume <0-100> [玩家]                     # 运行时音量

/mineaudio region list
/mineaudio region pos1 | pos2                       # 记录准星方块（5 格内）
/mineaudio region create <id>                       # 由 pos1/pos2 生成 Cuboid
/mineaudio region sphere <id> <半径>                # 以当前位置为中心
/mineaudio region settrack <id> <曲目|clear>
/mineaudio region setambient <id> <曲目...|clear>
/mineaudio region setpriority <id> <值>
/mineaudio region delete <id>

/mineaudio emitter list
/mineaudio emitter create <id> <曲目>               # 绑定准星方块
/mineaudio emitter bind <id>                        # 重新绑定准星方块
/mineaudio emitter settrack <id> <曲目>
/mineaudio emitter settrigger <id> <ALWAYS|REDSTONE|COMMAND|INTERACT>
/mineaudio emitter setradius <id> <半径>
/mineaudio emitter start|stop <id>
/mineaudio emitter delete <id>

/mineaudio reload
/mineaudio ui                                       # MineUI 音乐界面（需客户端装 MineUI mod）
/mineaudio debug                                    # 曲目/音效/区域/发声点 + 在线玩家会话
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
│       ├── backend/    # SoundBackend（PACK/VANILLA）/ NbsBackend / StreamBackend
│       ├── playback/   # AudioOrchestrator / 会话状态 / 选源
│       ├── region/     # 形状 / 索引 / 迟滞 / 优先级 / 持久化
│       ├── emitter/    # 发声点与触发
│       ├── stream/     # StreamProvider / MoeMusicProvider 命令桥
│       ├── track/      # Track / Cue 注册表与解析
│       ├── profile/    # 每玩家资源包与流媒体客户端状态
│       └── command/    # /mineaudio
├── pack/               # 资源包源文件（OGG + sounds.json）
├── nbs/                # NBS 曲目源文件
├── tools/gen_pack.py
└── docs/PLAN.md
```

## 状态与后续

V1 已完成：PACK / Vanilla / NBS、四种 Bus、Player/Global/World/Region/Emitter 范围、
Cuboid/Sphere 区域与优先级、红石 Emitter、Cue 与 Fallback、Java API、`/mineaudio debug`。

Phase 2 已完成（MoeMusic 部分）：`STREAM` 曲目、MoeMusic 命令桥、全服同步播放、
按玩家客户端能力 fallback、`moemusic:client_handshake` 能力探测。

Phase 3 已完成（界面部分）：MineUI 音乐界面（当前播放 / 暂停继续停止 / 曲目点播 / 环境音），
客户端安装包由 `tools/build_client_kit.sh` 生成。

后续阶段见 [docs/PLAN.md](docs/PLAN.md)：

- Phase 2.5：Concerto Adapter（可选）、`uri` 直链白名单细化
- Phase 3 剩余：搜索、队列、音量、歌词（依赖 MoeMusic 对外能力开放）
- Phase 4：WorldGuard Adapter、时间与天气条件、播放列表
- Phase 5：MineAudio Client（真正的 per-player stream 与空间音频）
