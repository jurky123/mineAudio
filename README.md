# MineAudio

> Paper 26.2 · Minecraft 统一音乐 / 环境音 / 游戏音效基础设施

MineAudio 不是点歌插件，而是整个服务器的 **Audio Orchestrator**：统一管理“什么时候、给谁、在哪里、播放什么”。

- **Resource Pack OGG**：高音质、原版客户端可听、支持位置声，适合游戏音效与环境音
- **Note Block（NBS）**：音符盒音乐，不增加大型资源包
- **Streaming（规划中）**：对接 MoeMusic / Concerto，服务端只发送播放控制与歌曲信息，不代理音频流

业务插件（MineUNO / MineChess / MineAgent 等）只依赖统一的 `com.mineaudio.api`，不关心声音由哪种 Backend 播放。

## 文档

- [初步设计文档](初步设计文档) —— 项目定位、Bus / Region / Emitter / Backend 设计、阶段规划与 V1 验收标准
- [落地方案](docs/PLAN.md) —— 模块拆分、实施顺序与验收方式

## 开发状态

设计阶段，尚未开始编码。

## 目录规划

```text
mineAudio/
├── mineaudio-api/      # 业务插件依赖的稳定 Java API
├── mineaudio-paper/    # Paper 插件实现（Registry / Orchestrator / Backend / Region / Emitter）
├── pack/               # 资源包源文件（OGG + sounds.json），构建为 mineaudio.zip
├── nbs/                # NBS 曲目源文件，部署到 plugins/MineAudio/nbs/
└── docs/               # 设计与落地方案文档
```

## 构建与部署

```bash
./gradlew build                                # 构建插件与单测
./gradlew :mineaudio-api:publishToMavenLocal   # 供 MineUNO / MineChess 等业务插件 compileOnly
./deploy.sh                                    # 部署到 Paper 服务器（默认 /home/ubuntu/minecraft）
```
