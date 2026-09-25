# MineAudio 落地方案（V1）

> 依据：[初步设计文档](../初步设计文档) §69 / §77。
> 核心原则：MineAudio 负责 **WHEN / WHO / WHERE / WHAT**，Backend 负责 **HOW**。
>
> 实现状态（2026-09-19）：M0–M6 已完成并推送；M7 文档完成；Phase 2（流媒体命令桥）已实现并部署，
> Phase 3 界面部分（MineUI 音乐界面）已实现，客户端安装包见 tools/build_client_kit.sh。
> MineAudio Client（自研流媒体客户端，26.2）按 docs/DESIGN_MINEAUDIO_MOD.md 推进：
> Phase 0 服务端重构、Phase 1 协议/握手/状态缓存、Phase 2a 安全媒体基础、Phase 2b 本机媒体网关、
> Phase 2c 音频链路（LavaPlayer 48kHz 解码 + MC 声音引擎通道播放 + 会话控制/状态上报）已完成并推送，
> 2026-09-19 实机验证出声成功（teststream 直链）。
> 后续已完成：Phase 4 校时同步起播/定时控制、音量跟随游戏设置（总音量+Bus 档位）、
> Phase 5/6 播放路径接入 MediaFirewall + 本机网关 + MediaCache、播放音乐时压制原版背景音乐、
> `/mineaudio` 客户端控制（pause/resume/seek/volume）、MineUI 音乐界面收尾（进度条 / 定位 / 音量 /
> 解析状态与失败分类 / 错误 Toast / 键位 / “正在播放”HUD）、断线释放通道崩溃修复（客户端 0.1.10）。
> MoeMusic 兼容路径（命令桥 / Legacy 降级 / `/music queue` 展示解析）已于 2026-09-19 全部移除，
> 流媒体只走自研客户端；UI 由 MineUI 页面 + HUD 承载，解析失败按分类展示（不再依赖第三方插件）。
> 2026-09-19 追加：播放时钟状态机重构完成（client-core `PlaybackClock`，客户端 0.2.0）：
> LOADING/BUFFERING/PLAYING/PAUSED/DRAINING/FINISHED/ERROR 单入口迁移，听感位置只由时钟写。
> 2026-09-19 追加：共享时间轴（R8）落地——受众会话统一锚点（`Timeline`），
> 晚加入者在 HELLO 后按当前进度补播对齐；经确认采用“松散共享”：暂停/seek 保留 per-player，不做全服统一控制。
> 2026-09-19 追加：网易搜索 + 点歌队列落地（搜索=免签接口+批量详情补封面；队列每人上限 2 首、
'
'> 自然结束自动续播；UI 输入框/结果列表/队列管理；客户端无需更新）。
'
'> 2026-09-20 追加：修复客户端暂停后恢复跳回 bug——非排空恢复路径解码器先于重定位解冻导致帧竞态，
'> 现改为先停旧通道、再 relocate、最后解冻解码器；排空路径 wasDraining 判定提前防止状态竞变（客户端 0.2.11）。
'> 2026-09-20 追加：彻底修复尾部暂停恢复跳播——暂停/恢复一律只 pause/unpause OpenAL 通道保留已排队音频，
'> 不再按播放时钟重定位（墙钟因解码饥饿补静音而漂移到实际音频之前，曲末漂移最大导致跳过一段）；
'> PlaybackClock 新增 resumeFromOutput 直接回到 PLAYING/DRAINING 不经过 BUFFERING（客户端 0.2.12）。
'> 2026-09-20 追加：DRAINING 终态从“ring 估算 + 800ms”改为“真实输出完成”——`PcmAudioStream`
'> 在解码器 EOF 后排空 ring 即返回真实 EOF，等 OpenAL 播完已排队缓冲（源 stopped / MC release channel）
'> 才 FINISHED；watchdog 不计暂停时长（客户端 0.2.13）。这是曲尾被提前 FINISHED 后服务端 STOP 掉尾部的直接修复。
'> 2026-09-20 追加：引入每玩家 Music Arbiter（`MusicLayer`/`MusicIntent`/`MusicArbiter`，纯 Java 可单测）：
'> MUSIC 各来源（个人/受众/区域/世界）只声明 intent，`reconcileMusic` 唯一实际播放入口，
'> 优先级 PERSONAL > AUDIENCE > REGION > WORLD；修复全服/个人互相顶替、refresh 反抢、下层无法恢复、
'> 队列被全服卡住；队列 A→B 原子推进；ActiveSession 只表示逻辑会话；客户端至多一条 MUSIC 不变量（0.3.0）。
'> 后续：P2 集成场景测试；MineUNO/MineChess 接入。
'> 2026-09-20 追加：统一播放语义——点歌/曲库“点歌”进全服队列并按共享时间轴全服播放；搜索“自己”/曲库“自己”
'> 为 PERSONAL 临时覆盖，结束/停止后自动回到全服进度；停止按钮区分（自己中→只停自己回全服，全服中→停全服清队列）；
'> 全服曲目被自己遮挡期间越过时长则切下一首（0.3.2）。
'> 2026-09-24 追加：客户端本地曲库 M1（0.4.0）——固定目录 `config/mineaudio/library/` 扫描，
'> jaudiotagger 解析标题/歌手/专辑/时长与嵌入封面（导出 .covers），按大小+修改时间缓存索引、按 sha256 去重，
'> MineUI 本地绑定 `{local.mineaudio.library}` + `local:mineaudio.lib_play/lib_refresh/lib_delete` 本地试听；
'> 点歌到全服的文件上传/分发为 M2（服务器临时缓存托管）。
'> 2026-09-24 追加：本地曲库支持网易云 .ncm（客户端内置解密 AES+keyBox，缓存到 .decoded）、
'> 同名 .lrc 关联存储（歌词后续显示）；NCM 解码器纯 Java 可单测（0.4.1）。
'> 2026-09-24 追加：本地曲库 UI 因 MineUI `list.items` 仅支持 `{state.*}`（不支持 local 列表），
'> 改为固定 8 插槽 + 本地标量绑定 `{local.mineaudio.libN_*}` + 翻页动作（0.4.2）。
'> 歌词能力：MineUI 目前仅有通用 `list` 组件（`/mineui lyrics` 为演示），无音频同步歌词能力，MineAudio 侧未接入。
'> 2026-09-24 追加：修复真实 .ncm 解析——meta base64 含空白，Java 严格解码器报 Illegal base64 character 20，
'> 改用 MIME 解码器；单文件失败不再中断整体扫描，曲库提示显示首个失败原因（0.4.3）。
'
'> 待办：歌词（等 MineUI 通用能力）、MineUNO/MineChess 接入、正式版客户端包。
> Phase 8（D 方案）已完成第一版：`StreamResolver` 边界 + `DirectUrlResolver` + `NeteaseEapiResolver`
> （最小 eapi，仅 song/enhance/player/url/v1）+ `ResolutionCache`（TTL + 同曲合并）+ 失败分类 +
> 解析失败上报分类错误；凭证（MUSIC_U）仅从环境变量 `MINEAUDIO_NETEASE_MUSIC_U` 读取，
> 不落配置、不进日志、不下发客户端。无凭证时匿名尝试，失败即降级。

