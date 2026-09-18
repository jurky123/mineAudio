# MineAudio Client

> MineAudio 官方流媒体客户端
> 状态：设计方案 v0.1
> 日期：2026-09-18
> 目标：Fabric 1.20.1 / 1.21.1 / 26.2，Paper 端由 MineAudio 控制

---

# 0. 结论

推荐最终方案：

```text
Paper MineAudio
    │
    │ mineaudio:stream
    ▼
MineAudio Client Mod
    │
    ├── Protocol Client
    ├── Session Manager
    ├── Secure Media Transport
    ├── LavaPlayer Decoder
    ├── PCM Ring Buffer
    └── OpenAL Stream Engine
```

主要技术决策：

| 项目           | 决策                                |
| ------------ | --------------------------------- |
| 音源解析         | 服务端                               |
| 音频下载         | 客户端直连 CDN                         |
| 解码           | LavaPlayer                        |
| 输出           | Minecraft 已有 LWJGL / OpenAL       |
| 协议           | V1 JSON                           |
| Minecraft 通信 | `mineaudio:stream` Custom Payload |
| 时间同步         | 单调时钟 + PING/PONG                  |
| 播放进度         | 客户端实际播放进度为观测值                     |
| 元数据          | 服务端权威                             |
| UI           | MineUI                            |
| 多玩家          | 每个客户端独立播放                         |
| MoeMusic     | Legacy fallback                   |
| 位置音频         | 协议预留，V2                           |
| EQ / DSP     | V2+                               |
| 多版本          | 共用 Core + 每版本 Fabric Adapter      |

V1 不依赖 MoeMusic 客户端。

---

# 1. 职责边界

整个系统保持：

```text
MineAudio Server
= WHEN / WHO / WHERE / WHAT

MineAudio Client
= HOW

MineUI
= DISPLAY
```

服务端决定：

```text
播放什么
给谁播放
什么时候播放
在哪个区域播放
使用哪个 Bus
当前应该位于第几毫秒
URL 从哪里解析
是否允许播放
```

客户端只负责：

```text
取流
缓冲
解码
播放
seek
音量
fade
状态上报
```

客户端绝不：

```text
搜索网易云
访问 QQ 登录
保存 Cookie
解析账号 Token
决定曲目标题
决定玩家权限
```

---

# 2. 当前 MineAudio 需要调整的地方

当前项目只有：

```text
mineaudio-api
mineaudio-paper
```

两个模块。

扩展为：

```text
mineAudio/

├── mineaudio-api/
├── mineaudio-protocol/
├── mineaudio-paper/
│
├── mineaudio-client-core/
│
├── mineaudio-client-fabric-1.20.1/
├── mineaudio-client-fabric-1.21.1/
├── mineaudio-client-fabric-26.2/
│
├── docs/
└── tools/
```

其中：

```text
mineaudio-api
```

仍然是：

```text
MineUNO
MineChess
其他 Paper 插件
```

使用的业务 API。

而：

```text
mineaudio-protocol
```

必须是纯 Java：

```text
无 Bukkit
无 Fabric
无 Minecraft
```

负责协议 DTO / Codec / 常量。

---

# 3. StreamProvider 必须升级

目前：

```java
void play(AudioSource.Stream source);
```

无法知道：

```text
给哪个玩家播放
哪个 session
从哪里开始播放
音量多少
同步时间是多少
```

因此改为：

```java
interface StreamProvider {

    String id();

    boolean available(Player player);

    StreamProviderCapabilities capabilities(Player player);

    PlaybackHandle play(
        Player player,
        StreamPlaybackRequest request
    );
}
```

其中：

```java
record StreamPlaybackRequest(
    UUID sessionId,
    AudioTrack track,
    AudioSource.Stream source,
    PlaybackOptions options,
    StreamTiming timing
) {}
```

---

# 4. Provider 与 Resolver 分离

当前：

```java
AudioSource.Stream(
    provider,
    source,
    id,
    uri
)
```

存在一个概念混淆：

```text
provider = moemusic
source   = netease
```

这里 `moemusic` 实际上是“播放器 Backend”，而：

```text
netease
```

才是音乐来源。

新的客户端出现后应该彻底分开：

```text
Resolver
= 怎么获得播放 URL

Provider
= 怎么把 URL 播出来
```

因此新增：

```java
interface StreamResolver {

    String id();

    CompletableFuture<ResolvedStream> resolve(
        StreamReference reference
    );

    CompletableFuture<ResolvedStream> refresh(
        StreamReference reference,
        ResolvedStream previous
    );
}
```

曲目引用：

```java
record StreamReference(
    String resolver,
    String source,
    String id,
    URI directUri
) {}
```

例如：

```text
resolver = netease
source   = song
id       = 123456
```

或者：

```text
resolver = moemusic-adapter
source   = netease
id       = 123456
```

播放器则由 MineAudio 根据玩家能力选择：

```text
MineAudioClientProvider
MoeMusicLegacyProvider
```

---

# 5. ResolvedStream

服务端解析结果：

```java
record ResolvedStream(
    URI url,
    Map<String, String> headers,

    String mimeType,
    String codecHint,

    long expiresAtMs,

    long durationHintMs,

    String cacheKey
) {}
```

客户端只看到这个。

其中：

```text
cacheKey
```

必须是稳定逻辑 ID，例如：

```text
netease:song:123456:standard
```

的 SHA-256。

不能拿临时签名 URL 作为缓存 Key。

---

# 6. Provider 选择

目标：

```text
玩家安装 MineAudio Client
        ↓
MineAudioClientProvider

没有安装
        ↓
PACK / NBS fallback

管理员开启 legacy
        ↓
MoeMusicProvider
```

优先级：

```text
MineAudio Client
    >
PACK / NBS fallback
    >
MoeMusic Legacy
```

具体顺序允许配置。

