# MineAudio 配套流媒体客户端 Mod —— 需求说明

> 状态：需求草案（2026-09-18）。实现方案由后续设计文档确定。
> 背景：[初步设计文档](../初步设计文档) Phase 5；当前 Phase 2 的 MoeMusic 命令桥只是过渡。

## 1. 背景与目标

现状限制（已实测确认）：

- MoeMusic 的公开 Plugin API 只面向**在其内部运行的音源/扩展插件**，不向外部 Paper 插件暴露播放状态与控制
- 服务端插件是**全服共享队列**，无法 per-player / per-region 独立播放
- 播放时的旋转唱片 HUD 是**客户端本地渲染**，服务端无法移动、缩放、关闭；也无法并入计分板
- MoeMusic 自带界面（搜索/队列/歌词）是 mod 内置屏幕，无法与 MineUI 页面合并
- MineAudio 只能靠解析 `/music queue` 输出“猜”当前曲目，格式一变就失效

目标：开发 MineAudio 官方配套客户端 mod，作为 MineAudio 的**流媒体 Backend**，实现：

1. 服务端可控的流媒体播放（per-player / 全服 / 区域 / 发声点）
2. 播放状态**回传服务端**，计分板 / PAPI / MineUI 显示真实曲目与进度
3. HUD 由服务端配置（可关闭，只用计分板；可缩放/移位）
4. 与 MineUI 界面深度集成（真实进度、队列、搜索）
5. 服务端只发控制与曲目引用，**客户端直连音源 CDN**，服务端不代理音频

## 2. 角色与边界

| 角色 | 负责 |
| --- | --- |
| 服务端 MineAudio | WHEN / WHO / WHERE / WHAT：曲目解析（provider 适配）、会话仲裁、同步基准、权限与安全策略、状态缓存（计分板/UI） |
| 客户端 Mod | HOW：取流、解码、播放、缓冲、状态上报、可配置 HUD |
| MineUI Mod | 界面渲染（MineAudio 下发 JSON，本 mod 通过 MineAudio 提供数据） |

明确不做：

- 服务端代理/转发音频流
- DRM 绕过、账号登录抓取、下载后再分发
- 原版客户端支持（未装 mod 由服务端 fallback 到 PACK/NBS）
- 在客户端实现音源解析（音源改版不应要求玩家更新 mod）

## 3. 功能需求

### FR-1 能力握手
- 客户端连接后声明：协议版本、支持能力（seek / pause / multiSession / positional / lyrics / hud / cache）、支持的编码格式
- 服务端据此做选源与降级；能力位与 `AudioCapabilities` 对齐

### FR-2 播放控制
- 指令：play / stop / pause / resume / seek / volume / fadeIn / fadeOut
- 播放引用包含：`trackId`、`provider`、`source`、`id`、服务端解析出的**可播放 URL（可刷新）**、`startEpoch`、起始进度、音量、Bus、会话 ID
- URL 可能过期：客户端请求刷新（服务端重新 resolve 并下发）

### FR-3 同步播放
- 服务端保存 `startEpoch`，客户端按本地时钟计算进度，晚加入直接 seek
- 周期校时（PING/PONG，估算 RTT 与时钟偏移）；进度漂移超过阈值时静默 seek 校正
- 缓冲状态需上报；卡顿时不阻断其他会话

### FR-4 多会话与 Bus
- `MUSIC`：每玩家 1 条
- `AMBIENT`：可多层（受服务端 `max-ambient-layers` 限制）
- `SFX` / `UI`：不走流媒体（保持 PACK/Vanilla 低延迟）
- 每会话独立音量/暂停/seek；服务端可单独停止

### FR-5 状态回传
- 上报：会话 ID、曲目 ID、状态（buffering / playing / paused / stopped / error）、当前进度、缓冲比例、错误码
- 上报频率：状态变化立即 + 播放中周期（1~5s 可配）
- 服务端据此更新计分板 / PAPI / MineUI；**曲目元数据以服务端为准**

### FR-6 曲目解析与 URL 下发
- provider 适配（网易云 / QQ / 酷狗 / Bilibili / HTTP 等）在**服务端**实现（MineAudio adapter 或复用现有音源插件逻辑）
- 服务端负责：搜索、曲目信息、封面/歌词地址、URL 解析与刷新、内容过滤、限流
- 客户端只消费 URL；不接触账号、Cookie、Token

### FR-7 媒体防火墙
- 客户端只播放服务端下发的 URL，且校验：
  - 仅 HTTPS（可配置例外）
  - 主机白名单 / 黑名单
  - 拒绝私网/本机地址（SSRF/内网探测防护）
- 违规拒绝并上报错误；规则可由服务端下发、客户端本地可覆盖（更严格方向）

### FR-8 HUD（旋转唱片卡片）
- 服务端可下发默认配置：`enabled`、`anchor`（四角）、`offsetX/Y`、`scale`、`showCover`、`spinCover`、`showTitle/Artist/Progress/Lyrics`、背景与配色
- 客户端本地设置优先级高于服务端（隐私与个人偏好）
- **支持完全关闭**：只用服务端计分板/PAPI 显示（计分板文本无法渲染图片，HUD 与计分板二选一或共存）
- HUD 渲染不得与 MineUI HUD（未来）冲突，需预留互斥开关

