# MineUI 新增能力需求（音乐 UI 驱动）

> 状态：需求草案（2026-09-18）。本需求由 MineAudio 场景驱动，但要求做成**通用能力**，
> 其他业务插件可复用；具体设计由 MineUI 项目确定。
> 关联：音频与状态来源见 [配套流媒体客户端 Mod 需求](CLIENT_MOD_REQUIREMENTS.md)。

## 1. 背景与目标

MineUI 已能承载屏幕页面（会话 / 状态 / 动作 / 组件 / 动态贴图 / 3D 预览），MineAudio 的
`/mineaudio ui` 已在使用。但要做“音乐播放器”体验还缺：

- **HUD**：此前“正在播放”只能靠第三方客户端本地渲染，服务端不可控（MineUI HUD 已实现，MineAudio 已接入）
- **远程图片**：网易云封面等是远程 URL，MineUI 目前只能渲染资源包/内置贴图
- **进度条**：需要数值绑定或专用组件；进度要平滑，不能靠高频网络包
- **交互细节**：拖动滑块（seek/音量）、按键打开界面、歌词高亮滚动等

目标：把这些能力做进 MineUI，避免每个业务插件各自造轮子；MineAudio 只通过服务端下发
状态与动作，不依赖任何客户端专用 UI。

## 2. 范围

- 只做 UI 能力；不包含音频播放与流媒体协议（见关联文档）
- 状态来源：业务插件通过 MineUI 现有/新增 API 下发；MineAudio 的状态由服务端汇总
- 通用性要求：HUD、远程图片、进度条等不做成音乐专用，验收时需用非音乐场景验证

## 3. 功能需求

### FR-1 HUD Overlay 框架
- 声明式：复用现有 JSON 节点（text / box / image / item / …）描述 HUD
- 服务端下发默认布局：`anchor`（四角或自由偏移）、`offset`、`scale`、`visible`、`z`
- 与屏幕会话独立生命周期：owner 插件管理；玩家退出 / 切服 / 插件停用自动清理
- 客户端本地可覆盖：总开关、位置、缩放（个人偏好优先），客户端设置持久化
- 安全区：默认避开原版计分板、hotbar、BossBar 等区域，或提供 safe-area 约束
- 更新：走现有状态 patch 机制；HUD 常驻渲染成本可控
- 与 MineUI 未来 HUD 规划（设计文档 §62）保持同一套实现

### FR-2 远程图片 / 封面渲染
- 通过 HTTPS URL 拉取图片并作为 `image` 节点渲染（封面、头像）
- 安全限制：仅 HTTPS、域名白名单/黑名单、拒绝私网与本机地址、大小与格式限制
- 异步加载不阻塞渲染；加载中 / 失败有占位与降级（不崩、不空白闪烁）
- 缓存：内存 + 磁盘上限、LRU 清理；退出/切服清理句柄
- 可选的展示效果：圆形裁剪、旋转（唱片）等（可由组件层或动效实现）
- 只解码图片，不执行任何远程代码

### FR-3 数值绑定与进度条
- 现有 `{state.x}` 绑定扩展到数值属性（如 `width` / `height` 百分比）
- 或提供 progress 组件：`min` / `max` / `value`、背景与填充样式、方向、圆角
- 支持时间文本（进度 / 总时长）格式化，或由业务侧拼好字符串

### FR-4 客户端平滑插值（低频更新）
- 服务端 1Hz 下发进度即可，客户端按本地时间在两次更新间插值，进度条与时间平滑
- 节点声明式开启（如 `interpolate: true`）；暂停 / seek / 切歌时立即对齐
- 不增加网络频率

### FR-5 滑块增强（seek / 音量）
- 拖动中（预览值）与提交（松开）分离的动作，避免拖动期间高频请求
- 值双向绑定：服务端状态变化能更新滑块位置
- 禁用态 / 只读态；步进与吸附

### FR-6 客户端键位
- 服务端可声明“按某键打开 app/view”（如音乐界面）
- 冲突处理与本地改键；未安装 mod 的玩家不受影响
- 界面按钮可显示当前按键提示

### FR-7 高亮列表 / 歌词视图
- 滚动文本列表 + 当前行高亮 / 居中（歌词、队列当前项）
- 逐行样式；自动滚动到当前行