旧 `MoeMusicProvider` 不删除，但改名建议：

```text
MoeMusicLegacyProvider
```

明确它：

```text
GLOBAL only
共享队列
无 per-player
无 multi-session
```

---

# 7. AudioCapabilities 调整

当前能力包括：

```text
seek
pause
loop
positional
sync
multiSession
lyrics
```

建议扩为：

```java
record AudioCapabilities(
    boolean vanillaClient,

    boolean seek,
    boolean pause,

    boolean volume,
    boolean fade,

    boolean loop,

    boolean positional,

    boolean synchronizedPlayback,

    boolean perPlayer,
    boolean multiSession,

    boolean cache,

    boolean lyrics
) {}
```

当前仍是 0.1.x，非常适合现在改。

---

# 8. PlaybackHandle 调整

当前 `PlaybackHandle` 已经有：

```text
stop
pause
resume
seek
```

但没有：

```text
volume
```

建议新增：

```java
default boolean setVolume(float volume) {
    return false;
}
```

以后如果需要：

```java
default boolean fadeTo(
    float volume,
    Duration duration
) {
    return false;
}
```

默认方法可以降低已有 Backend 的改造成本。

---

# 9. PlaybackState

当前：

```text
PENDING
PLAYING
PAUSED
STOPPED
FINISHED
UNSUPPORTED
```

建议补：

```text
BUFFERING
ERROR
```

因此生命周期：

```text
PENDING
   ↓
BUFFERING
   ↓
PLAYING
   ↕
PAUSED
   ↓
FINISHED
```

异常：

```text
ANY
 ↓
ERROR
```

---

# 10. 客户端总体架构

```text
MineAudioClient
│
├── net/
│   ├── ProtocolClient
│   ├── PacketCodec
│   ├── ClockSynchronizer
│   └── ConnectionState
│
├── playback/
│   ├── ClientAudioManager
│   ├── ClientPlaybackSession
│   ├── BusManager
│   └── PlaybackStateReporter
│
├── media/
│   ├── SecureMediaTransport
│   ├── MediaFirewall
│   ├── MediaCache
│   └── UrlRefreshManager
│
├── decode/
│   ├── AudioDecoder
│   ├── LavaPlayerDecoder
│   └── PcmRingBuffer
│
├── output/
│   ├── OpenAlEngine
│   ├── OpenAlStreamSource
│   └── MinecraftAudioDeviceBridge
│
├── platform/
│   └── FabricPlatform
│
└── config/
```

---

# 11. 音频解码

推荐：

```text
Lavalink Lavaplayer
```

只把它当：

```text
URL / file
   ↓
decoder
   ↓
48kHz Stereo PCM16LE
```

使用。

当前 Lavalink 维护版 Lavaplayer 为 Apache-2.0，支持 HTTP、本地文件以及 MP3、FLAC、WAV、AAC、OGG Vorbis/Opus/FLAC、WebM、MP4/M4A 等格式。

因此不需要：

```text
FFmpeg binary
VLC
Chromium
系统播放器
```

---

# 12. 不直接复制 MoeMusic

MoeMusic 当前客户端实际上也是：

```text
LavaPlayer
↓
PCM RingBuffer
↓
OpenAL
```

其解码输出采用 stereo PCM 48 kHz，并放在独立 decoder thread 中。

Minecraft 端则通过单独 OpenAL 线程消费 PCM，并复用 Minecraft OpenAL context。

MineAudio Client 可以采用同样的工程结构，但：

**只参考架构，不复制 MoeMusic AGPL 源码。**

实际代码独立实现，并直接依赖 Apache-2.0 的 Lavaplayer。

---

# 13. PCM 格式

统一内部格式：

```text
Sample Rate : 48,000 Hz
Channels    : Stereo
Sample      : Signed PCM
Bit Depth   : 16 bit
Endian      : Little Endian
```

即：

```text
192 KB/s PCM
```

三秒 PCM buffer：

```text
≈ 576 KB / session
```

即使：

```text
1 MUSIC + 3 AMBIENT
```

也只约：

```text
2.3 MB
```

非常轻。

---

# 14. PcmRingBuffer

每个播放 Session：

```text
Decoder
   │
   ▼
PcmRingBuffer
   │
   ▼
OpenAlEngine
```

推荐：

```text
buffer capacity
= 2~4 秒
```

开始播放阈值：

```text
500~1000 ms
```

低于阈值：

```text
BUFFERING
```

恢复到：

```text
resumeThreshold
```

后：

```text
PLAYING
```

---

# 15. OpenAL 架构

不要每个 Session 一个 OpenAL Thread。

使用：

```text
一个 MineAudio-OpenAL Thread
```

管理所有：

```text
MUSIC
AMBIENT 1
AMBIENT 2
AMBIENT 3
```

即：

```text
                 OpenAlEngine
                     │
         ┌───────────┼────────────┐
         ▼           ▼            ▼
      Source 1    Source 2     Source 3
         │           │            │
      Buffer ×4   Buffer ×4    Buffer ×4
```

这样：

```text
所有 OpenAL 调用
```

始终发生在一个线程。

---

# 16. Minecraft OpenAL Context

不创建第二套声卡 Context。

应该复用：

```text
Minecraft SoundEngine
```

已有 OpenAL context。

设计：

```java
interface MinecraftAudioDeviceBridge {

    long currentContext();

    long generation();

    void onContextChanged(...);
}
```

每个 Minecraft 版本只需要一个很薄的 Mixin：

```text
SoundEngine / Library 初始化
           ↓
捕获 ALC context
```

真正的 OpenAL Engine 完全放在：

```text
mineaudio-client-core
```

中。

LWJGL 本身已经提供 OpenAL 绑定，并随 Minecraft 环境存在。

---

# 17. 声音设备切换

当 Minecraft：

