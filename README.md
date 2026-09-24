# MineAudio

> Paper 26.2 · Minecraft 统一音乐 / 环境音 / 游戏音效基础设施

MineAudio 不是点歌插件，而是整个服务器的 **Audio Orchestrator**：统一管理“什么时候、给谁、在哪里、播放什么”。

- **Resource Pack OGG**：高音质、原版客户端可听、支持位置声，适合游戏音效与环境音
- **Note Block（NBS）**：音符盒音乐，不增加大型资源包
- **Streaming（自研客户端）**：服务端解析直链并下发播放控制，装了 MineAudio Client 的玩家直连音源，服务端不代理音频流

业务插件（MineUNO / MineChess / MineAgent 等）只依赖统一的 `com.mineaudio.api`，不关心声音由哪种 Backend 播放。

- 设计文档：[初步设计文档](初步设计文档)
- 落地方案与实施记录：[docs/PLAN.md](docs/PLAN.md)
- 配套流媒体客户端 Mod 需求：[docs/CLIENT_MOD_REQUIREMENTS.md](docs/CLIENT_MOD_REQUIREMENTS.md)
- MineUI 新增能力需求（音乐 UI 驱动）：[docs/MINEUI_REQUIREMENTS.md](docs/MINEUI_REQUIREMENTS.md)

## 特性（V1）

- 三种来源：`PACK` 资源包声音、`VANILLA` 原版声音、`NBS` 音符盒曲目（NoteBlockAPI）
- 流媒体：`STREAM` 曲目由服务端解析直链、自研客户端播放；每玩家会话，支持同步 / 暂停 / 定位 / 音量
- 搜索与点歌：网易搜索（标题/歌手/封面/可播放性），**全服点歌队列**（统一队列，上限默认 2 首），曲目结束后自动续播
- **客户端本地曲库**：玩家自有音频文件放在客户端目录，本地解析标题/歌手/封面并可直接试听；
  点歌到全服时上传分发（M2，进行中）
- 四种 Bus：`MUSIC`（每人一条）、`AMBIENT`（多层）、`SFX` / `UI`（短音效）
- 范围：单玩家 / 全服 / 世界 / 区域 / 发声点（Emitter）
- 区域：Cuboid / Sphere、chunk 索引、优先级叠加、边界迟滞、世界层 BGM、环境音层数上限
- 发声点：世界坐标 + 半径，`ALWAYS` / `REDSTONE` / `COMMAND` / `INTERACT` 触发
- Fallback：资源包未加载或 NoteBlockAPI 未安装时自动降级；缺失只影响对应 Backend
- MineUI 音乐界面（需要 MineUI 服务端/客户端 **0.14+**、客户端包内含 `mineui-client-api`）：`/mineaudio ui` 查看当前播放（含进度条）、控制暂停/继续/定位/音量、点播曲目，`/mineaudio hud` 切换“正在播放”HUD
- 每玩家能力查询（`AudioCapabilities`）与事件（播放 / 停止 / 进出区域 / Emitter 启动）
- Java API `com.mineaudio.api`：`play` / `playSfx` / `playSfxAt` / `stop` / `registerCue`

## 构建与部署

```bash
./gradlew build                                   # 构建 + 单测
./gradlew :mineaudio-api:publishToMavenLocal      # 供业务插件 compileOnly

./deploy.sh                                       # 部署到 /home/ubuntu/minecraft

./tools/build_client_kit.sh                       # 生成玩家客户端安装包（只含 MineAudio Client）
```

`deploy.sh` 会构建插件、生成资源包、复制 NBS 曲目，并把 `mineaudio.zip` 放进
`plugins/PackHost/packs/`；MineAudio 不自己开 HTTP 端口，资源包由 PackHost 合并下发。
`tools/build_client_kit.sh` 从 Modrinth 解析 Fabric 26.2 最新版本，产出
`tools/out/mineaudio-client-kit.zip`（含安装说明、Fabric 安装器）。

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
| `stream.assume-available` | false | 未检测到 MineAudio Client 时是否仍尝试流播放 |
| `stream.http-enabled` | false | 是否允许 `uri` 直链（客户端 MediaFirewall 仍会校验） |
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
    type: STREAM                # 需要玩家安装 MineAudio Client
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