## 1. V1 范围

**做：**

- Backend：PACK（资源包 OGG）、Vanilla、NBS（NoteBlockAPI 1.7.0）
- Scope：PLAYER / GLOBAL / WORLD / REGION / EMITTER
- Bus：MUSIC / AMBIENT / SFX / UI
- Region：Cuboid / Sphere、优先级叠加、边界迟滞、chunk 索引
- Emitter：绑定世界坐标、位置声、红石触发
- Track / Cue Registry、Primary + Fallback（资源包未加载 / NoteBlockAPI 未安装时降级）
- Java API（`com.mineaudio.api`）、`/mineaudio` 命令与 `/mineaudio debug`
- PackHost 资源包接入（复用现有 `plugins/PackHost/packs/` 机制）
- 单测（区域仲裁、迟滞、形状判定、配置解析、fallback 选择）

**不做（后续阶段）：**

- 流媒体（MoeMusic / Concerto Adapter、Stream Track、同步进度）→ Phase 2
- MineUI 播放界面 / HUD → Phase 3
- WorldGuard、时间/天气条件、播放列表、Shuffle → Phase 4
- MineAudio 客户端 mod（真正的 per-player stream / 空间音频）→ Phase 5
- PackHost 独立仓库与 `PackHostApi` → 独立小项目（跨仓库，见 §11）
- MineUNO / MineChess 接入（迁移 `playSound` 到 MineAudio API）→ 已确认放 V1 之后