```text
切换输出设备
或
重新初始化 SoundEngine
```

会出现新的：

```text
OpenAL context generation
```

流程：

```text
Device reload

↓
OpenAlEngine suspend

↓
销毁旧 source/buffer

↓
获取新 context

↓
根据 session 当前 position

↓
重新建立 source

↓
seek + resume
```

因此设备切换不应该造成：

```text
幽灵声音
资源泄漏
整个游戏崩溃
```

---

# 18. 多 Session

客户端：

```java
Map<UUID, ClientPlaybackSession>
```

Session：

```java
ClientPlaybackSession {

    UUID sessionId;

    long revision;

    AudioBus bus;

    String trackId;

    DesiredPlaybackState desired;

    ActualPlaybackState actual;

    Decoder decoder;

    PcmRingBuffer buffer;

    OpenAlStreamSource output;
}
```

---

# 19. Bus 规则

### MUSIC

严格：

```text
max = 1
```

新的 MUSIC：

```text
替换旧 MUSIC
```

必要时：

```text
old fadeOut
+
new fadeIn
```

---

### AMBIENT

允许：

```text
0..N
```

N 由服务端：

```text
maxAmbientLayers
```

控制。

客户端自己再设：

```text
hardMaxAmbient = 4
```

防止恶意服务器无限开流。

---

### SFX / UI

继续：

```text
PACK / Vanilla
```

不进入 Stream Client。

原因：

```text
网络流媒体延迟不适合游戏 SFX
```

---

# 20. Protocol

通道：

```text
mineaudio:stream
```

V1 推荐：

**UTF-8 JSON。**

暂时不要 protobuf。

原因：

```text
消息很小
频率很低
便于抓包/debug
升级方便
未知字段天然可忽略
开发成本最低
```

典型：

```text
STATE 1次/秒
PING 10秒一次
PLAY 一首歌一次
```

Protobuf 节约的流量没有实际意义。

---

# 21. Packet Envelope

统一：

```json
{
  "protocol": 1,
  "type": "PLAY",

  "session": "...",
  "revision": 3,

  "data": {}
}
```

所有：

```text
session control packet
```

都必须有：

```text
session
revision
```

---

# 22. Revision

非常重要。

例如：

```text
PLAY rev=1
SEEK rev=2
STOP rev=3
```

客户端某个异步 URL load：

```text
rev=1
```

晚于 STOP 完成。

这时必须：

```text
if callbackRevision != currentRevision
    discard
```

防止：

```text
已经停止的旧歌突然重新响
```

---

# 23. HELLO

客户端：

```json
{
  "protocol": 1,
  "type": "HELLO",

  "modVersion": "0.1.0",

  "minecraft": "26.2",

  "locale": "zh_cn",

  "capabilities": [
    "seek",
    "pause",
    "volume",
    "fade",
    "multi_session",
    "sync",
    "cache"
  ],

  "formats": [
    "mp3",
    "flac",
    "wav",
    "aac",
    "ogg-vorbis",
    "ogg-opus",
    "m4a-aac",
    "webm-opus"
  ]
}
```

服务端：

```text
ClientConnectionRegistry
```

记录。

---

# 24. HELLO_ACK

服务端返回：

```json
{
  "protocol": 1,
  "type": "HELLO_ACK",

  "serverVersion": "0.2.0",

  "reportIntervalMs": 1000,

  "maxAmbientLayers": 3,

  "sync": {
    "pingIntervalMs": 10000,
    "driftThresholdMs": 120
  },

  "firewall": {
    "httpsOnly": true,
    "denyPrivateNetwork": true,
    "maxRedirects": 5
  }
}
```

---

# 25. PLAY

```json
{
  "protocol": 1,
  "type": "PLAY",

  "session": "a4d8...",
  "revision": 1,

  "data": {

    "trackId": "mineaudio:night_song",

    "source": "netease",
    "sourceId": "123456",

    "url": "https://cdn...",

    "headers": {
      "Referer": "..."
    },

    "resourceVersion": 1,
    "expiresAt": 1790000000000,

    "serverStartTime": 183748372,

    "positionMs": 0,

    "volume": 0.8,

    "bus": "MUSIC",

    "fadeInMs": 500,

    "durationHintMs": 243000,

    "spatial": null
  }
}
```

---

# 26. URL 中不发送账号凭据

禁止：

```text
Cookie
Authorization
账号 Token
网易云 Cookie
QQ Cookie
```

服务器允许发送：

```text
User-Agent
Referer
Origin
Range
```

等明确白名单 Header。

URL 本身可能包含：

```text
短期签名 token
```

这是正常的临时播放授权。

因此 URL：

```text
不得打印完整日志
```

Debug 只显示：

```text
cdn.example.com/... [redacted]
```

---

# 27. SecureMediaTransport

这是客户端安全的核心。

不要让 LavaPlayer：

```text
直接访问服务端给出的 URL
```

否则很难对：

```text
redirect
DNS rebinding
private IP
```

做完整控制。

推荐结构：

```text
LavaPlayer
   │
   │ localhost
   ▼
SecureMediaTransport
   │
   ├── MediaFirewall
   ├── Redirect Validator
   ├── DNS Validator
   ├── URL Refresh
   └── Cache
   │
   ▼
Remote CDN
```

---

# 28. Local Media Gateway

客户端启动：

```text
127.0.0.1:<random-port>
```

建立仅本机可访问的：

```text
Secure Media Gateway
```

LavaPlayer 实际加载：

```text
http://127.0.0.1:38741/media/<randomToken>
```

而不是远程 URL。

Gateway 内部才访问：

```text
https://music-cdn...
```

这样：

```text
安全策略
缓存
URL refresh
限速
redirect
headers
```

全部与 decoder 解耦。

---

# 29. Gateway 不能成为代理服务器

绝对不能：

```text
/media?url=https://anything
```

