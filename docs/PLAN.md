# MineAudio 落地方案（V1）

> 依据：[初步设计文档](../初步设计文档) §69 / §77。
> 核心原则：MineAudio 负责 **WHEN / WHO / WHERE / WHAT**，Backend 负责 **HOW**。

## 1. V1 范围

**做：**

- Backend：PACK（资源包 OGG）、Vanilla、NBS（NoteBlockAPI 1.7.0）
- Scope：PLAYER / GLOBAL / WORLD / REGION / EMITTER
- Bus：MUSIC / AMBIENT / SFX / UI
- Region：Cuboid / Sphere、优先级叠加、边界迟滞、chunk 索引
- Emitter：绑定世界坐标、位置声、红石触发
- Track / Cue Registry、Primary + Fallback（资源包未加载 / NoteBlockAPI 未安装时降级）
- Java API（`com.mineaudio.api`）、`/audio` 命令与 `/audio debug`
- PackHost 资源包接入（复用现有 `plugins/PackHost/packs/` 机制）
- 单测（区域仲裁、迟滞、形状判定、配置解析、fallback 选择）

**不做（后续阶段）：**

- 流媒体（MoeMusic / Concerto Adapter、Stream Track、同步进度）→ Phase 2
- MineUI 播放界面 / HUD → Phase 3
- WorldGuard、时间/天气条件、播放列表、Shuffle → Phase 4
- MineAudio 客户端 mod（真正的 per-player stream / 空间音频）→ Phase 5
- PackHost 独立仓库与 `PackHostApi` → 独立小项目（跨仓库，见 §11）

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
│   ├── backend/     AudioBackend / PackBackend / VanillaBackend / NbsBackend
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

`/audio`（避免与 MoeMusic / Concerto 的 `/music` 冲突），权限 `mineaudio.admin` 默认 op：

```text
/audio play <track> [self|player <玩家>|world <世界>|global]
/audio stop [bus]
/audio region pos1|pos2|create <id>|sphere <半径>|settrack <id> <track>|
              setambient <id> <track...>|delete <id>|list
/audio emitter create <id>|bind <id>|settrack <id> <track>|settrigger <id> <trigger>|
                 setradius <id> <r>|start <id>|stop <id>|delete <id>|list
/audio reload
/audio debug [玩家]
```

区域编辑用 `/audio region pos1|pos2` 选两点后 `create`，避免依赖 WorldEdit；命令改动写回 YAML（YamlConfiguration 会丢注释，文件头保留说明文字即可）。

## 9. 里程碑

| 阶段 | 内容 | 产出/验证 |
| --- | --- | --- |
| M0 骨架 | Gradle 多模块、plugin.yml、主类、配置加载、`/audio debug` 占位、deploy.sh | `./gradlew build` 通过，插件能在服务器 enable |
| M1 Registry | api 接口与事件、tracks/cues 解析、Cue 运行时注册 | 单测：解析、Cue 查询 |
| M2 基础播放 | Orchestrator、PlayerAudioState、PACK/Vanilla Backend、fallback、`/audio play/stop` | 游戏内点播/停止、资源包未加载走 fallback |
| M3 NBS | NbsBackend、NoteBlockAPI 软依赖、循环/暂停、位置播放 | 无 NoteBlockAPI 时功能不受影响 |
| M4 Region | 形状、chunk 索引、迟滞、优先级、世界层、ambient 层数、region 命令持久化 | 单测 + 游戏内重叠区域进出验证 |
| M5 Emitter | 模型、ALWAYS/REDSTONE/COMMAND/INTERACT、位置声、emitter 命令持久化 | 红石开关验证、距离衰减 |
| M6 资源包 | pack/、gen_pack.py、示例音频、deploy.sh 联动 PackHost | 客户端听到自定义音效 |
| M7 业务接入 | MineUNO / MineChess 迁移 `playSound` 到 MineAudio API（跨仓库，先确认） | 各仓库单独提交验证 |
| M8 收尾 | README（命令/配置/素材规范）、V1 验收走查、汇总仓库登记 | §77 清单逐项通过 |

## 10. 验收方式（对照 §77）

1. `./gradlew build`（含单测）全绿。
2. 服务器实测：PACK / Vanilla / NBS 三种 Track 对单玩家、全服、世界、区域播放与停止。
3. 两个重叠 Cuboid + 一个 Sphere：进出时按 priority 切换，边界来回走不抖。
4. 红石 Emitter 开关正常启停，位置声有距离衰减。
5. 资源包未加载 / NoteBlockAPI 未安装时走 fallback 或静默跳过，无报错。
6. `/audio debug` 输出玩家当前各 Bus、Region 栈、选中 Track、Backend 与能力状态。
7. MineUNO / MineChess 接入后旧 `player.playSound` 调用被 API 取代（各自仓库验收）。

## 11. 跨仓库事项（动手前需确认）

1. **PackHost**：V1 不改 mineUNO/PackHost，只把 `mineaudio.zip` 放进其 `packs/`；设计文档 §49–50 的独立仓库 + `PackHostApi` 是独立小项目，建议 V1 之后再做。
2. **MineUNO / MineChess 接入**：需分别修改两个仓库（加 `mineaudio-api` 依赖 + softdepend + 替换 playSound），按 AGENTS.md 需事后确认再动；计划在 API 冻结后每个仓库一个聚焦提交。
3. **NoteBlockAPI 1.7.0**：需安装到服务器 `plugins/`（纯库插件，无配置无命令）；不装则 NBS 不可用。
4. **汇总仓库**：把 mineAudio 注册为 submodule，并更新 `README.md` 插件一览与 `AGENTS.md` 项目索引。

## 12. 待确认的决策

1. **World BGM 配置形态**：本方案用 `regions.yml` 的 `worlds:` 段（priority 0）；也可以强制用覆盖全世界的 CUBOID Region。倾向 `worlds:`，语义清晰。
2. **Cue 注册方式**：本方案同时支持 `cues.yml` 与 `registerCue`（业务插件自带、随插件注销）。若希望 V1 更小，可以只留 `cues.yml`。
3. **Region 编辑命令**：本方案用 `pos1/pos2/create`（不依赖 WorldEdit）；Sphere 用 `/audio region sphere <半径>` 以玩家位置为中心。
4. **流媒体 Adapter 的启动时机**：V1 完成后是否马上做 MoeMusic Adapter（Phase 2），还是先把 MineUNO/MineChess 接入跑稳。
5. **API 版本策略**：V1 先与插件同版本号（0.1.0）；是否现在就把 `mineaudio-api` 独立发版/独立仓库，倾向暂不，等第二个消费者接入后再拆。