## 2. 技术基线

| 项 | 选择 | 说明 |
| --- | --- | --- |
| Java | 25 | 与 mineUI / mineChess 一致 |
| Paper API | `26.2.build.124-stable` | 与线上 `paper-26.2-124.jar` 一致 |
| 构建 | Gradle 多模块 | 设计文档 §42 的 `compileOnly(project(":mineaudio-api"))`；根目录 `settings.gradle` / `build.gradle` / `gradle.properties` |
| 插件 jar | `MineAudio-0.1.0.jar` | 内嵌 api 类（同 mineUI 内嵌 protocol 的做法），业务插件运行时不额外带依赖 |
| API 分发 | `./gradlew :mineaudio-api:publishToMavenLocal` | MineUNO / MineChess（Maven）以 `provided` scope 引用，配 `MineAudioProvider.get()` 软集成 |
| NBS | `compileOnly com.github.koca2000:NoteBlockAPI:1.7.0`（JitPack） | 运行时 softdepend；服务器不装则 NBS Backend 不可用，其余 Backend 不受影响 |
| 测试 | JUnit 5 | 纯逻辑，不依赖 Bukkit 服务器 |
| 资源包 | `pack/` + `tools/gen_pack.py` | `pack.mcmeta` 用 26.2 的 `min_format/max_format: 88`（与 mineChess 一致） |

## 3. 模块与工程结构

```text
mineAudio/
├── settings.gradle              # include mineaudio-api / mineaudio-paper
├── build.gradle                 # group com.mineaudio, version 0.1.0
├── gradle.properties            # paper_version=26.2.build.124-stable
├── gradlew / gradle/
│
├── mineaudio-api/               # 仅编译期依赖 paper-api + adventure，稳定业务 API
│   └── com.mineaudio.api
│       ├── MineAudio.java           MineAudioProvider.java
│       ├── AudioBus.java            Audience.java
│       ├── AudioTrack.java          AudioCue.java
│       ├── AudioSource.java         PlaybackOptions.java
│       ├── PlaybackHandle.java      PlaybackState.java
│       ├── AudioCapabilities.java   AudioMetadata.java
│       └── event/                   6 个事件
│
├── mineaudio-paper/             # com.mineaudio 插件实现
│   ├── MineAudioPlugin.java
│   ├── track/       TrackRegistry / CueRegistry / TrackParser
│   ├── playback/    AudioOrchestrator / PlaybackSession / PlayerAudioState
│   ├── backend/     AudioBackend / SoundBackend（PACK+Vanilla 共用）/ NbsBackend
│   ├── region/      AudioRegion / RegionShape / RegionManager / SpatialIndex
│   ├── emitter/     AudioEmitter / EmitterManager
│   ├── listener/    PlayerConnectionListener / PackStatusListener / InteractListener
│   ├── command/     AudioCommand
│   └── integration/ NoteBlockIntegration（Phase 2 再加 MoeMusic / Concerto）
│
├── pack/                        # assets/mineaudio/...（OGG + sounds.json）
├── nbs/                         # 示例/业务 NBS 文件，部署到 plugins/MineAudio/nbs/
├── tools/gen_pack.py            # pack/ -> pack/out/mineaudio.zip
├── deploy.sh                    # 构建 + 部署 jar / 资源包 / NBS
└── docs/PLAN.md
```

## 4. API 形状（V1 冻结）