而应该：

```text
sessionId
→
内存中的 MediaResource
```

URL 不接受客户端 HTTP 参数。

Endpoint：

```text
/media/<128-bit-random-token>
```

并只绑定：

```text
127.0.0.1
::1
```

---

# 30. Media Firewall

所有外部 URL 必须：

1. 默认 HTTPS；
2. 禁止 URI userinfo；
3. 检查 Host；
4. 检查 allowlist / denylist；
5. DNS resolve；
6. 检查所有返回 IP；
7. 每次 redirect 重新检查；
8. redirect 最大 5；
9. 限制 URL 长度；
10. 限制 header。

拒绝：

```text
127.0.0.0/8
10.0.0.0/8
172.16.0.0/12
192.168.0.0/16
169.254.0.0/16

::1
fc00::/7
fe80::/10

multicast
unspecified
link-local
```

并建议同时拒绝：

```text
100.64.0.0/10
```

避免访问运营商内部网络。

---

# 31. 本地规则只能更严格

服务端：

```text
ALLOW
```

不能强迫客户端：

```text
ALLOW
```

最终：

```text
effectiveAllow
=
serverPolicy
∩
localPolicy
```

例如：

```text
Server:
allow HTTP = true

Client:
allow HTTP = false

结果:
false
```

---

# 32. Redirect

不要自动跟随未经检查的 redirect。

流程：

```text
Request
 ↓
302
 ↓
extract Location
 ↓
MediaFirewall.validate()
 ↓
DNS check
 ↓
follow
```

每一级都重新验证。

---

# 33. URL Refresh

触发：

```text
URL 即将过期

HTTP 401
HTTP 403
HTTP 410

CDN 返回 expired
```

客户端：

```json
{
  "type": "URL_REFRESH",

  "session": "...",
  "revision": 3,

  "requestId": "...",

  "resourceVersion": 1,

  "reason": "HTTP_403",

  "positionMs": 92743
}
```

---

# 34. Refresh Response

服务端重新：

```text
StreamResolver.refresh()
```

后返回：

```json
{
  "type": "URL_REFRESH_RESULT",

  "session": "...",
  "revision": 3,

  "requestId": "...",

  "resourceVersion": 2,

  "url": "...",
  "expiresAt": 1790001000000
}
```

客户端：

```text
保持 session
保持 position
替换 media resource
重新加载
seek(position)
resume
```

---

# 35. Refresh 防循环

单 Session：

```text
maxConsecutiveRefresh = 3
```

最小间隔：

```text
5 秒
```

否则：

```text
ERROR URL_REFRESH_FAILED
```

服务端再决定：

```text
fallback
skip
next song
```

---

# 36. 时间同步不要直接依赖系统 Epoch

需求里叫：

```text
startEpoch
```

实现时建议不要直接使用：

```java
System.currentTimeMillis()
```

因为玩家机器时钟可能：

```text
快 3 秒
慢 10 秒
被 NTP 调整
```

应该使用：

```text
服务端 monotonic clock
+
客户端 monotonic clock
+
offset estimation
```

即：

```java
System.nanoTime()
```

转换成毫秒。

---

# 37. PING / PONG

采用类似 NTP 的四时间戳：

```text
Server             Client

t0  ───PING──────► t1
                   t2
t3  ◄──PONG───────
```

计算：

```text
RTT =
(t3 - t0)
-
(t2 - t1)
```

Clock Offset：

```text
offset =
((t1 - t0) + (t2 - t3)) / 2
```

---

# 38. ClockSynchronizer

连接开始：

```text
快速采样 5 次
```

保留：

```text
RTT 最低的 3 个
```

取：

```text
median offset
```

运行期间：

```text
每 10 秒
```

重新校正。

避免单次网络抖动。

---

# 39. 同步开始播放

全服同步时不要：

```text
收到 PLAY
→ 立即播放
```

服务器安排一个未来时间：

```text
now + 1000~1500 ms
```

例如：

```text
serverStartTime = 500123.500
```

客户端：

```text
收到 PLAY
↓
连接 CDN
↓
buffer
↓
等待 startTime
↓
同时 start
```

这样才能比较稳定达到：

```text
≤100 ms
```

同步。

---

# 40. 慢客户端

如果 Alice：

```text
目标时间前 buffer ready
```

正常等待。

Bob：

```text
目标时间后 700ms
```

才 buffer ready。

Bob 不从：

```text
0ms
```

开始。

而：

```text
expected =
serverNow - serverStartTime
```

然后：

```text
seek(expected)
```

再播放。

因此晚进服也是同一个逻辑。

---

# 41. Drift Correction

客户端维护：

```text
expectedPosition
```

与：

```text
actualPosition
```

差：

```text
drift
```

建议：

```text
|drift| < 80 ms
→ ignore

80~150 ms
→ 等下一周期确认

> 150 ms 持续两次
→ silent seek

> 500 ms
→ immediate seek
```

不要不断 seek。

---

# 42. 实际播放进度

不能只使用：

```text
Decoder Track.position
```

因为它可能已经 decode 到：

```text
未来 buffer
```

真正应该上报：

```text
已经被 OpenAL 播出的 PCM
```

计算：

```text
finishedFrames
+
current AL buffer offset
```

得到：

```text
actualPositionMs
```

这也是当前 MoeMusic OpenAL 实现采用的思路。

---

# 43. STATE

```json
{
  "type": "STATE",

  "session": "...",
  "revision": 3,

  "seq": 128,

  "state": "PLAYING",

  "positionMs": 92432,
  "durationMs": 243281,

  "bufferedMs": 2410,
  "bufferRatio": 0.80,

  "error": null
}
```

---

# 44. 上报策略

状态变化：

```text
立即
```

例如：

```text
BUFFERING → PLAYING
PLAYING → PAUSED
PLAYING → ERROR
```