### FR-8 通知 / Toast（可选）
- 服务端短提示（切歌、错误等），支持图标与点击动作

### FR-9 本地偏好持久化
- HUD 开关 / 位置 / 缩放等客户端本地保存；服务端只给默认值

### FR-10 性能与生命周期
- HUD 与屏幕同时存在时的渲染顺序与资源占用可控
- 批量 patch；玩家退出 / 切服 / 断线彻底清理（无残留 HUD、图片句柄）

### FR-11 安全与权限
- HUD 与页面内容仅服务端权威；客户端只渲染
- 远程资源同受防火墙约束，不把客户端 IP 暴露给任意主机

## 4. 与现有 API 的关系

- 屏幕：`MineUi.open` / `session.state` / `session.on` / `session.snapshot` / `session.close` 保持不变
- HUD：需要新 API（例如 `MineUiHud` / `HudSession`，owner 生命周期、state / on / snapshot）
- 能力位：客户端上报是否支持 HUD、远程图片、插值等；业务插件按能力降级
- 服务端只提供状态；具体展示由页面 / HUD 的 JSON 决定

## 5. 验收场景（含通用性验证）

1. 音乐：`/mineaudio ui` 显示真实曲目、进度、封面；HUD 显示“正在播放”，服务端可调位置/缩放/关闭；计分板仍可显示文本
2. 音乐：未装流媒体 mod 时 UI 自动降级（无进度/封面）且不报错
3. 通用：另一个插件用 HUD 显示倒计时 / 比分，验证非音乐场景可用
4. 性能：HUD + 屏幕同时开启，帧率无明显下降；1Hz 更新下进度平滑
5. 安全：非白名单图片 URL 被拒绝；HUD 在切服 / 退出后无残留

## 6. 非功能需求

- 平台：与 MineUI 客户端一致（Fabric 1.20.1 / 1.21.1 / 26.2+）
- 版本：能力位与协议向后兼容；旧客户端遇到未知节点安全跳过（沿用现有约定）
- 性能：图片缓存上限、HUD 渲染开销、内存占用需有明确上限
- 安全：远程资源白名单、拒绝私网

## 7. 待设计决策（交给 MineUI 设计）

1. HUD 与屏幕共用 JSON 组件，还是 HUD 独立子集
2. HUD 本地偏好的存储位置与优先级
3. 远程图片直连还是服务端代理/签名 URL（IP 暴露权衡）
4. 插值支持哪些属性、刷新上限
5. 键位方案（是否引入客户端键位注册 API）
6. 进度条用专用组件还是数值绑定
7. 是否把歌词 / 队列抽象为通用“高亮列表”组件
8. 与未来官方 HUD 规划的合并方式


## 8. FR-12 客户端本地状态与动作（跨 mod 极简 API，2026-09-19 评审后确定）

背景：播放位置 / 拖动预览 / 本地暂停必须由客户端在本地主线程逐帧处理，不能再经服务端 1Hz 中转；
但 MineUI 页面是服务端下发的 JSON，需要一条“页面直接读本地状态、直接派发本地动作”的通路。

### 8.1 API 形态（选定方案 a）

- 新增独立 artifact/mod：`mineui-client-api`
  - **纯 Java 接口 + 注册表，无 MC 依赖**，做成无入口点的 Fabric library mod（`fabric.mod.json` 仅 id/version）
  - 随客户端包分发；MineUI 客户端与业务客户端（MineAudio Client）都 `compileOnly` 依赖它、
    运行时不 `include`（避免双份嵌套同 id 冲突）
  - MineUI 不在时注册表只是没人消费，业务客户端正常加载（惰性、无副作用）
- 不采用方案 b（业务客户端依赖整个 MineUI UI mod）

### 8.2 接口草案

```java
package com.mineui.client.api;

public interface ClientStateProvider {
    /** 客户端渲染/逻辑线程调用；无该键返回 null。实现必须无阻塞、无网络。 */
    Object get(String key);
}

public interface ClientActionHandler {
    /** 返回 true 表示已处理；false 交给默认逻辑（当前默认：忽略）。 */
    boolean handle(String action, java.util.Map<String, Object> payload);
}

public interface MineUiClientBridge {
    static MineUiClientBridge get();
    /** namespace 如 "mineaudio"；返回的 AutoCloseable 用于注销。 */
    AutoCloseable register(String namespace, ClientStateProvider state, ClientActionHandler actions);
    /** 供 MineUI HELLO 汇总上报的能力位（注册时自动加入，见 8.4）。 */
    void declareCapability(String capability);
}
```

