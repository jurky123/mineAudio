# MineAudio 配套流媒体客户端 Mod —— 需求说明

> 状态：需求草案（2026-09-18）。实现方案由后续设计文档确定。
> 关联：UI 与 HUD 需求已拆分到 [MineUI 新增能力需求](MINEUI_REQUIREMENTS.md)；
> 本 mod 只负责音频播放与状态上报，不渲染任何界面。

## 1. 背景与目标

现状限制（已实测确认）：

- MoeMusic 的公开 Plugin API 只面向**在其内部运行的音源/扩展插件**，不向外部 Paper 插件暴露播放状态与控制
- MoeMusic 服务端是**全服共享队列**，无法 per-player / per-region 独立播放
- MineAudio 只能靠解析 `/music queue` 输出“猜”当前曲目，格式一变就失效

目标：开发 MineAudio 官方配套客户端 mod，作为 MineAudio 的**流媒体 Backend**：

1. 服务端可控的流媒体播放（per-player / 全服 / 区域 / 发声点）
2. 播放状态**回传服务端**，供计分板 / PAPI / MineUI 显示真实曲目与进度
3. 服务端只发控制与曲目引用，**客户端直连音源 CDN**，服务端不代理音频
4. UI 与 HUD 全部交给 MineUI（见关联文档），本 mod 不实现界面

## 2. 角色与边界

| 角色 | 负责 |
| --- | --- |
| 服务端 MineAudio | WHEN / WHO / WHERE / WHAT：曲目解析（provider 适配）、会话仲裁、同步基准、权限与安全策略、状态缓存（计分板/UI） |
| 客户端 Mod | HOW：取流、解码、播放、缓冲、状态上报 |
| MineUI Mod | 屏幕页面与 HUD 渲染（由 MineAudio 服务端下发状态与动作） |

明确不做：

- 服务端代理/转发音频流
- 客户端渲染 UI / HUD / 歌词界面（属于 MineUI 需求）
- 客户端音源解析（音源改版不应要求玩家更新 mod）
- DRM 绕过、账号登录抓取、下载后再分发
- 原版客户端支持（未装 mod 由服务端 fallback 到 PACK/NBS）

## 3. 功能需求

### FR-1 能力握手
- 客户端连接后声明：协议版本、mod 版本、支持能力（seek / pause / multiSession / positional / cache）、支持的编码格式
- 服务端据此选源与降级；能力位与 `AudioCapabilities` 对齐

### FR-2 播放控制
- 指令：play / stop / pause / resume / seek / volume / fadeIn / fadeOut
- 播放引用包含：`trackId`、`provider`、`source`、`id`、服务端解析出的**可播放 URL（可刷新）**、`startEpoch`、起始进度、音量、Bus、会话 ID
- URL 可能过期：客户端请求刷新，服务端重新 resolve 并下发

### FR-3 同步播放
- 服务端保存 `startEpoch`，客户端按本地时钟计算进度，晚加入直接 seek
- 周期校时（PING/PONG，估算 RTT 与时钟偏移）；进度漂移超过阈值时静默 seek 校正
- 缓冲状态需上报；卡顿时不阻断其他会话

### FR-4 多会话与 Bus
- `MUSIC`：每玩家 1 条
- `AMBIENT`：可多层（受服务端 `max-ambient-layers` 限制）
- `SFX` / `UI`：不走流媒体（保持 PACK/Vanilla 低延迟）
- 每会话独立音量 / 暂停 / seek；服务端可单独停止

### FR-5 状态模型与上报
- 上报字段（供服务端/UI 使用）：
  - 会话 ID、曲目 ID、状态（buffering / playing / paused / stopped / error）
  - 当前进度 `positionMs`、总时长 `durationMs`（以客户端解码信息为准）
  - 缓冲比例、错误码与可读信息
- 上报时机：状态变化立即 + 播放中周期（默认 1s，可配）
- 曲目标题/歌手/封面/歌词等元数据以**服务端解析结果为准**，客户端不回传元数据（避免信任问题）
- 服务端据此更新计分板 / PAPI / MineUI；UI 不直连客户端

### FR-6 曲目解析与 URL 下发
- provider 适配（网易云 / QQ / 酷狗 / Bilibili / HTTP 等）在**服务端**实现（MineAudio adapter 或复用现有音源逻辑）
- 服务端负责：搜索、曲目信息、封面/歌词地址、URL 解析与刷新、内容过滤、限流
- 客户端只消费 URL；不接触账号、Cookie、Token