播放期间：

```text
默认 1000 ms
```

一次。

暂停期间：

```text
5 秒 heartbeat
```

即可。

---

# 45. 服务端状态缓存

新增：

```text
ClientPlaybackStateCache
```

Key：

```text
Player UUID
+
Session UUID
```

Value：

```java
ClientPlaybackSnapshot {
    state
    positionMs
    durationMs
    bufferedMs
    error

    receivedAt

    revision
}
```

---

# 46. PAPI 不直接查询客户端

PlaceholderAPI：

```text
%mineaudio_title%
%mineaudio_artist%
%mineaudio_state%
%mineaudio_position%
%mineaudio_duration%
%mineaudio_progress%
```

读取：

```text
Server Cache
```

绝不能：

```text
PAPI request
→ packet
→ wait client
```

否则会阻塞服务器线程。

---

# 47. Server Progress Extrapolation

如果最后一次 STATE：

```text
position = 60,000ms
state = PLAYING
received = 500ms ago
```

服务端显示：

```text
60,500ms
```

而不是一直显示：

```text
60,000
```

因此：

```text
displayPosition =
reportedPosition
+
elapsedSinceReport
```

如果：

```text
PAUSED
BUFFERING
ERROR
```

则不增加。

---

# 48. MineUI

MineUI 永远：

```text
MineUI
   ▲
   │ state
   │
MineAudio Server
   ▲
   │ STATE
   │
MineAudio Client
```

不能：

```text
MineUI Client
↔
MineAudio Client
```

直接共享业务状态。

原因：

```text
服务端才是权威状态中枢
```

这样：

```text
PAPI
Scoreboard
Web
MineUI
```

看到的都是同一份状态。

---

# 49. Pause

PAUSE 应包含：

```text
目标位置
执行时间
```

而不只是：

```text
pause now
```

例如：

```json
{
  "type": "PAUSE",
  "session": "...",
  "revision": 5,

  "positionMs": 91340,

  "executeAtServerTime": 83929382
}
```

全服玩家可以在接近同一时间暂停。

---

# 50. Resume

同样：

```text
resumePosition
+
resumeAtServerTime
```

因此全服恢复不会因为包延迟逐渐失同步。

---

# 51. SEEK

```json
{
  "type": "SEEK",

  "session": "...",
  "revision": 8,

  "positionMs": 120000,

  "executeAtServerTime": 93748382
}
```

客户端：

```text
停止 output
↓
清 PCM buffer
↓
decoder seek
↓
rebuffer
↓
按 timeline resume
```

---

# 52. Volume

协议：

```json
{
  "type": "VOLUME",

  "session": "...",

  "volume": 0.65,

  "transitionMs": 300
}
```

客户端音量：

```text
Final Gain
=
Track Volume
×
Bus Volume
×
Client Master Volume
×
Minecraft Master Volume(optional)
×
Fade
×
SpatialGain(future)
```

---

# 53. Fade

Fade 不需要 Decoder 处理。

直接：

```text
OpenAL AL_GAIN
```

线性或：

```text
equal-power curve
```

都可以。

V1 用：

```text
linear
```

即可。

---

# 54. 本地音量配置

配置：

```json
{
  "masterVolume": 1.0,

  "musicVolume": 1.0,
  "ambientVolume": 0.8,

  "followMinecraftMaster": true,

  "pauseWhenGamePaused": true
}
```

这个配置不需要 MineUI。

可以直接使用：

```text
config/mineaudio-client.json
```

以后 MineUI 设置页只是编辑它。

---

# 55. 游戏暂停行为

默认：

```text
多人服务器打开 ESC
→ 不暂停
```

因为 Minecraft 世界也没有暂停。

真正：

```text
Minecraft.isPaused()
```

时，例如单人游戏：

```text
pauseWhenGamePaused=true
```

才暂停。

---

# 56. Cache

目录：

```text
.minecraft/
└── mineaudio/
    └── cache/
```

不按照：

```text
URL
```

命名。

而：

```text
SHA-256(cacheKey)
```

例如：

```text
3b7e....media
```

---

# 57. Cache 生命周期

配置：

```text
enabled: true

maxSize:
1 GB

maxSingleTrack:
100 MB
```

清理：

```text
LRU
```

使用文件：

```text
lastModified
```

作为简单 lastAccess。

不需要 SQLite。

---

# 58. 缓存安全

缓存：

```text
只保存编码后的原始媒体 bytes
```

不保存：

```text
Cookie
Authorization
解析 Token
账号凭据
```

`.part`：

```text
播放下载未完成
```

完成：

```text
atomic rename
```

崩溃：

```text
下次启动清理过期 .part
```

---

# 59. Cache 与 Secure Gateway

Gateway 可以：

```text
Cache Hit
↓
直接提供本地文件
↓
LavaPlayer
```

Cache Miss：

```text
Remote CDN
↓
Secure Gateway
├→ LavaPlayer
└→ cache .part
```

只有完整顺序流成功结束后：

```text
.part → .media
```

Range / live stream / 不完整下载：

```text
不提交 cache
```

因此实现可以保持简单。

---

# 60. 单实例锁

推荐：

```text
MineAudio playback lock
```

用：

```text
FileChannel.tryLock()
```

锁位置使用：

```text
用户级状态目录
```

而不是单个 Minecraft instance cache。

如果无法获得：

```text
MineAudio Client
仍然工作
```

但：

```text
audioOutput = false
```

HELLO 不声明：

```text
stream_playback
```

服务器自动 fallback。

---

# 61. Error Model

固定 error code：

```text
FIREWALL_REJECTED

PRIVATE_ADDRESS

DNS_FAILED

HTTP_401
HTTP_403
HTTP_404
HTTP_410
HTTP_4XX
HTTP_5XX

URL_EXPIRED
URL_REFRESH_FAILED

UNSUPPORTED_FORMAT

DECODE_FAILED

BUFFER_UNDERRUN

AUDIO_DEVICE_UNAVAILABLE

SESSION_LIMIT

INTERNAL
```

