# A2 交付：TS6 屏幕共享协议规格与手机端改动评估

> 状态：✅ 协议规格完成；Rust 改动范围已确定（含一处 tsclientlib 补丁需求）
> 来源（事实依据，全部为代码级核验）：
> - `pad/src/server/screen-share-coordinator.ts`（网关协调器，1039 行，逐段核对）
> - `pad/src/server/ts-client.ts`（`sendProtocolCommand` → SDK `execCommand` 原始命令通道）
> - `tslib/`（Rust 核心现状）与 `tsclientlib`（上游协议库，已克隆核验）
> 日期：2026-10-18

## 1. 结论摘要

1. TS6 屏幕共享是**客户端协议命令 + 服务端中转 WebRTC 信令 + P2P/TURN 媒体**。协议面共 **7 条命令 + 6 个通知**，本文档已逐参数锁定。
2. **发送方向无阻塞**：`tslib` 已具备发送任意协议命令的能力（`OutCommand::new(..., "<命令名>")` + `write_arg`（自动转义）），照现有文件命令模式扩展即可。
3. **接收方向有阻塞（必须解决）**：`tslib` 依赖的 `tsclientlib` 对**未知命令直接丢弃**（`handle_command` 中 `InMessage::new` 失败即 `warn` 后 return，见 `tsclientlib/src/lib.rs:1343-1350`）。TS6 的 `notifystream*` / `notifyjoinstreamrequest` 等通知不在其消息规格内，当前**无法到达** `tslib`。
4. 解决方案为**小型补丁 + 本地 vendor**：在 `tslib` 中内置一份 tsclientlib（含 tsproto 系列 crate），修补 `handle_command`：未知命令不再丢弃，而是作为新的 `StreamItem` 透传原始字节；`tslib` 用 `tsproto_packets::commands::CommandParser` 自行解析。改动约 20–40 行 Rust + 依赖指向调整。
5. Android 媒体层全部为成熟方案：WebRTC Android SDK（含现成的 `ScreenCapturerAndroid`）+ `SurfaceViewRenderer` + `MediaProjection` 前台服务。工作量在集成，不在自研算法。

## 2. 协议总览

```
手机（发布者）                 TS6 服务器                   观看者（原生 TS6 / WebSpeak）
   │  setupstream ────────────►│                                 │
   │◄── notifystreamstarted ───│（流有全局 id）                    │
   │◄── notifyjoinstreamrequest│◄──── joinstreamrequest ──────────│
   │──── respondjoinstreamrequest (offer SDP) ──►│（转发给观看者）   │
   │◄─── notifystreamsignaling (answer / ICE) ───│──── streamsignaling ──►│
   │  ══════════════ WebRTC P2P / TURN 媒体直连（不经过服务器）══════════════►
```

- **发现**：观看方或任何客户端可发 `requeststreaminfo clid=<客户端id>` 查询某人是否在直播；回包为 `notifystreaminfo`。流开始时服务端会广播 `notifystreamstarted`。
- **信令**：SDP / ICE 全部经服务端文本命令中转；媒体走 WebRTC P2P，默认 STUN `stun:turn.teamspeak.com:3478`，可配置外置 TURN。
- **流的绑定**：流与**服务器 + 频道**绑定；来源切换频道、观看者切换频道即离开；频道是隐式的可见性范围。

## 3. 命令参考（手机端需要发送的 7 条）

| # | 命令 | 参数（全部为字符串） | 用途 | 发送时机 |
| --- | --- | --- | --- | --- |
| C1 | `requeststreaminfo` | `clid`（目标客户端 id） | 查询某人直播状态 | 进入频道/需要发现时 |
| C2 | `setupstream` | `name`、`type=3`、`bitrate=4608`、`accessibility=1`、`mode=1`、`viewer_limit=0`、`audio=0/1` | 发起发布（请求建流） | 用户点"开始共享" |
| C3 | `joinstreamrequest` | `id`（流 id）、`clid`（流来源客户端 id）、`msg=""`、`is_remove=0`、`muted=0`、`volume=0`、`hidden=0` | 观看者请求加入流 | 用户点"观看" |
| C4 | `respondjoinstreamrequest` | `id`、`clid`（观看者客户端 id）、`msg=""`、`offer=<SDP>`、`decision=1` | 发布者接受观看并下发 offer | 收到 C-Notify-1 后 |
| C5 | `streamsignaling` | `id`、`clid`（对端客户端 id）、`json={"cmd":...,"args":{...}}` | 中转 answer / ICE 候选 | 信令全阶段 |
| C6 | `removeclientfromstream` | `id`、`clid`（观看者）、`reason=1` | 移除观看者（或拒绝） | 发布者主动断开某人 |
| C7 | `stopstream` | `id`、`reason=1` | 结束共享 | 用户点"停止共享" |