### FR-9 MineUI 集成
- 服务端 MineAudio 已有 `com.mineui.api` 集成（`/mineaudio ui`）
- 本 mod 上线后，MineUI 页面可显示：真实曲目、歌手、进度条、缓冲、队列
- 页面按钮走 MineAudio 服务端动作（play/pause/seek/stop），不直连客户端
- 可选：搜索与队列（服务端 provider 搜索接口就绪后）
- 歌词（可选）：由服务端下发或客户端按服务端提供的 URL 拉取

### FR-10 封面与歌词
- 封面：服务端下发 URL 或字节；客户端缓存（有上限）
- 歌词：LRC/逐行，时间轴与服务端 `startEpoch` 对齐；来源随 provider

### FR-11 本地缓存
- 可配置开关与容量上限；LRU 清理
- 缓存仅本机播放用，禁止导出/再分发；退出时不得影响其他实例（单实例锁）

### FR-12 音频输出
- 解码与音频输出在独立线程，不阻塞游戏线程
- 音量与游戏音量关系可配（独立音量 / 跟随主音量）
- 设备切换、暂停游戏（单机菜单）时的行为需定义
- 与 Minecraft 原版声音、其他音频 mod 共存；参考 MoeMusic 的 OpenAL 实践（线程上下文绑定、断线清理）

### FR-13 位置音频（可选，后续阶段）
- 服务端可下发世界坐标与半径；客户端做距离衰减/立体声
- V1 可不做；设计时预留字段

### FR-14 回退与隔离
- 未装 mod / 能力不足的玩家：由服务端 fallback 到 PACK/NBS 或跳过，不报错
- 本 mod 崩溃/未连接不得影响 PACK/Vanilla/NBS 播放
- 服务端 provider 解析失败：单曲失败，不阻塞队列

### FR-15 调试
- 客户端日志分级；`/mineaudio debug` 能看到各玩家流媒体状态（连接、能力、会话、进度、错误）

## 4. 协议需求（服务端 ↔ 客户端）

- 通道：插件消息通道（如 `mineaudio:stream`），双方声明后使用；带协议版本
- 消息最小集：
  - `HELLO`（C→S）：协议版本、能力位、mod 版本、语言
  - `PLAY` / `STOP` / `PAUSE` / `RESUME` / `SEEK` / `VOLUME`（S→C）
  - `URL_REFRESH`（C→S 请求 / S→C 响应）
  - `STATE`（C→S）：状态与进度
  - `PING` / `PONG`：时钟同步
- 消息编码：JSON 或 protobuf（设计时定）；需向后兼容（未知字段忽略）
- 安全：不传输账号凭据；URL 视为敏感数据（不下发到其他玩家）；频率与大小限制
- 版本策略：协议版本独立于插件版本，破坏性变更升主版本

## 5. 非功能需求

- 平台：Fabric 为主（对齐 MineUI 客户端支持范围：1.20.1 / 1.21.1 / 26.2 起）；NeoForge 可选
- Java：与目标 MC 版本一致（26.2 为 Java 25）
- 性能：8~16 人同时播放时，客户端帧率与服务器 TPS 无明显影响
- 安全：只播服务端 URL + 防火墙；日志脱敏；不执行远程代码
- 兼容：与 MineUI mod、常用优化 mod 共存；不依赖资源包
- 可维护：音源适配全部在服务端，客户端尽量不随音源改版更新

## 6. 与现有 MineAudio 的接口

- 现有抽象已就绪：`StreamProvider` / `StreamBackend`（命令桥为 `MoeMusicProvider`）
- 新增 `MineAudioClientProvider implements StreamProvider`：协议实现，能力位 `multiSession=true`、`seek=true`、`synchronizedPlayback=true`、`positional`（后续）
- MineAudio 侧需要配合：
  - 协议服务端实现（握手、会话、URL resolve 回调、状态缓存）
  - PAPI/计分板读取真实状态（替换当前 `/music queue` 输出桥）
  - 能力探测改为 mod 通道握手
  - 旧 MoeMusic 命令桥保留为降级路径（可配置禁用）

## 7. 验收标准（场景）

1. 全服点播：装 mod 玩家同步误差 ≤ 100ms；晚进服 seek 到正确进度
2. 单玩家点播：仅该玩家可听；区域/发声点可按 scope 播放
3. 暂停 / 继续 / seek / 音量实时生效，且状态回传正确
4. 未装 mod 玩家走 fallback，服务端无报错
5. HUD 服务端默认关闭时无卡片；计分板显示真实标题、歌手、进度
6. 非白名单 URL 被客户端拒绝并上报
7. `/mineaudio ui` 显示真实曲目与进度，按钮可用
8. 断线重连、切维度、退出游戏无残留声音与缓存句柄
9. 8 人同时播放，客户端帧率/服务器 TPS 无明显波动

## 8. 待设计决策（交给配套 Mod 设计文档）

1. URL 解析放服务端还是客户端（推荐服务端；客户端只取流）
2. 协议编码（JSON / protobuf）与压缩
3. 音频实现选型（解码库、输出设备、是否复用 OpenAL）
4. 封面/歌词来源与缓存策略
5. HUD 与计分板分工：是否默认关 HUD、只保留计分板
6. 与 MoeMusic 的关系：并行共存还是逐步替代；迁移与开关策略
7. 多版本支持范围与发布方式（参考 MineUI 客户端安装包 `tools/build_client_kit.sh`）
8. 位置音频与 DSP（crossfade/EQ）是否纳入 V1

---

设计文档建议放 `docs/CLIENT_MOD_DESIGN.md`（或独立仓库），落地后 MineAudio 侧按第 6 节接入。