可读 message：

```text
≤256 chars
```

禁止把完整 URL 放进去。

---

# 62. Session Failure Isolation

例如：

```text
AMBIENT waterfall
```

断流。

只能：

```text
waterfall → ERROR
```

不能导致：

```text
MUSIC 停止
AMBIENT fire 停止
MineAudio Client shutdown
```

所有 session 独立。

---

# 63. 客户端断开服务器

触发：

```text
disconnect
server switch
world unload due to disconnect
```

执行：

```text
stop all sessions

cancel resolver wait

cancel refresh

stop decoder

clear PCM buffers

delete OpenAL sources

clear protocol state
```

Cache 保留。

---

# 64. 换维度

不要客户端自行全部 stop。

因为：

```text
Nether
```

音乐应该由：

```text
MineAudio Server
```

决定。

流程：

```text
PlayerChangedWorldEvent
↓
服务端 Region / World arbitration
↓
STOP / PLAY
```

客户端只是执行。

---

# 65. 多版本设计

不要做：

```text
一个 jar
同时跑 1.20.1 / 1.21.1 / 26.2
```

因为 Fabric networking 在 1.20.5 前后发生过明显重构；当前 26.2 官方 API 使用 `CustomPacketPayload + StreamCodec`，而 1.20.1 仍是旧的 FabricPacket / PacketByteBuf 路线。

因此：

```text
Client Core
   │
   ├ Fabric 1.20.1
   ├ Fabric 1.21.1
   └ Fabric 26.2
```

最稳。

---

# 66. Client Core Java 版本

建议：

```text
mineaudio-client-core
→ Java 17
```

这样三个版本都能加载其字节码。

平台层：

```text
1.20.1 → Java 17

1.21.1 → Java 21

26.2 → Java 25
```

客户端 Core 不引用 Minecraft 类。

---

# 67. Platform Adapter

抽象：

```java
interface ClientPlatform {

    void send(byte[] packet);

    void disconnectCleanup(Runnable callback);

    boolean gamePaused();

    float minecraftMasterVolume();

    AudioDeviceBridge audioDevice();
}
```

Minecraft-specific 代码只出现在：

```text
fabric-xxx
```

模块。

---

# 68. Fabric 26.2

使用：

```text
CustomPacketPayload
StreamCodec
ClientPlayNetworking
```

符合当前 Fabric 26.2 官方 networking API。

Payload 本身只包：

```text
byte[]
```

然后交给：

```text
mineaudio-protocol
```

解析 JSON。

因此业务协议不会绑定 Minecraft packet API。

---

# 69. 1.20.1

使用旧：

```text
Identifier
PacketByteBuf
ClientPlayNetworking
```

外层 Adapter 不同。

内部仍然：

```text
byte[]
↓
ProtocolCodec
```

完全一样。

---

# 70. 协议包限制

推荐应用层硬限制：

```text
Packet
≤ 24 KiB

URL
≤ 8 KiB

Header Count
≤ 16

Header Value
≤ 1 KiB

Error Message
≤ 256
```

正常 PLAY 包一般只有：

```text
1~5 KiB
```

完全无需压缩。

---

# 71. JSON 不压缩

V1：

```text
JSON
+
UTF-8
+
无 compression
```

原因：

```text
包非常小
便于 debug
避免额外状态
避免压缩炸弹
```

以后只有证明：

```text
协议流量是问题
```

再加：

```text
flags: gzip
```

---

# 72. MineAudio Client Provider

服务端新增：

```java
MineAudioClientProvider
    implements StreamProvider
```

职责：

```text
检查客户端握手

创建 Session

调用 Resolver

发送 PLAY

处理 STATE

处理 URL_REFRESH

维护 PlaybackHandle
```

---

# 73. ClientStreamHandle

```java
ClientStreamHandle
    implements PlaybackHandle
```

内部：

```text
player UUID
session UUID
revision

desiredState
lastClientState
```

`state()`：

优先：

```text
Client STATE
```

否则：

```text
PENDING
```

---

# 74. StreamBackend 不再按 trackId 全服复用

当前：

```java
Map<Key, StreamHandle> active
```

这一逻辑需要删除。

新的 key：

```text
sessionId
```

每个玩家：

```text
独立 handle
```

Global Audio Session：

```text
逻辑上一个 ActiveSession
```

内部仍然拥有：

```text
Alice ClientStreamHandle
Bob ClientStreamHandle
Carol ClientStreamHandle
```

这正好与当前 `AudioOrchestrator` 已经按玩家展开 Audience 的结构相匹配。

---

# 75. 删除 Global-only 限制

当前：

```text
if stream && !audience.isGlobal()
    reject
```

需要删除。

改为：

```text
根据玩家实际 StreamProvider capability
```

决定。

因此：

```text
Player
Region
World
Global
```

都自然可用。

---

# 76. Emitter

V1 流媒体 Emitter：

```text
服务端根据 radius
决定哪些玩家属于 audience
```

客户端收到的仍然：

```text
普通非位置 Stream
```

因此可以实现：

```text
酒吧音箱
附近玩家听同一首歌
```

但还不是：

```text
真正左右声道空间化
```

---

# 77. Positional V2

PLAY 协议现在就预留：

```json
"spatial": {
  "world": "world",
  "x": 123.4,
  "y": 65,
  "z": -42,

  "radius": 24,

  "rolloff": "linear"
}
```

V1：

```text
capability positional=false
```

直接忽略。

V2：

```text
OpenAL AL_POSITION
+
listener position
```

即可实现。

---

# 78. DSP

V1 不做：