参数值说明（`pad/src/server/screen-share-coordinator.ts`）：
- C2 的固定值直接沿用 WebSpeak 已验证组合（`type=3` 视频流、`bitrate=4608`、`accessibility=1`、`mode=1`、`viewer_limit=0` 不限人数、`audio` 表示是否附带系统声音）。
- 自定义字段（如 `name`）含空格/特殊字符时需按 TS 转义；**tsproto 的 `OutCommand::write_arg` 已自动转义**（`utils/tsproto-packets/src/packets.rs:1012-1026`），实现时无需手写转义。

## 4. 通知参考（需要接收解析的 6 个）

| # | 通知名 | 参数 | 含义 | 面向 |
| --- | --- | --- | --- | --- |
| N1 | `notifystreamstarted` | `id`/`stream_id`、`clid`（来源）、`name`、`audio` | 某流开始（也是自己 `setupstream` 的回执，用于拿到流 id） | 所有人 |
| N2 | `notifystreaminfo` | 同 N1 | `requeststreaminfo` 的应答 | 查询方 |
| N3 | `notifystreamstopped` | `id`/`stream_id` | 流结束 | 所有人 |
| N4 | `notifyjoinstreamrequest` | `id`/`stream_id`、`clid`（观看者） | 有人请求观看我的流 | **发布者** |
| N5 | `notifyrespondjoinstreamrequest` | `id`/`stream_id`、`offer`（SDP 直接内联） | 来源接受的答复（内含 offer SDP） | 观看者 |
| N6 | `notifystreamsignaling` | `id`/`stream_id`、`clid`（对端）、`json`/`data` | 对端信令（answer / ICE） | 双方 |
| N7 | `notifystreamclientleft` | `id`/`stream_id`、`clid`（观看者） | 观看者离开 | 发布者 |

兼容细节（重要）：
- 流 id 字段名**两种都可能出现**：先取 `id`，否则取 `stream_id`。
- 信令载荷字段名**两种都可能出现**：先取 `json`，否则取 `data`；且 `params.offer`（N5）可直接使用。
- `notifyrespondjoinstreamrequest` 的 SDP 在参数 `offer` 中，对应信令 cmd 为 `joinResponse`（见下节归一化）。

## 5. 信令 JSON 模式（`streamsignaling` 的 `json` 参数）

```jsonc
// 通道一：对端在我的流上（我作为发布者收到原生观看者信令，或我作为观看者发给来源）
{ "cmd": "iceCandidate", "args": { "sdp": "<candidate>", "mid": "<sdpMid>", "mLine": 0 } }
{ "cmd": "answer",      "args": { "answer": "<SDP>" } }

// 通道二：我作为观看者收到的（来自来源）
{ "cmd": "joinResponse",   "args": { "decision": 1, "offer": "<SDP>" } }   // 接受（decision=0 为拒绝）
{ "cmd": "offer",          "args": { "offer": "<SDP>" } }                  // 或 args.sdp
{ "cmd": "reconnectOffer", "args": { "offer": "<SDP>" } }                  // 重连变体，按 offer 处理
{ "cmd": "iceCandidate",   "args": { "sdp": "<candidate>", "mid": "...", "mLine": 0 } }
```