- 绑定语法：`{local.<namespace>.<key>}`；动作：`action: "local:<namespace>.<action>"`，payload 与现有动作一致
- 生命周期：注销后对应绑定渲染为空串、动作忽略；业主停用/断开连接时 MineUI 清理

### 8.3 能力位与降级（确定）

- 能力位使用 **`local_state`（有状态提供者）+ `local_action`（有动作处理器）**，
  由 MineUI 客户端按注册表自动加入 HELLO caps。不用 `music_local`（业务专用，违背通用性）；
  命名空间是 API 内部概念，不进入能力位。
- 降级语义：
  - 旧客户端 / 未安装 MineUI 客户端 / 未注册命名空间 → `{local.*}` 渲染为空串
  - `local:` 动作被忽略并返回 `handled=false`：不弹错、不回传服务端
- 服务端通过 `local_state` 能力位决定下发“本地版”还是“服务端推送版”页面/控制

### 8.4 安全边界（确定，写死在文档与实现里）

- `local.*` 是客户端本地数据（可信本地 mod 提供），**只在本机解析，协议里不出现，服务端无法读取**
- `local:` 动作只派发给**同命名空间**的注册者，禁止跨命名空间派发
- 本地数据可被本机玩家篡改，但只影响该玩家自己；任何权威判定（全服同步、计分、权限）
  必须走服务端，不得依赖 `local.*`

### 8.5 MineAudio 侧接入（本次交付依赖它）

- MineAudio Client 注册命名空间 `mineaudio`：
  - state：`position` / `duration` / `playing` / `buffering` / `volume`（来自 0.1.17 起的播放时钟）
  - actions：`seek` / `pause` / `resume` / `volume`
- 页面（player.json / hud.json）在有 `local_state` 时使用 `{local.mineaudio.*}` 与
  `local:mineaudio.*` 动作；否则沿用当前服务端推送 + `SEEK/PAUSE/...` 协议回退

---

## 9. FR-13 封面旋转动画（2026-09-19 新增，待 MineUI 设计）

MineAudio 已用 `image` + `{state.cover}` 展示网易封面（圆形用 `radius` 实现，`music.126.net` 已白名单）。
希望补一个**通用持续旋转**动画属性，让任意节点按本地时间旋转（唱片效果），例如：

```json
{ "type": "image", "url": "{state.cover}", "width": 56, "height": 56, "radius": 28, "spin": 8.0 }
```

- `spin`：秒/圈，纯客户端本地时间驱动，不占网络；`0`/缺省不旋转
- 可选 `spinPlaying`（`{state.playing}` 绑定）：false 时冻结角度，供暂停场景使用
- 旧客户端遇到未知字段安全忽略

---

## 10. FR-14 输入框中文（IME）支持（2026-09-19 新增，MineUI 已交付待验收）

现象：MineUI 自绘输入框无法输入中文（IME 组词不激活）。

原因：原版 `EditBox` 在聚焦变化时会调用 `Minecraft.onTextInputFocusChange(listener, focused)`，
由 `TextInputManager` 开关 IME 并重放 preedit；自绘 `InputNode` 没有这一步。

建议修复（客户端）：

- 聚焦输入框：`Minecraft.getInstance().onTextInputFocusChange(listener, true)`
  （listener 需为 `GuiEventListener`，可传 `UiScreen`），并调用
  `textInputManager().setTextInputArea(x, y, w, h)` 将候选窗定位到输入框（GUI 坐标换算为窗口坐标）
- 失焦 / 关闭界面：`onTextInputFocusChange(listener, false)`
- `charTyped` 已能收到合成后的字符，无需改动
- 验收：Windows 微软拼音下可输入并提交中文；组词进行中按回车不误触发提交
- MineAudio 侧无需改动（聚焦自动激活）；搜索框中文待用户在 Windows 微软拼音下实机验收