```text
EQ
Reverb
Compressor
Time stretching
Pitch shifting
```

Crossfade 也不作为独立 DSP。

只支持：

```text
Gain Fade
```

已经足够。

---

# 79. 与 MoeMusic 的迁移

阶段一：

```text
MineAudio Client
+
MoeMusic Legacy
```

并存。

配置：

```yaml
stream:
  provider-priority:
    - mineaudio-client
    - moemusic-legacy
```

---

阶段二：

稳定后：

```text
mineaudio-client
```

成为默认。

MoeMusic：

```text
legacy-enabled: false
```

---

阶段三：

如果服务器不再需要 MoeMusic 自己的：

```text
/music
KTV
独立音乐功能
```

即可完全卸载。

MineAudio Client 不依赖它。

---

# 80. MineUI Client Kit

你当前 MineUI 已经有：

```text
tools/build_client_kit.sh
```

负责：

```text
MineUI jar
Fabric API
Fabric Installer
安装说明
```

自动打包。

MineAudio 建议不要再让用户下载第二个安装包。

最终增加：

```text
client-kit/
├ mineui-client.jar
├ mineaudio-client.jar
├ fabric-api.jar
└ 安装说明.txt
```

长期最好把：

```text
build_client_kit.sh
```

提升到：

```text
minePlugins/tools/
```

成为：

```text
服务器官方客户端安装包
```

构建工具。

---

# 81. MineAudio 自身 Build

同时仍提供单独：

```text
mineaudio-client-26.2.jar
```

方便独立更新。

Release：

```text
MineAudio Client 0.1.0

Assets:

mineaudio-client-1.20.1-0.1.0.jar
mineaudio-client-1.21.1-0.1.0.jar
mineaudio-client-26.2-0.1.0.jar

mineaudio-client-kit-26.2-0.1.0.zip
```

---

# 82. 配置

客户端：

```text
config/mineaudio-client.json
```

建议：

```json
{
  "masterVolume": 1.0,

  "musicVolume": 1.0,
  "ambientVolume": 0.8,

  "followMinecraftMaster": true,

  "pauseWhenGamePaused": true,

  "cache": {
    "enabled": true,
    "maxSizeMb": 1024,
    "maxTrackMb": 100
  },

  "security": {
    "httpsOnly": true,
    "allowPrivateNetwork": false
  },

  "debug": false
}
```

服务端不能：

```text
修改客户端本地安全策略
```

只能提供：

```text
更严格 / 上层 policy
```

---

# 83. Server Config

增加：

```yaml
stream-client:

  enabled: true

  handshake-timeout-ms: 3000

  state-report-ms: 1000

  max-ambient-layers: 3

  sync:
    enabled: true

    initial-lead-ms: 1200

    ping-interval-ms: 10000

    drift-threshold-ms: 150

  firewall:

    https-only: true

    max-redirects: 5

  legacy-moemusic:
    enabled: true
```

---

# 84. 调试

现有：

```text
/mineaudio debug
```

扩展显示：

```text
Player Alice

Stream Client:
CONNECTED

Mod:
0.1.0

Protocol:
1

Minecraft:
26.2

Clock:
RTT 32ms
Offset +8ms

Capabilities:
seek pause volume fade
multi-session sync cache

MUSIC:
mineaudio:night_song

Session:
a431...

State:
PLAYING

Position:
01:32.431 / 04:03.120

Buffer:
2.4s

Drift:
+17ms

URL:
music.163.com [redacted]

Last report:
421ms ago
```

---

# 85. Client Log

等级：

```text
ERROR
WARN
INFO
DEBUG
TRACE
```

默认 INFO。

永远脱敏：

```text
URL query
signed token
header values
```

例如：

```text
https://m7.music.126.net/[redacted]
```

---

# 86. 性能目标

需要注意：

```text
8~16 人同时播放
```

不是一台客户端解码 16 首。

而是：

```text
16 个玩家 PC
各自解码自己的 1~4 个 Session
```

所以服务端负担只有：

```text
控制包
状态包
Resolver
```

基本不会影响 TPS。

客户端常规：

```text
1 MUSIC
+
0~3 AMBIENT
```

目标：

```text
OpenAL thread <1% CPU 常态
无游戏线程解码
无 render thread 网络阻塞
```

---

# 87. Thread Model

```text
Minecraft Client Thread
    │
    └── packet enqueue only


Protocol Executor
    │
    ├ session state
    └ URL refresh


Decoder Executor
    │
    ├ LavaPlayer session 1
    └ LavaPlayer session N


Secure HTTP Executor
    │
    └ CDN / cache


MineAudio OpenAL Thread
    │
    └ all AL calls
```

游戏线程绝不：

```text
HTTP
decode
disk cache
wait URL
```

---

# 88. 并发上限

客户端：

```text
MUSIC = 1

AMBIENT =
min(serverLimit, clientLimit)

HTTP concurrent =
4

decoder sessions =
4

URL refresh =
2 concurrent
```

避免恶意服务器创建大量线程。

---

# 89. Server Resolver 也不能阻塞主线程

流程：

```text
Paper Main Thread

play()

↓
create PENDING handle

↓
Async StreamResolver

↓
ResolvedStream

↓
send PLAY
```

因此：

```java
PlaybackHandle play(...)
```

可以马上返回：

```text
PENDING
```

Resolver 完成后进入：

```text
BUFFERING
```

---

# 90. Fallback

发生：

```text
没有 mod
HELLO timeout
capability 不足
firewall reject
resolver failure
decode failure
```

服务端根据 Track：

```text
fallback
```

选择：

```text
PACK
NBS
Vanilla
```

但是要防止：

```text
STREAM 播了 5 秒
↓
偶发 buffer
↓
突然同时开始 PACK BGM
```

只有终态：

```text
ERROR
```

且错误属于：

```text
non-recoverable
```

才 fallback。