归一化规则（`toBrowserScreenSignal`，`screen-share-coordinator.ts:993-1025`）：
- `joinResponse` 且 `decision` 为 `0/false/"0"` → **拒绝**（对端放弃）；
- `joinResponse` / `offer` / `reconnectOffer` → 一律按 **offer** 处理（内部字段 `offer` 或 `sdp` 二选一）；
- `answer` 字段名 `answer` 或 `sdp` 二选一；
- `iceCandidate` 字段名 `sdp` 或 `candidate` 二选一；`mid` 仅字符串时携带；`mLine` 仅数字时携带。

## 6. 端到端流程（手机视角）

### 6.1 手机观看别人的流（观看端）
1. 发现：进入频道或手动刷新 → C1 `requeststreaminfo clid=<某人>` → N2（或被动收到 N1）。
2. 用户点观看 → C3 `joinstreamrequest`。
3. 来源接受 → 收到 N5 `notifyrespondjoinstreamrequest`（`params.offer` 为 SDP）→ 创建 `RTCPeerConnection`，`setRemoteDescription(offer)` → `createAnswer` → C5 `streamsignaling json={"cmd":"answer",...}`。
4. ICE：本地候选 → C5 `streamsignaling json={"cmd":"iceCandidate",...}`；对端候选经 N6 到达 → `addIceCandidate`。
5. 流结束：N3（来源停）或自己停止时静默清理；离开频道时同样清理。

### 6.2 手机发布、他人观看（发布端）
1. 用户点"开始共享" → 申请 `MediaProjection` 授权 → C2 `setupstream`（`name` 用设备昵称/系统型号）。
2. 服务端回 N1 `notifystreamstarted`（含流 id）→ 记录下来（可能是稍后异步到达，需容错）。
3. 原生/Web 观看者加入 → N4 `notifyjoinstreamrequest`（`clid`=观看者）→ 为其创建独立 `PeerConnection` + `addTrack(屏幕视频轨)` → `createOffer` → C4 `respondjoinstreamrequest offer=<SDP> decision=1`。
4. 观看者应答 → N6 `notifystreamsignaling`（`cmd=answer` 或 `iceCandidate`）→ 对应 PC 的 `setRemoteDescription` / `addIceCandidate`；本地 ICE 同样经 C5 发回（`clid`=观看者）。
5. 观看者关闭 → N7 `notifystreamclientleft` 清理该 PC；主动拉黑 → C6 `removeclientfromstream`。
6. 用户停止共享 → C7 `stopstream`，销毁所有 PC 与采集；若收到"发布超时未确认"（N1 迟迟不来）→ 重试或报错退出。

### 6.3 会话约束（与 WebSpeak 保持一致）
- 一个客户端同一时刻**只发布一路流**；观看可多路并发。
- 流与频道绑定；发布者/观看者切频道即断开。
- 媒体连接：每对（发布者, 观看者）一个独立 PeerConnection（不要把多个观看者混在一个 PC 里）。

## 7. 手机端实现映射与改动清单

### 7.1 Rust 层（`tslib/`）——主要改动

| 项 | 内容 | 位置 |
| --- | --- | --- |
| R1 | **vendor tsclientlib**（含 `tsclientlib`/`tsproto`/`tsproto-packets`/`tsproto-structs`/`ts-bookkeeping` 五个 crate），记录上游 SHA | 新增 `tslib/vendor/tsclientlib/` |
| R2 | 补丁：`StreamItem` 新增 `UnknownCommand { name, data }`；`handle_command` 解析失败分支中，对 `ParseError::UnknownCommand` 透传原始命令字节（其余错误保持原行为：warn + 丢弃） | `vendor/.../tsclientlib/src/lib.rs:1339-1350` |
| R3 | tslib 依赖从 git 改为 path 指向 vendor；确认 `Cargo.lock` 稳定 | `tslib/Cargo.toml` |
| R4 | 新增 `screen_share` 模块：7 条命令构造器（照 `subscribe_all_channels` 模式，2–5 行/条）+ 6 个通知解析器（`CommandParser` 解析 + 二进制转义由 parser 处理） | `tslib/crates/tslib-core/src/` |
| R5 | `process_stream_item` 增加 `UnknownCommand` 分支 → 映射新事件（`ScreenShareStreamStarted/Info/Stopped`、`JoinRequest`、`Signal{json}`、`ViewerLeft`） | `tslib-core/src/client.rs:337`、`events.rs` |
| R6 | 公开 API：`request_stream_info` / `setup_stream` / `stop_stream` / `join_stream_request` / `respond_join_stream_request` / `stream_signaling` / `remove_client_from_stream` | `tslib-core/src/client.rs` |