```java
package com.mineaudio.api;

public interface MineAudio {
    PlaybackHandle play(Audience audience, Key track);
    PlaybackHandle play(Audience audience, Key track, PlaybackOptions options);
    void playSfx(Player player, Key cue);
    void playSfxAt(Location location, Key cue);        // 位置声，附近玩家可听
    void stop(Audience audience, AudioBus bus);
    void stopAll(Audience audience);

    boolean hasTrack(Key track);
    AudioCapabilities capabilities(Player player);

    // 业务插件注册自己的 Cue，随插件 disable 自动注销
    void registerCue(Plugin owner, AudioCue cue);
    void unregisterCues(Plugin owner);
}
```

- `MineAudioProvider`：与 `MineUiProvider` 同款服务定位器，未安装时为 `null`，业务插件按既有模式回退原版实现。
- `Key` 直接用 Adventure `net.kyori.adventure.key.Key`，不另造类型。
- `Audience`：`player(Player)` / `players(...)` / `world(World)` / `global()`，播放开始时解析为玩家集合；会话记录 audience 描述，玩家进服/换世界时自动补播。
- `PlaybackHandle`：`id()`、`state()`、`stop()`、`pause()`、`resume()`、`seek(Duration)`；不支持的 Backend 返回 false 并保持 capability 语义（API 不撒谎）。
- `PlaybackOptions`：volume / pitch / loop / fadeInMs / fadeOutMs（builder）。
- 事件（Bukkit Event）：`AudioPlayEvent`（可取消）、`AudioStopEvent`、`TrackStartedEvent`、`TrackFinishedEvent`、`AudioRegionEnterEvent`、`AudioRegionLeaveEvent`、`AudioEmitterStartEvent`。

## 5. 配置与数据模型

`config.yml`

```yaml
region-check-interval-ticks: 10
region-enter-delay-ms: 300      # 边界迟滞
region-exit-delay-ms: 500
max-ambient-layers: 3
emitter-poll-interval-ticks: 5
pack:
  assume-loaded: true           # 非 PackHost 场景默认视为已加载；收到失败事件后置否
debug: false
```

`tracks.yml`

```yaml
tracks:
  spawn:
    type: PACK                  # PACK | VANILLA | NBS
    bus: MUSIC
    sound: mineaudio:music.spawn
    duration: 180000            # PACK/VANILLA 循环需要
    loop: true
    volume: 0.7
    fallback:
      type: VANILLA
      sound: minecraft:music.overworld.forest
  tavern:
    type: NBS
    bus: MUSIC
    file: tavern.nbs
    loop: true
```

`cues.yml`（服务端集中定义；业务插件也可用 `registerCue` 自带）

```yaml
cues:
  mineuno:card.play:
    primary:  { type: PACK, sound: mineuno:card.play }
    fallback: { type: VANILLA, sound: minecraft:item.book.page_turn }
```

`regions.yml`

```yaml
worlds:                          # 世界层，priority = 0
  world:
    music: spawn
    ambient: [birds]

regions:
  tavern:
    world: world
    shape:
      type: CUBOID               # CUBOID | SPHERE
      min: { x: 100, y: 60, z: -40 }
      max: { x: 150, y: 80, z: -10 }
    priority: 20
    music: tavern
    ambient: [fireplace, rain]
    fade-in-ms: 1000
    fade-out-ms: 1000
```

`emitters.yml`

```yaml
emitters:
  tavern_radio:
    world: world
    x: 135
    y: 65
    z: -21
    track: tavern
    radius: 24
    trigger: REDSTONE           # ALWAYS | REDSTONE | COMMAND | INTERACT
    loop: true
```

约定：权威数据在 YAML，方块不依赖 Block PDC；命令里的增删改写回对应文件。

## 6. 运行时实现要点

### 6.1 Backend 与能力