## 11. FR-15 图片最近邻过滤（像素风）（2026-09-19 新增，MineUI 已交付）

MineAudio 的封面已改为 64px 缩略图（网易 `?param=64y64`）。希望 `image` 节点支持
`"filter": "nearest"`（或 `pixelated: true`）：放大时使用最近邻采样，风格更贴像素画且不糊；
实测结论（MineUI）：远程图基底与遮罩/着色变体恒为最近邻，
MineAudio 的 64px 封面天然是像素风——本仓库无需加 `filter` 字段；显式 `"linear"` 才走变体烘焙。


---

### 附：点分嵌套键（MineUI Deferred-(a)，已交付）
- `state("player.name", v)` 现在按嵌套路径处理；MineAudio 侧已核对：所有顶层状态键只用下划线
  （如 `srch0_name`），唯一带点的是文本拼接 `"{state.state}  {state.backend}  {state.origin}"`，
  不受语义变更影响。

## 12. FR-16 跨仓库架构评审意见（2026-09-19，待 MineUI 处理）

以下为 MineUI 侧问题（本仓库不实现，仅明确需求与验收点，供 MineUI 会话评审）：

### U1 Toast 兼容构造器（P0）
- 新增字段破坏了 Java API：现有 `Toast` 构造调用（MineAudio 错误提示路径）在新版本下抛 `NoSuchMethodError`。
- 要求：保留五参数兼容构造器（`new Toast(text, icon, action, duration, color)`），默认 `kind="info"`。

### U2 批次 PATCH 合并语义（P0）
- 同一批 PATCH 内对同一新字段`add`后又被`replace`，导致客户端丢第一份、无法应用。
- 要求：保留批次开始时的存在性判断，正确合并 add/replace 语义；验收：含新字段的批量 PATCH 在客户端可完整应用。

### U3 新皮肤未接入预设转换（P1）
- `skin` 字段写了新皮肤名，但解析层没有接入程序化皮肤和 `sprite9:` 转换，渲染分支按文档无法触达。
- 要求：在现有解析层接通两者，文档示例逐条可跑。

### U4 tint/UV/尺寸计算（P1）
- tint 路径用未补齐尺寸；动态纹理查的是资源文件尺寸；sprite 忽略 tint。
- 要求：统一目标矩形、源 UV、纹理尺寸与颜色计算；游戏内验证具体视觉。

### U6 列表条目身份 vs 控件值（P1）
- Tabs 用 `itemIndex` 存页签下标，会覆盖外层列表条目身份。
- 要求：条目点击动作携带独立条目索引（payload 字段），控件值另存；本仓库后续想把搜索结果迁移到动态 `List` 组件时需要它。

### U7 Checkbox/Switch/Tabs 的值读取时机（P1）
- 这类控件只在 `measure` 读取值；普通本地值变化时外观不刷新。
- 要求：非布局状态在绘制阶段读取，保证本地值变化即刷新。

### U8 最低客户端版本协商（P1，条件性）
- 旧客户端仍声明 `server_ui`，但解析不了新增控件会静默降级。
- 要求：若继续混用多版本客户端，补最低客户端版本检查或控件集能力协商；MineAudio 目前按 `local_state`/`mineaudio_local_v1` 能力位做页面回退。

### U9 绘制分支的透明度继承（P2）
- 新控件/皮肤/tint 路径忽略继承透明度。
- 要求：所有绘制分支统一乘最终 `opacity`。

### U5 遮罩资源释放（P1, MineUI 侧）
- 遮罩源 `NativeImage` 关闭时机不明；断线后异步烘焙任务可能重新注册纹理。
- 要求：明确资源所有权（谁创建谁释放），烘焙任务绑定连接代际，切服/断线后旧结果丢弃。

### R10 本地绑定失效机制（P1，MineUI 侧）
- `visible`/`enabled`/`list.items` 等是否随本地值变化重布局，需按“标量绑定每帧读、结构变化自增 generation”明确支持范围并补齐缺口。

## 13. 关于 MineUI `state()` 去重的说明（供 MineUI 会话确认）