**实施状态（2026-10-18）**：

- ✅ **R1–R3 完成**：vendor 于 `tslib/vendor/tsclientlib/`（上游 `ee3bc6f4`，含 declarations 子模块 `83cb8a9`；
  补丁内容与升级步骤见 `tslib/vendor/tsclientlib/VENDOR-INFO.md`）；`tslib/Cargo.toml` 改 path 引用并
  `exclude` vendor 树（修复 vendor 的 dev-dependencies 引发的 `jni-sys` 解析死锁）；`tslib/Cargo.lock` 已提交。
- ✅ **R4–R6 完成**：`tslib-core/src/screen_share.rs`（7 命令构造 + 7 通知解析 + 信令 JSON 构造 + 12 项单元测试）；
  `Event::ScreenShare(ScreenShareEvent)` 已接入 `process_stream_item` 的 `UnknownCommand` 分支；
  `Client` 新增 7 个屏幕共享方法。验证：`cargo check -p tslib-core` 通过；
  `cargo test -p tslib-core` 全量 63 项 + screen_share 12 项全部通过；`cargo check -p tslib-jni` 通过。
- ✅ **JNI 事件映射**：`tslib-jni` 将新事件映射为 `screen_share` 类型（结构化数据以 `json` 字段承载）；
  Python 绑定同步映射。
- ✅ **JNI 命令函数（2026-10-18 完成）**：9 个 `Java_dev_tslib_Client_native*`（7 命令 + `sendScreenShareAnswer`
  + `sendScreenShareIceCandidate`，后两者在 Rust 侧组装 `answer` / `iceCandidate` 信令 JSON，避免 Kotlin 重复实现协议）；
  Java 绑定（`tslib/java` 与 `phone` 两份 `Client.java`）与 Kotlin 包装（`TsClient.kt`）同步完成。
  验证：`cargo check -p tslib-jni` 通过；release `.so`（arm64-v8a，strip 后 9.7 MB）已重编并替换
  `phone/app/src/main/jniLibs/arm64-v8a/libtslib_jni.so`，`llvm-nm` 确认 9 个符号全部导出。
- 🔄 **端到端验证**：探针 `tslib/examples/screen-share-probe` 已就绪，待在真实 TS6 服务器上执行
  （A2b 验收：`--publish` 应收到 `StreamStarted` 通知；双端 `--publish`/`--join` 应触发 `JoinRequest`）。
- 🔄 **§7.3 Android 媒体与 UI（A3/A4）**：实现已完成、编译通过，待真机与对端互通验证（见 §7.3 实施状态
  与 `docs/A3-screen-share-android.md`）。

预计量级：**Rust 约 300–500 行新增 + vendor 树上 20–40 行补丁**。

### 7.2 JNI 与 Java 绑定（`tslib/crates/tslib-jni` + `tslib/java`）
- 新命令 → 新 JNI 函数（照现有 `nativeSendChannelMessage` 模式）；新事件 → `create_java_event`（`tslib-jni/src/types.rs:137`）扩展映射。
- 手机 App 侧 `phone/.../bridge/TsClient.kt` 已有事件分发框架与 `launchNativeCommand` 模式（`TsClient.kt:457`），新增事件分支与命令包装即可。

**实施状态（2026-10-18）**：

- ✅ 9 个 JNI 命令函数已实现于 `tslib-jni/src/client.rs`（见 §7.1）；两份 `Client.java` 同步新增公开方法
  与 native 声明；`TsClient.kt` 新增 `screenShareEvents` 事件流（replay=16）与 9 个命令包装。
- ✅ 事件桥：`TsClient.kt` 的 `handleEvent` 新增 `screen_share` 分支，将 Rust 侧 Serde JSON 解析为
  `ScreenShareEvent`（`ScreenShareEvents.kt`，宽松解析；未知负载仅记日志）。