| Backend | 播放方式 | 位置声 | 循环 | 暂停/Seek | 备注 |
| --- | --- | --- | --- | --- | --- |
| PackBackend | Adventure `Sound` + `player.playSound` / `world.playSound(location)` | ✓（Mono OGG + attenuation_distance） | 按 duration 调度重播 | ✗ | 停止按 sound key，不按 SoundCategory，避免误停原版声音 |
| VanillaBackend | 同上，`minecraft:` 原版声音 | ✓ | 一般不循环 | ✗ | 兼作 fallback 与纯原版 Track |
| NbsBackend | NoteBlockAPI `RadioSongPlayer` / `PositionSongPlayer` | ✓（distance） | ✓ | ✓ | 未安装 NoteBlockAPI 时不可用 |

- `AudioBackend`：`id()` / `available()` / `capabilities()` / `play(...)` / `playAt(...)`。
- Fade：PACK / Vanilla 无法对已播放的 playsound 连续调音量，按设计文档 §35 退化为直接停止，capability 里如实标注；NBS 提供音量能力（V1 先不做渐变曲线）。
- 找不到 sound key 时客户端静默忽略，因此 PACK Track 的可用性判断只依赖“玩家资源包是否加载”和 fallback 配置。

### 6.2 播放仲裁（AudioOrchestrator）

- 每玩家 `PlayerAudioState`：MUSIC 单会话、AMBIENT 多层（≤ `max-ambient-layers`）、SFX/UI 不落表。
- 优先级：**API 显式点播（PLAYER）> Region 栈最高 priority > 世界层**。Region 离开后回落到下一层（酒馆 → 出生点 → 世界 BGM）。
- `play` 返回的 `PlaybackHandle` 可整体停止该 Audience 的所有会话；`stop(audience, bus)` 只停对应 Bus。
- 会话记录 audience：GLOBAL / WORLD 音乐对新进服/新到世界的玩家补播（PACK 从头发起，符合 §33 的 SYNC_START、不承诺 LATE_JOIN_SEEK）。

### 6.3 Region

- 加载时建立 `世界 -> chunk -> Region[]` 索引（Cuboid 按相交 chunk，Sphere 按包围盒 chunk）。
- 每 `region-check-interval-ticks` 检查玩家所在 chunk 及相邻 chunk 的候选区域，再精确判定形状。
- 进入/离开各带 tick 迟滞（默认 6 / 10 tick），防止边界抖动切歌。
- 选择逻辑抽成纯函数（不依赖 Bukkit），便于单测覆盖重叠优先级、迟滞、ambient 层数截断。

### 6.4 Emitter

- 模型：id、世界坐标、半径、track、trigger、启用状态。
- 触发：`ALWAYS` 随插件启用；`REDSTONE` 每 `emitter-poll-interval-ticks` 轮询 `block.isBlockPowered()` 启停；`COMMAND` 由命令启停；`INTERACT` 监听右键绑定的方块。
- PACK / Vanilla 位置声用 `world.playSound(location, ...)`（附近玩家按距离衰减）；NBS 用 `PositionSongPlayer` + distance。
- 半径对 PACK 声音是 best-effort：以 volume/attenuation_distance 近似，文档里如实说明。

### 6.5 Fallback 与客户端能力

- 每玩家记录 `PACK_AVAILABLE`：`PlayerResourcePackStatusEvent` + Paper `player.hasResourcePack()`，默认值取 `pack.assume-loaded`。
- 解析顺序：primary → 玩家能力/backend 可用 → fallback → 都不行则跳过并 debug 记录一次，不报错、不影响游戏。
- NoteBlockAPI / 未来的 MoeMusic / Concerto 均按 §67 做故障隔离：缺失只影响对应 Backend。

## 7. 资源包与 NBS 部署

- `pack/` 产出 `mineaudio.zip`，`deploy.sh` 复制到 `plugins/PackHost/packs/`；MineAudio **不开** HTTP 端口。
- 长音乐在 `sounds.json` 标 `"stream": true`；需要 3D 衰减的音效用 mono OGG（README 里写明）。
- V1 先放 1~2 个示例音效/音乐用于联调（可用 ffmpeg 生成占位 OGG），业务曲目后续补。
- NBS 文件部署到 `plugins/MineAudio/nbs/`。

## 8. 命令与权限

`/mineaudio`（避免与 MoeMusic / Concerto 的 `/music` 冲突），权限 `mineaudio.admin` 默认 op：