- MineAudio 侧已按“值变化才推送”重写：本地模式跳过 position/time/playing/volume 的服务器推送，HUD 与页面去重刷新。
- 若 MineUI `state()` 改为“相等值不发包”，MineAudio 本地页面理论上不受影响：
  本地标量每帧读取、`progress_visible`/`active` 等布局开关只在跃迁时变化。
- **仍需实机验证一次**：播放 ⇄ 停止时进度行与 HUD 的显隐是否即时正确（0.14.0 客户端）。


---

## 14. FR-17 跨仓库评审 MineUI 侧问题（2026-09-20，待 MineUI 处理）

### U1 Toast 兼容构造器（P0）
- 五参数 `new Toast(text, icon, action, durationMillis, color)` 必须保留（旧业务插件按此编译的 jar
  运行时直接兼容；源码依赖者重编一次即可）。kind 缺省 `"info"`。

### U2 批次 PATCH 合并（P0）
- 同一批 PATCH 内对同一新字段 `add` 又被 `replace`，会让客户端丢第一份、无法应用。
- 要求：保留批次开始时的存在性，正确合并 add/replace；验收：含新字段的批量 PATCH 能完整应用。

### U3 新皮肤未接入预设转换（P1）
- `skin` 字段的新皮肤名未接入程序化皮肤与 `sprite9:` 转换，渲染分支按文档无法触达。
- 要求：在现有解析层接通，文档示例逐条可跑。

### U4 tint/UV/尺寸（P1）
- tint 路径用未补齐尺寸；动态纹理由资源文件查尺寸；sprite 忽略 tint。
- 要求：统一目标矩形、源 UV、纹理尺寸与颜色计算；游戏内验证具体视觉。

### U6 列表条目身份 vs 控件值（P1）
- Tabs 用 `itemIndex` 存页签下标，覆盖外层列表身份；MineAudio 目前用普通按钮切 Tab 不受影响，
  但后续想把搜索结果迁移到动态 `List` 时需要它。
- 要求：条目点击动作携带独立条目索引（payload 字段），控件值另存。

### U7 非布局状态读取时机（P1）
- Checkbox/Switch/Tabs 只在 measure 读取值，普通本地值变化时外观不刷新。
- 要求：非布局状态在绘制阶段读取。MineAudio 侧注意：暂停/音量等本地标量变化需即时反映，
  若 MineUI 侧未修，需要在每次变更后附带一次状态推送（已在 refresher 覆盖范围内）。

### U8 最低客户端版本协商（P1，条件性）
- 旧客户端仍声明 `server_ui`，但解析不了新增控件。MineAudio 目前按
  `local_state`/`mineaudio_local_v1` 能力位做页面回退。
- 要求：若继续混用多版本客户端，补最低客户端版本检查或控件集能力协商。
  MineAudio 声明的最低兼容：服务端/客户端 **0.14+**（见 README）。

### U9 绘制分支透明度继承（P2）
- 新控件/皮肤/tint 路径忽略继承透明度。
- 要求：所有绘制分支统一乘最终 `opacity`。

### U5 遮罩资源释放（P1）
- 遮罩源 `NativeImage` 关闭时机不明；断线后异步烘焙任务可能重新注册纹理。
- 要求：明确资源所有权（谁创建谁释放），烘焙任务绑定连接代际，切服/断线后旧结果丢弃。

### R10 本地绑定失效机制（P1，MineUI 侧）
- `visible`/`enabled`/`list.items` 等是否随本地值变化重布局，需按“标量绑定每帧读、
  结构变化自增 generation”明确支持范围并补齐缺口。

## 15. 关于 MineUI `state()` 去重的复核说明（供 MineUI 会话确认）

- MineAudio 侧已按“值变化才推送”重写：本地模式跳过 position/time/playing/volume 的
  服务器推送（见 0.2.7），HUD 与页面刷新合并。
- MineAudio 本地页面**不依赖**“每秒必发 PATCH”维持正确性：本地标量每帧读取，
  `progress_visible`/`active` 等布局开关只在跃迁时变化（会产生 PATCH）。
- 仍以实机验证为准：播放 ⇄ 停止时进度行与 HUD 的显隐是否即时正确。


---

落地后：MineAudio 侧只负责下发状态与动作。本需求建议同步到 mineUI 仓库 `docs/` 作为正式需求。