## 流媒体（自研客户端）

MineAudio 全链路自研：服务端解析直链并下发播放控制，玩家安装 MineAudio Client 后直连音源，
服务端不代理音频流，不依赖任何第三方音乐插件。

```text
MineAudio  --解析直链 + 下发 PLAY-->  MineAudio Client（本地解码播放）
     │                                     │ 状态上报（位置/缓冲/错误）
     └── Resolver（直链 / 网易 eapi）   直连 CDN（HTTPS + MediaFirewall）
```

使用步骤：

1. 玩家安装 `mineaudio-client-kit`（`tools/build_client_kit.sh` 生成），进服后经 `mineaudio:stream` 通道自动握手
2. 在 `tracks.yml` 定义 `type: STREAM` 曲目（`uri` 直链或 `source + id`，如网易云）
3. `/mineaudio play <曲目> global` 点播；支持暂停/继续/定位/音量，`/mineaudio ui` 有完整界面
   （全服＝共享锚点起播 + 晚加入自动对齐；确认语义：**松散共享**——暂停/seek 仍为 per-player，
   不做全服统一控制；不替换自然结束的全局会话，旧会话结束即清理，不会被区域刷新重启）

能力（`AudioCapabilities` 如实反映）：

| 能力 | 自研客户端 | 说明 |
| --- | --- | --- |
| 同步播放 | ✓ | 共享时间轴：服务端统一锚点，晚加入者按当前进度自动对齐 |
| 暂停 / 继续 / 停止 | ✓ | 协议指令下发，服务端权威 |
| Seek | ✓ | 命令与界面都支持（绝对定位 / ±15s） |
| 音量 | ✓ | 运行时音量，自动跟随游戏设置 |
| 多会话 | ✓ | 每位玩家独立会话，可 per-player / per-region |
| 位置声 | ✓ | 3D 定位播放 |
| 无客户端降级 | ✓ | 配了 `fallback` 的玩家走资源包/NBS；未装且无 fallback 则不可播 |

注意事项：

- 客户端 IP 直连音源 CDN；客户端内置 MediaFirewall（HTTPS-only、私网拦截、白黑名单、逐跳重定向校验）
- `uri` 直链默认禁用；开启 `stream.http-enabled` 后仍受 `stream.allowed-hosts` 白名单约束
- 网易 `source + id` 由服务端最小 eapi Resolver 解析（`resolvers.netease.*`）；
  凭证只从环境变量/文件读取，不落配置、不进日志、不下发客户端
- 解析失败按分类上报（`UNSUPPORTED_SOURCE / CREDENTIAL_MISSING / NOT_PLAYABLE / …`），UI 与 Toast 直接展示

## 音乐界面（MineUI）

`/mineaudio ui` 打开声明式音乐界面（页面 JSON 随插件 jar 发布，改界面不用重发 mod）：

```text
┌─────────────── MineAudio ───────────────┐
│  ♪ MineAudio        流媒体客户端：已连接 │
│  ┌────────────────────────────────────┐ │
│  │ 💿  稻香                            │ │
│  │     周杰伦                          │ │
│  │     ● PLAYING  stream  API          │ │
│  │     ███████████░░░░░░░░  1:23/3:45  │ │
│  └────────────────────────────────────┘ │
│  [暂停] [继续] [停止音乐] [停止环境音]   │
│  [-15s] [+15s] [音量-] [音量+] 100% [HUD]│
│  曲目（点歌 / 自己）                     │
│  稻香      MUSIC  STREAM   [点歌][自己] │
│  demo      MUSIC  PACK     [点歌][自己] │
│  环境音（AMBIENT）                       │
│  rain                                    │
│                  [关闭]                  │
└──────────────────────────────────────────┘
```