---

# 91. 错误恢复分类

Recoverable：

```text
URL expired
temporary HTTP 5xx
buffer underrun
device reload
```

客户端先重试。

Non-recoverable：

```text
Firewall reject
unsupported format
resolver permanently failed
invalid URL
session limit
```

服务端 fallback。

---

# 92. 测试结构

## Protocol Tests

```text
unknown field ignored
unknown packet ignored

version mismatch

oversized packet rejected

stale revision rejected
```

## Security Tests

```text
localhost
127.0.0.1
192.168.x
IPv6 loopback
redirect → private IP
DNS → private IP

userinfo URL
oversized URL
bad header
```

## Playback Tests

```text
MP3
FLAC
OGG Vorbis
OGG Opus
AAC / M4A

pause
resume
seek

fade
volume

EOF
HTTP failure
```

## Sync Tests

人工注入：

```text
20ms
50ms
100ms
300ms
```

延迟和 jitter。

目标：

```text
正常网络
≤100ms
```

---

# 93. 开发阶段

## Phase 0 — Server Refactor

先完成：

```text
StreamProvider session-aware

AudioCapabilities 扩展

PlaybackState BUFFERING / ERROR

PlaybackHandle setVolume

删除 stream global-only 限制

StreamBackend 不再按 trackId 全局复用
```

---

## Phase 1 — Protocol

实现：

```text
mineaudio-protocol

HELLO
HELLO_ACK

PLAY
STOP

STATE

PING
PONG
```

先不播放音频。

验证：

```text
Paper ↔ Fabric 26.2
```

通信。

---

## Phase 2 — 最小播放器

实现：

```text
LavaPlayer
PCM RingBuffer
OpenAL

PLAY
STOP
STATE
```

只支持：

```text
HTTP MP3 / OGG
```

先跑通完整链路。

---

## Phase 3 — 完整控制

加入：

```text
pause
resume
seek
volume
fade

duration
buffer state

URL refresh
```

---

## Phase 4 — Sync

加入：

```text
clock sync

future scheduled play

late join seek

drift correction
```

完成：

```text
全服同步 ≤100ms
```

---

## Phase 5 — Security

加入：

```text
SecureMediaTransport
MediaFirewall
redirect validation
DNS validation
header whitelist
```

这个阶段完成前：

**不要正式发布给普通玩家。**

---

## Phase 6 — Cache

实现：

```text
local media gateway

disk cache
LRU
atomic files
```

---

## Phase 7 — Multi Session

实现：

```text
1 MUSIC
+
N AMBIENT
```

区域播放正式切到：

```text
MineAudio Client
```

---

## Phase 8 — 生态集成

替换：

```text
MoeMusic queue parsing
```

接：

```text
PAPI
Scoreboard
MineUI
/audio debug
```

---

## Phase 9 — 多版本

最后移植：

```text
26.2
↓
1.21.1
↓
1.20.1
```

不要一开始同时维护三版。

先把：

```text
client-core
```

稳定。

---

# 94. V1 明确不做

```text
客户端搜索歌曲

客户端网易云 API

账号登录

Cookie

下载歌曲功能

歌词 UI

音乐 HUD

EQ

Reverb

DSP

HRTF 定制

真正位置流媒体

NeoForge

跨服务器持续播放
```

---

# 95. V1 验收

完成条件：

```text
✓ MineAudio Client handshake

✓ per-player stream

✓ global stream

✓ region stream

✓ MUSIC + AMBIENT multi-session

✓ client direct CDN

✓ MP3 / OGG / FLAC / AAC 基础支持

✓ pause / resume

✓ seek

✓ volume

✓ fade

✓ late join seek

✓ global sync ≤100ms

✓ client state report

✓ server PAPI 使用真实状态

✓ MineUI 使用 server state

✓ URL refresh

✓ firewall

✓ private IP rejection

✓ disconnect cleanup

✓ device reload recovery

✓ PACK / NBS fallback

✓ MoeMusic legacy 可并存

✓ /mineaudio debug

✓ 26.2 稳定运行
```

缓存可以：

```text
V1.0 或 V1.1
```

完成，但协议从第一版就声明对应 capability。

---

# 96. 最终数据流

最终一次网易云区域音乐：

```text
Alice 进入 Tavern
        │
        ▼
MineAudio RegionManager
        │
        ▼
AudioOrchestrator
        │
        ▼
StreamBackend
        │
        ▼
MineAudioClientProvider
        │
        ▼
StreamResolver
        │
        ├─ title
        ├─ artist
        ├─ duration
        ├─ cover
        └─ playable URL
        │
        ▼
PLAY
        │
Minecraft connection
        ▼
MineAudio Client
        │
        ▼
MediaFirewall
        │
        ▼
SecureMediaTransport
        │
        ▼
CDN
        │
        ▼
LavaPlayer
        │
        ▼
PCM RingBuffer
        │
        ▼
OpenAL
        │
        ▼
Speakers
```

同时：

```text
OpenAL actual position
        │
        ▼
STATE
        │
        ▼
MineAudio Server
        │
   ┌────┼─────┐
   ▼    ▼     ▼
 PAPI MineUI Debug
```

---

# 97. 最终原则

MineAudio Client 应当尽可能“笨”。

它不知道：

```text
网易云是什么
QQ音乐是什么
Bilibili是什么
这首歌叫什么
谁点的歌
为什么播放
玩家在哪个区域
```

它只知道：

```text
Session A

从这个经过安全校验的媒体资源

在服务器时间 T

从 position P

以 volume V

开始播放

并把真实播放状态报告回去
```

这样未来即使：

```text
网易云接口变化
QQ 音乐变化
新的 Music Provider 出现
MoeMusic 被卸载
```

玩家都不需要更新 MineAudio Client。

这就是这个客户端 Mod 最重要的长期维护优势。