```text
/mineaudio play <track> [self|player <玩家>|world <世界>|global]
/mineaudio stop [bus]
/mineaudio region pos1|pos2|create <id>|sphere <半径>|settrack <id> <track>|
              setambient <id> <track...>|delete <id>|list
/mineaudio emitter create <id>|bind <id>|settrack <id> <track>|settrigger <id> <trigger>|
                 setradius <id> <r>|start <id>|stop <id>|delete <id>|list
/mineaudio reload
/mineaudio debug [玩家]
```

区域编辑用 `/mineaudio region pos1|pos2` 选两点后 `create`，避免依赖 WorldEdit；命令改动写回 YAML（YamlConfiguration 会丢注释，文件头保留说明文字即可）。

## 9. 里程碑

| 阶段 | 内容 | 产出/验证 |
| --- | --- | --- |
| M0 骨架 | Gradle 多模块、plugin.yml、主类、配置加载、`/mineaudio debug` 占位、deploy.sh | `./gradlew build` 通过，插件能在服务器 enable |
| M1 Registry | api 接口与事件、tracks/cues 解析、Cue 运行时注册 | 单测：解析、Cue 查询 |
| M2 基础播放 | Orchestrator、PlayerAudioState、PACK/Vanilla Backend、fallback、`/mineaudio play/stop` | 游戏内点播/停止、资源包未加载走 fallback |
| M3 NBS | NbsBackend、NoteBlockAPI 软依赖、循环/暂停、位置播放 | 无 NoteBlockAPI 时功能不受影响 |
| M4 Region | 形状、chunk 索引、迟滞、优先级、世界层、ambient 层数、region 命令持久化 | 单测 + 游戏内重叠区域进出验证 |
| M5 Emitter | 模型、ALWAYS/REDSTONE/COMMAND/INTERACT、位置声、emitter 命令持久化 | 红石开关验证、距离衰减 |
| M6 资源包 | pack/、gen_pack.py、示例音频、deploy.sh 联动 PackHost | 客户端听到自定义音效 |
| M7 收尾 | README（命令/配置/素材规范）、V1 验收走查 | §77 清单（业务接入除外）逐项通过 |

实际实现说明：
- `PackBackend` 与 `VanillaBackend` 播放机制一致，合并为 `SoundBackend`，PACK 的可用性判断由 Orchestrator 选源时处理
- Emitter 触发 V1 支持 `ALWAYS / REDSTONE / COMMAND / INTERACT`；`PROXIMITY` 解析时降级为 `ALWAYS` 并告警，留待 Phase 4
- World BGM 采用 `regions.yml` 的 `worlds:` 段（已确认）

## 9.5 Phase 2 实现说明（流媒体；MoeMusic 兼容路径已移除）

> 2026-09-19 更新：本节所述 MoeMusic 命令桥 / Legacy 降级 / NowPlaying 解析已全部删除，
> 仅保留历史记录；现行实现见 §9.7 与 docs/DESIGN_MINEAUDIO_MOD.md。

调研结论（2026-09-18）：MoeMusic 的公开 Plugin API（`org.lolicode.moemusic:api`，maven.lolicode.org）
面向**在 MoeMusic 内部运行的音源/扩展插件**，不提供外部 Paper 插件控制播放的 API；
服务端播放由独立的 Spigot/Paper 插件（`MoeMusic-Minecraft` 的 `spigot` 分支）以**全服共享队列**管理。
因此 MineAudio 采用设计文档预留的“命令 / 协议桥”方案：

- `StreamBackend` + `StreamProvider` 抽象；V1 实现 `MoeMusicProvider`，把 `Stream` 曲目翻译为
  `/music addById <source> <id> --now`、`/music stop|pause|resume`（控制台执行，绕过玩家权限）
- `uri` 直链默认禁用，开启后仍受 `stream.allowed-hosts` 白名单和 MoeMusic 自身媒体防火墙约束
- 能力如实上报：`synchronizedPlayback=true`，`multiSession=false`（共享队列），`seek=false`、位置声/歌词不支持；
  因此 `STREAM` 只接受 `global` 受众