### 7.3 Android 媒体与 UI（`phone/app`）
| 项 | 方案 |
| --- | --- |
| WebRTC | 引入 WebRTC Android 发行版（`io.github.webrtc-sdk:android` 或同类），自带 `ScreenCapturerAndroid`（MediaProjection 采集）与 `SurfaceViewRenderer`（渲染） |
| 采集授权 | `MediaProjectionManager.createScreenCaptureIntent()`；Android 14+ 需 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 类型的前台服务 + 每次授权一次性令牌 |
| 转发/渲染 | 观看：远端视频轨绑定到 `SurfaceViewRenderer`（自绘窗口，支持旋转/全屏）；发布：本地轨可选预览（不强制） |
| 保活 | 前台服务（`mediaProjection` + `microphone` 类型按目标版本声明），与现有音频服务协调 |
| UI | 成员卡片/成员详情加"观看"入口与"直播中"角标；频道/悬浮窗显示共享状态；发布入口放主界面 |
| STUN/TURN | 默认 `stun:turn.teamspeak.com:3478`；预留自定义 ICE 服务器设置项（复用 `setupstream` 无关，属本地 PC 配置） |

预计量级：**Kotlin/JNI 绑定约 300–600 行 + UI 约 500–800 行 + 媒体集成约 400–700 行**。

**实施状态（2026-10-18）**：

- ✅ **已实现**（编译通过；详细设计与验证清单见 `docs/A3-screen-share-android.md`）：
  - WebRTC：`io.github.webrtc-sdk:android:150.7871.01`（Chromium M150；125+ 支持 16 KB 页）；
  - 发布端 `ScreenShareController`（每观看者一条 PeerConnection，`respondjoinstreamrequest` 携带 offer）
    + `ScreenShareService`（`mediaProjection` 前台服务；Android 14+ 顺序：授权 → startForeground → getMediaProjection）；
  - 观看端：`joinStream`/`leaveStream` + `SurfaceViewRenderer` 渲染（`ScreenShareViewerDialog`）；
  - UI：服务器界面顶栏共享开关（含 MediaProjection 授权流程）+ 远端流横幅"观看"入口；
  - ICE：默认 TS 公共 STUN（与 WebSpeak 默认一致）；`sdpSemantics=UNIFIED_PLAN`；
  - 构建：Gradle `buildRustLibs` 修正（`../tslib`、`--release`、arm64-v8a、Ninja 生成器）。
- ⬜ **待完成/待验证**：真机验证（授权、采集、与 WebSpeak / TS6 原生客户端互通）；
  `withAudio=false`（系统声音采集需 `AudioPlaybackCapture`，后续版本）；观看端旋转/全屏适配；
  自定义 ICE/TURN 设置项；发布码率调优。

## 8. 风险清单（进入 A3 前先验证）

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| V1 tsclientlib 补丁验证 | 假设 TS6 服务端确实把这些通知作为标准命令包下发（WebSpeak 通过 SDK 原始通道已证实可收，但 Rust 栈需实机验证） | A2b 原型：补丁 + 打日志跑真实服务器 |
| V2 通知名与字段 | 已按 WebSpeak 生产代码锁定，但 TS6 版本演进可能新增字段/通知 | 解析保持宽松（未知字段忽略、未知 cmd 忽略） |
| V3 WebRTC 兼容性 | 与 TS6 原生客户端（H.264/AV1?）及浏览器（WebSpeak）互通 | 优先 VP8/H264 双编码；用 WebSpeak 实例做首轮互通目标 |
| V4 Android 14+ 前台服务限制 | `mediaProjection` 类型限制多 | 提前在目标机型验证 |
| V5 发现机制 | 是否需要定期 `requeststreaminfo` 轮询（WebSpeak 有主动查询逻辑） | 进入频道时批量查一次 + 被动 N1 |

## 9. 验证环境需求（A3/A4 验收用）
- 一个支持屏幕共享的 **TS6 服务器**（TS6 beta 或已启用该能力的社区服务器）；
- 一个 **TS6 原生桌面客户端**（做发布/观看对端）；
- 一个 **WebSpeak 实例**（做浏览器对端；也可用官方演示站）。