- 打开时下发完整状态，之后每秒与每次操作后增量推送（服务端权威状态）
- 进度条/拖动在客户端支持 `local_state` 时走**本地绑定**（`{local.mineaudio.*}` + `local:mineaudio.*`，
  逐帧读客户端播放时钟、动作本地直连，不经服务端）；旧客户端或未装 mineui-client-api 时
  自动回退到服务端 1Hz STATE 推送 + 协议指令
- 解析状态与失败分类（`UNSUPPORTED_SOURCE / CREDENTIAL_MISSING / …`）直接在页面上显示，失败时同时 Toast
- 页面分三个 Tab：**正在播放**（封面/进度/控制）、**搜索点歌**（输入框+结果+队列）、**曲库**（曲目/环境音）
- 页面按钮：暂停/继续/停止/±15s 定位/音量 ±10%/HUD 开关；键位：`F7`(槽位1) 打开界面、`F8`(槽位2) 切换 HUD（可在原版按键设置改键）
- “正在播放”HUD（左上角，MineUI 0.8+ 客户端）：进服自动开启（`stream-client.hud-auto: false` 可关），无播放时自动隐藏；`/mineaudio hud` 手动切换；节点定义 `hud.json`，进度条同样插值
- 未安装 MineUI 客户端的玩家回退为聊天提示，不影响其他功能
- 页面定义：`mineaudio-paper/src/main/resources/assets/mineaudio/ui/mineaudio/player.json`（HUD 为 `hud.json`）
- 后续（Phase 3 剩余）：搜索、队列、歌词（等 MineUI 通用能力）

### 播放语义（点歌 / 自己）

MineAudio 的 MUSIC 由每玩家 **Music Arbiter** 统一仲裁，优先级 **个人点播 > 业务受众 > 区域 > 世界**：

- **点歌（全服队列）**：搜索结果“点歌”与曲库“点歌”→ 进入**全服点歌队列**，按全服共享时间轴统一播放；
  空闲时立即起播，正在播放时排队，曲终自动续播下一首
- **自己（临时）**：搜索结果“自己”与曲库“自己”→ 只对该玩家播放（PERSONAL），临时覆盖全服/区域；
  **播放结束或手动停止后自动回到当前全服进度**（按共享时间轴对齐，不从头）
- **停止**：自己播放中停止 → 只停止自己并回到全服；全服播放中停止 → 停止全服并清空点歌队列
- 全服曲目在被“自己”遮挡期间越过时长时，会直接切到队列下一首，不会回到已结束的曲目

### 客户端本地曲库（0.4.0 M1）

因为服务端只有非 VIP cookie、很多歌无法解析，MineAudio 支持**客户端本地曲库**：玩家把自己的音频文件
放进客户端目录，本地解析并直接试听，**不占用服务器存储、不经服务器播放**。

- 目录：`<游戏目录>/config/mineaudio/library/`（可含子目录），把 `mp3 / flac / ogg / m4a / wav …` 放进去
- 解析：jaudiotagger 读取标题 / 歌手 / 专辑 / 时长与**嵌入封面**；无标签时用文件名兜底
- 索引：`library/index.json` 按“大小 + 修改时间”缓存，未变化的文件不重复解析；按内容 sha256 去重
- 封面：导出到 `library/.covers/<sha256>.<ext>`（供后续分发用）
- 界面：`/mineaudio ui` → **曲库** Tab 顶部“本地曲库（客户端）”，列出本地曲目，支持“试听”（本地直接播放）与“刷新”；
  未安装客户端本地 API 时该区块为空、动作忽略
- 点歌到全服：需要把本地文件上传到服务器再分发给其他玩家（M2，进行中；当前“点歌”按钮暂为占位）

### 计分板占位符（PlaceholderAPI）

MineAudio 注册 `mineaudio` 扩展（需服务器安装 PlaceholderAPI），可在 TAB 等计分板配置中使用：