### FR-7 媒体防火墙
- 客户端只播放服务端下发的 URL，且校验：
  - 仅 HTTPS（可配置例外）
  - 主机白名单 / 黑名单
  - 拒绝私网 / 本机地址（SSRF、内网探测防护）
- 违规拒绝并上报错误；规则可由服务端下发，客户端本地可覆盖（只能更严格）

### FR-8 本地缓存
- 可配置开关与容量上限；LRU 清理
- 缓存仅本机播放用，禁止导出/再分发；退出时不得影响其他实例（单实例锁）

### FR-9 音频输出
- 解码与输出在独立线程，不阻塞游戏线程
- 音量策略可配：独立音量 / 跟随游戏主音量；切设备、暂停游戏（单机菜单）行为需定义
- 与 Minecraft 原版声音、其他音频 mod 共存；参考 MoeMusic 的 OpenAL 实践（线程上下文绑定、断线清理）

### FR-10 回退与隔离
- 未装 mod / 能力不足的玩家：服务端 fallback 到 PACK/NBS 或跳过，不报错
- mod 崩溃 / 未连接不得影响 PACK / Vanilla / NBS 播放
- 单曲解析失败不阻塞队列

### FR-11 位置音频（可选，后续阶段）
- 服务端可下发世界坐标与半径；客户端做距离衰减 / 立体声
- V1 可不做；协议字段预留

### FR-12 调试与可观测
- 客户端日志分级；`/mineaudio debug` 能看到各玩家流媒体状态（连接、能力、会话、进度、错误）

## 4. 协议需求（服务端 ↔ 客户端）

- 通道：插件消息通道（如 `mineaudio:stream`），双方声明后使用；带协议版本
- 消息最小集：
  - `HELLO`（C→S）：协议版本、能力位、mod 版本、语言
  - `PLAY` / `STOP` / `PAUSE` / `RESUME` / `SEEK` / `VOLUME`（S→C）
  - `URL_REFRESH`（C→S 请求 / S→C 响应）
  - `STATE`（C→S）：会话、状态、进度、缓冲、错误
  - `PING` / `PONG`：时钟同步
- 编码：JSON 或 protobuf（设计时定）；向后兼容（未知字段忽略）
- 安全：不传输账号凭据；URL 视为敏感数据；频率与大小限制
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
  - PAPI / 计分板读取真实状态（替换当前 `/music queue` 输出桥）
  - 能力探测改为 mod 通道握手
  - 旧 MoeMusic 命令桥保留为降级路径（可配置禁用）
  - 把状态转发给 MineUI（屏幕与 HUD），动作从 MineUI 走 MineAudio 服务端

## 7. 验收标准（场景）

1. 全服点播：装 mod 玩家同步误差 ≤ 100ms；晚进服 seek 到正确进度
2. 单玩家点播：仅该玩家可听；区域 / 发声点可按 scope 播放
3. 暂停 / 继续 / seek / 音量实时生效，状态回传正确
4. 未装 mod 玩家走 fallback，服务端无报错
5. 计分板 / PAPI 显示真实标题、歌手、进度（不再依赖解析 MoeMusic 输出）
6. 非白名单 URL 被客户端拒绝并上报
7. 断线重连、切维度、退出游戏无残留声音与缓存句柄
8. 8 人同时播放，客户端帧率 / 服务器 TPS 无明显波动

## 8. 待设计决策

1. URL 解析放服务端还是客户端（推荐服务端；客户端只取流）
2. 协议编码（JSON / protobuf）与压缩
3. 音频实现选型（解码库、输出设备、是否复用 OpenAL）
4. 状态上报频率与精度；进度以客户端还是服务端为准（推荐客户端上报、服务端广播）
5. 与 MoeMusic 的关系：并行共存还是逐步替代；迁移与开关策略
6. 多版本支持范围与发布方式（参考 MineUI 客户端安装包 `tools/build_client_kit.sh`）
7. 位置音频与 DSP（crossfade / EQ）是否纳入 V1

---

UI / HUD / 封面 / 歌词展示等需求见 [MineUI 新增能力需求](MINEUI_REQUIREMENTS.md)。