- 客户端能力探测：MoeMusic 客户端注册 `moemusic:client_handshake` 插件通道，
  用 `Player#getListeningPluginChannels()` 判断；未装客户端且有 fallback 的玩家走 PACK/NBS 降级
- 同一曲目按 `track.id` 去重：多个玩家会话复用同一共享队列句柄；旧句柄停止不会误停新曲
- Concerto 与真正的 per-player 流播放留待 Phase 2.5 / Phase 5

## 9.6 Phase 3 实现说明（MineUI 音乐界面）

- 页面 JSON 随插件 jar 发布：`assets/mineaudio/ui/mineaudio/player.json`，`/mineaudio ui` 打开
- 通过 `MineUiHook` 反射加载 `integration/MineUiIntegration`，未装 MineUI 或 API 不匹配时退化为
  Noop + 聊天提示（与 MineChess / MineSkin 的集成模式一致，`compileOnly` MineUI API）
- 服务端权威状态：打开时 `snapshot()`，之后每秒（刷新任务）与每次操作后推送增量
- 已实现：当前播放（标题/作者/状态/Backend/来源）、暂停/继续/停止、±15s 定位、音量 ±10%、
  HUD 开关、停止环境音、曲目列表自己/全服点播、流媒体客户端能力提示、进度条（客户端插值）、
  解析状态与失败分类（`StatusAware`）、错误 Toast、键位（槽位1 打开界面 / 槽位2 切换 HUD）
- “正在播放”HUD：`assets/mineaudio/ui/mineaudio/hud.json`，`/mineaudio hud` 切换（MineUI 0.8+）
- 未实现（依赖 MoeMusic 对外能力）：搜索、队列、歌词
- 客户端安装包：`tools/build_client_kit.sh` 从 Modrinth 解析 Fabric 26.2 版本，
  打包 MineUI 客户端 + MoeMusic + Bad Packets + Fabric Language Kotlin + Fabric API +
  Cloth Config/Mod Menu（可选）+ Fabric 安装器与中文安装说明
- 命令统一为 `/mineaudio`（与 /mineui、/mineskin 等风格一致），不再使用 `/audio`
- 计分板：注册 PlaceholderAPI 扩展（`%mineaudio:nowplaying%` / `title` / `artist` / `playing` / `stream`），
  本服 TAB 侧边栏已用它替换原 TPS 与两行指令；MoeMusic 自带界面（M 键）无法并入 MineUI 页面
- MoeMusic 不对外暴露播放状态：用带捕获的 CommandSender 执行 `/music queue` 并解析输出作为
  best-effort 补充（`MoeMusicNowPlaying`），MineAudio 未发起的播放也能显示；格式变化时退化为“未在播放”
- MoeMusic 的左上角旋转唱片 HUD 是客户端本地渲染，服务端无法移动/缩放；玩家可在
  M → 设置或 `[client.now_playing_hud]` 中调小/移位/关闭（安装说明已写明）
- Phase 5（定制流媒体客户端 mod）需求已单独整理：[CLIENT_MOD_REQUIREMENTS.md](CLIENT_MOD_REQUIREMENTS.md)，
  用于突破 MoeMusic 的状态/控制/HUD 封闭限制；设计文档由后续确定
- UI 能力（HUD / 远程图片 / 进度条 / 键位 / 高亮列表等）拆为 MineUI 通用需求：
  [MINEUI_REQUIREMENTS.md](MINEUI_REQUIREMENTS.md)，MineAudio 只负责下发状态与动作

V1 之后的独立事项（本次不做）：MineUNO / MineChess 接入；PackHost 独立化与 `PackHostApi`；汇总仓库登记 mineAudio submodule。

## 10. 验收方式（对照 §77）