| 占位符 | 说明 |
| --- | --- |
| `%mineaudio:nowplaying%` | 当前播放，如 `稻香 - 周杰伦`；无播放时为 `未在播放` |
| `%mineaudio:title%` / `%mineaudio:artist%` | 标题 / 作者（流媒体曲目取自 tracks.yml 的 `title` / `author`） |
| `%mineaudio:playing%` | `yes` / `no` |
| `%mineaudio:stream%` | 该玩家是否可流播放（已装 MineAudio Client）：`yes` / `no` |

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
/mineaudio search <关键词>                           # 搜索网易云（标题/歌手/封面/可播放标记）
/mineaudio search next|prev                          # 搜索结果翻页（UI 搜索页也有翻页按钮）
/mineaudio queue add|play <序号>                     # 点歌入全服队列（上限默认 2 首）/ 立即“自己”播放
/mineaudio queue list | clear                        # 查看 / 清空全服点歌队列

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
/mineaudio hud                                      # 切换“正在播放”HUD（进服自动开启；MineUI 0.8+ 客户端）
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
│       ├── stream/     # StreamProvider / Resolver 链路
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

Phase 2 已完成：`STREAM` 曲目、自研流媒体客户端（协议/握手/校时/安全媒体链路）、
按玩家能力 fallback；MoeMusic 兼容路径（命令桥、Legacy 降级、展示解析）已全部移除。

Phase 3 已完成（界面部分）：MineUI 音乐界面（当前播放 + 进度条 / 暂停继续停止 / ±15s 定位 /
音量调整 / 曲目点播 / 环境音 / 解析状态与失败分类）、“正在播放”HUD（`/mineaudio hud`）、
键位（槽位1 打开界面、槽位2 切换 HUD）与错误 Toast；客户端安装包由 `tools/build_client_kit.sh` 生成。

后续阶段见 [docs/PLAN.md](docs/PLAN.md)：

### 已知修复

- **v0.3.0**：引入每玩家 **Music Arbiter**（`MusicLayer` + `MusicIntent` + `MusicArbiter`）——
  MUSIC 各来源只声明“应该存在什么”，`reconcileMusic` 是唯一实际播放入口，按
  **个人点播 > 业务受众 > 区域 > 世界** 选唯一 winner。修复全服/个人互相顶替、`refresh` 反抢、
  被顶替的下层无法恢复、点歌队列被全服音乐卡住；队列推进 A→B 原子完成，不经过全服；
  `ActiveSession` 只表示逻辑会话（时间轴/来源/生命周期），不再持有每玩家实际播放；
  客户端加固为任一时刻至多一条 MUSIC 会话
- **v0.2.13**：DRAINING 终态改为“真实输出完成”判定——解码器 EOF 后 `PcmAudioStream` 不再无限补静音，
  环形缓冲喂完即返回真实 EOF，等 OpenAL 把已排队缓冲播完（源 stopped / MC release channel）才 FINISHED；
  排空 watchdog 不再计暂停时长（修复 >60s 暂停恢复被立即判结束）
- **v0.2.12**：彻底修复客户端暂停后恢复跳播——暂停/恢复一律只 `pause`/`unpause` OpenAL 通道，
  保留已排队音频，不再在恢复时按播放时钟重定位（墙钟会因解码饥饿补静音而缓慢漂移到实际音频之前，
  越到曲末漂移越大，重定位就跳过尚未听到的一段）
- **v0.2.11**：修复客户端暂停后恢复跳回的 bug——非排空恢复路径解码器先于重定位解冻导致帧竞态，
  现改为先停旧通道、再重定位、最后解冻解码器
- Phase 2.5：`uri` 直链白名单细化
- Phase 3 剩余：搜索、队列、歌词（等 MineUI 通用能力）
- Phase 4：WorldGuard Adapter、时间与天气条件、播放列表
- Phase 5：空间音频、多音源扩展（QQ/酷狗等）