1. `./gradlew build`（含单测）全绿。
2. 服务器实测：PACK / Vanilla / NBS 三种 Track 对单玩家、全服、世界、区域播放与停止。
3. 两个重叠 Cuboid + 一个 Sphere：进出时按 priority 切换，边界来回走不抖。
4. 红石 Emitter 开关正常启停，位置声有距离衰减。
5. 资源包未加载 / NoteBlockAPI 未安装时走 fallback 或静默跳过，无报错。
6. `/mineaudio debug` 输出玩家当前各 Bus、Region 栈、选中 Track、Backend 与能力状态。
7. MineUNO / MineChess 接入不在 V1 范围，等 API 稳定后单独进行与验收（见 §9 尾注）。

## 11. 跨仓库事项（动手前需确认）

1. **PackHost**：V1 不改 mineUNO/PackHost，只把 `mineaudio.zip` 放进其 `packs/`；设计文档 §49–50 的独立仓库 + `PackHostApi` 是独立小项目，建议 V1 之后再做。
2. **MineUNO / MineChess 接入（已确认放 V1 之后）**：V1 期间不改这两个仓库；等 API 冻结并实测稳定后再分别接入（加编译期依赖 + softdepend + 替换 playSound），每个仓库一个聚焦提交。
3. **NoteBlockAPI 1.7.0**：需安装到服务器 `plugins/`（纯库插件，无配置无命令）；不装则 NBS 不可用。
4. **汇总仓库**：把 mineAudio 注册为 submodule，并更新 `README.md` 插件一览与 `AGENTS.md` 项目索引。

## 12. 待确认的决策

1. **World BGM 配置形态**：本方案用 `regions.yml` 的 `worlds:` 段（priority 0）；也可以强制用覆盖全世界的 CUBOID Region。倾向 `worlds:`，语义清晰。
2. **Cue 注册方式**：本方案同时支持 `cues.yml` 与 `registerCue`（业务插件自带、随插件注销）。若希望 V1 更小，可以只留 `cues.yml`。
3. **Region 编辑命令**：本方案用 `pos1/pos2/create`（不依赖 WorldEdit）；Sphere 用 `/mineaudio region sphere <半径>` 以玩家位置为中心。
4. **流媒体 Adapter 的启动时机**：V1 完成后是否马上做 MoeMusic Adapter（Phase 2）。业务接入已确认放后，届时可直接评估。
5. **API 版本策略**：V1 先与插件同版本号（0.1.0）；是否现在就把 `mineaudio-api` 独立发版/独立仓库，倾向暂不，等第二个消费者接入后再拆。

## M2 本地曲库上传分发（进行中，2026-09-25）

目标：客户端本地曲库点歌到全服时，把音频与封面上传到服务器，服务器临时托管并下发给其他玩家；
封面随 PLAY 下发，其他客户端显示（需把 MineAudio 托管主机加入 MineUI `remote-images.allowed-domains`）。

- 已实现（0.5.1）：服务端 `LibraryHost`（JDK HttpServer）
  - `POST /mineaudio/upload?token=&kind=audio|cover&ext=`：令牌校验、按内容 sha256 去重、落盘 `plugins/MineAudio/library/`
  - `GET /mineaudio/media/<sha256>.<ext>`：支持 Range/断点，含正确 Content-Type
  - 定时清理（`library.retention-hours`），单文件上限 `library.max-file-mb`
  - 配置：`library.enabled/public-url/bind/port/token/retention-hours/max-file-mb`
- 待实现：
  1. 协议：`LIBRARY_TICKET`（服务端下发上传令牌与上传基址）、`LIBRARY_ADD`（客户端上报 audioId/coverId/元数据）
  2. 客户端：`lib_queue` 触发上传（后台线程，含封面），再发 `LIBRARY_ADD`
  3. 服务端：`LIBRARY_ADD` → 动态 Track（`AudioSource.Stream("mineaudio","library",audioId)`）→ 全服队列；`coverUrl` 指向托管封面
  4. 解析器：把 `library` 源的 id 映射为 `publicBase/mineaudio/media/<id>.<ext>`
  5. 客户端 MediaFirewall：对“服务端自托管主机”放行（当前默认 https-only + 禁私网）
  6. MineUI：本地曲库封面显示（FR-19，见 docs/MINEUI_REQUIREMENTS.md）
