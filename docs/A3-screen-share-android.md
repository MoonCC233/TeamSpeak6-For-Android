# A3 交付：Android 屏幕共享媒体层与 UI（发布端 + 观看端）

> 状态：**实现完成；真机与 TS6 原生客户端双向互通验收通过**（2026-10-18）。
> 协议事实来源见 `docs/A2-screen-share-protocol.md`；本文只描述媒体层与 UI 设计。
> SDP 兼容性根因与单因素变体矩阵见 §6。

## 1. 范围

- **A3（发布端）**：MediaProjection 采集 → WebRTC 编码 → 每观看者一条 PeerConnection → SDP/ICE 经
  `respondjoinstreamrequest` / `streamsignaling` 中转；对端（TS6 原生客户端 / WebSpeak）可见可看。
- **A4（观看端）**：发现直播流（`notifystreamstarted` 等）→ `joinstreamrequest` → 收到 offer 回 answer →
  `SurfaceViewRenderer` 渲染；含观看窗口入口。
- **不在本次范围**：系统声音采集（`withAudio=false`）、发布码率精细调优、自定义 TURN 设置项、
  `joinstreamrequest is_remove=1` 主动退订（依赖协议侧扩展）。

## 2. 组件与文件清单

| 层 | 文件 | 职责 |
| --- | --- | --- |
| Rust（协议） | `tslib/crates/tslib-core/src/screen_share.rs` | 7 命令构造 / 7 通知解析 / 信令 JSON 构造（A2b 已交付） |
| Rust（JNI） | `tslib/crates/tslib-jni/src/client.rs` | 9 个 `native*` 命令函数（含 `SendScreenShareAnswer` / `SendScreenShareIceCandidate`） |
| Java 绑定 | `tslib/java/.../dev/tslib/Client.java`、`phone/app/src/main/java/dev/tslib/Client.java`（两份同步） | 公开方法 + native 声明 |
| Kotlin（桥） | `phone/.../bridge/ScreenShareEvents.kt` | `ScreenShareEvent` / `ScreenShareSignal` 模型 + 宽松 JSON 解析 |
| Kotlin（桥） | `phone/.../bridge/TsClient.kt` | `screenShareEvents` 事件流（replay=16）+ 9 个命令包装 |
| Kotlin（媒体） | `phone/.../screen/ScreenShareController.kt` | WebRTC 会话编排（发布端每观看者 PC / 观看端 PC）、信令分发、状态流 |
| Kotlin（服务） | `phone/.../service/ScreenShareService.kt` | `mediaProjection` 前台服务（授权会话承载 + 通知栏停止按钮） |
| Kotlin（UI） | `phone/.../ui/screen/ServerScreen.kt` | 顶栏共享开关（授权流程）+ 远端流横幅"观看"入口 |
| Kotlin（UI） | `phone/.../ui/component/ScreenShareViewerDialog.kt` | 全屏观看窗口（`SurfaceViewRenderer`，进出组合即订阅/退订） |
| 资源 | `res/values{,-en,-zh-rCN}/strings.xml`、`ui/icon/AppIcons.kt` | 三语文案 + `ScreenShare` 图标 |
| 清单 | `AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限；`ScreenShareService`（`foregroundServiceType="mediaProjection"`） |

## 3. 端到端流程

### 3.1 发布端（A3）

1. 用户在服务器界面点顶栏共享图标 → `MediaProjectionManager.createScreenCaptureIntent()` 征求授权；
2. 授权通过 → `ScreenShareService.start(...)` 启动 `mediaProjection` 前台服务（**先 startForeground，再
   getMediaProjection/createVirtualDisplay**，Android 14+ 合规；每次会话均需重新授权）；
3. 控制器创建 `ScreenCapturerAndroid`（`SurfaceTextureHelper` + `VideoSource(isScreencast=true)`），
   分辨率等比缩放至 ≤1280×720（偶数对齐）、30 fps；
4. 发送 `setupstream(name, has_audio=false, bitrate=4608, ...)`；服务端回 `notifystreamstarted`（含
   `stream_id`）后进入直播态；
5. 每收到 `notifystreamjoinrequest(viewer_id)`：为该观看者创建 PC → `addTransceiver` 添加视频轨
   （方向 **SEND_ONLY**）→ 追加 `INACTIVE` 占位音频 m= 线 → `createOffer` → 视频方向兜底校正 +
   `setLocalDescription` → `respondjoinstreamrequest(stream_id, viewer_id, offer)`（兼容性要求见 §6）；
6. 观看者回 `answer`（`streamsignaling`）→ `setRemoteDescription`；双向 ICE 候选经 `iceCandidate` 信令中继；
7. 停止：本地 `stopstream(stream_id)` + 释放采集/PC + 关闭前台服务（通知栏"停止"同路径）。

### 3.2 观看端（A4）

1. `notifystreamstarted` / `notifystreaminfo` → `remoteStreams` 状态流 → 顶栏下方"观看"横幅 +
   成员行「直播」角标 / 频道行直播标记（`ChannelTree` 接收 `streamingUserIds`）；
2. 点击观看（横幅 / 成员角标 / 用户面板「观看屏幕共享」）→ `joinStream()` 创建 PC 并发送
   `joinstreamrequest(stream_id, source_client_id)`；
3. 收到 `notifyrespondjoinstreamrequest(offer)` → `setRemoteDescription` → `createAnswer` →
   `setLocalDescription` → 经 `sendScreenShareAnswer` 回发（Rust 侧组装 JSON）；
4. 远端视频轨到达（`onAddTrack`）→ 以**首帧检测包装**（`FirstFrameSink`）绑定
   `SurfaceViewRenderer`：首帧真正渲染后才把会话阶段切到 `WATCHING`，避免"已连接但黑屏"
   被当成观看成功；换绑渲染器（重组）时自动解挂旧 sink 再挂新渲染器；
5. 观看窗口 `ScreenShareViewerOverlay`（全屏覆盖层，非 Dialog）：
   - 沉浸全屏：隐藏系统栏（可上滑唤出）、保持屏幕常亮、退出时恢复系统栏与屏幕方向；
   - 控制条：单击画面切换显隐，观看中 3.5 秒无操作自动隐藏；
   - 「适应 / 填充」缩放切换（`SCALE_ASPECT_FIT` / `SCALE_ASPECT_FILL`）；
   - 「旋转」按钮：传感器横屏 ↔ 跟随系统（`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` /
     `UNSPECIFIED`），退出时恢复进入前的方向；
   - 阶段提示（由 `ScreenShareController.viewerPhases` 驱动）：加入中 / 连接画面中 /
     重连中 / 直播已结束 / 被拒绝 / 连接失败；「已结束」「被拒绝」3 秒后自动收起；
6. 关闭窗口（返回键 / 关闭按钮）→ `leaveStream()` 关闭 PC、解绑 sink、释放 EGL；
   发布方停止（`StreamStopped`）→ 观看会话释放但保留阶段与流快照，窗口显示「直播已结束」；
   加入请求 20 秒无应答 → 阶段置为 `FAILED`（提示「无法连接到该直播」）。

> **旋转不断流的实现前提**：`MainActivity` 声明
> `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`
> —— Activity 不因旋转重建，观看会话/渲染器保持；否则旋转会触发整窗重组与重新加入。

## 4. 关键设计决策

| 决策 | 说明 |
| --- | --- |
| 信令 JSON 生成位置 | `answer` / `iceCandidate` 的 JSON 在 **Rust 侧**组装（`answer_signal_json` / `ice_candidate_signal_json`），Kotlin 只传原子参数——协议知识单点维护 |
| 线程模型 | 所有 WebRTC 对象操作串行化到单线程 `ScreenShareSignal`（信令线程语义）；事件消费在协程，再投递到该线程 |
| ICE 服务器 | 与 WebSpeak 默认一致：`stun:turn.teamspeak.com:3478` / `stun:turn2.teamspeak.com:3478`（STUN-only，媒体 P2P）；预留后续设置项 |
| SDP 语义 | `UNIFIED_PLAN` + `GATHER_CONTINUALLY` |
| 前台服务拆分 | 新增独立 `ScreenShareService`，不改动 `TsConnectionService`（避免与语音/悬浮窗生命周期纠缠；`TsConnectionService` 仅负责 `attach/detach/warmUp`） |
| 事件流 replay | `screenShareEvents` 使用 `replay=16`，UI 订阅晚于事件到达时仍能恢复直播列表；断线时 `resetReplayCache()` |
| WebRTC 依赖 | `io.github.webrtc-sdk:android:150.7871.01`（Maven Central / aliyun public 镜像可解析；125+ 支持 16 KB 页大小） |
| 编码器 | 默认 `DefaultVideoEncoderFactory`（软/硬 VP8/H.264 自适应） |

## 5. 构建与验证现状

- **JNI 符号**：`llvm-nm -D` 验证 release `.so`（arm64-v8a，strip 后 **9.7 MB**）导出全部 9 个屏幕共享函数。
- **构建修复**（相对上游脚本的差异）：
  - 去除 `--features vendored-openssl`：依赖树为纯 Rust 加密栈（rustls/aws-lc-rs），`openssl` 仅是
    tslib-jni 的未引用可选依赖；
  - Windows 交叉编译必须 `CMAKE_GENERATOR=Ninja`（默认 VS 生成器会在 `audiopus_sys` 处失败）；
  - `CARGO_PROFILE_RELEASE_STRIP=symbols`（debug 版 .so 高达 193 MB，strip 后 9.7 MB）；
  - Gradle 任务 `buildRustLibs` 已同步（`../tslib`、`--release`、arm64-v8a、Ninja 路径、SDK 路径读取 `local.properties`）。
- **编译验证**：`cargo test -p tslib-core`（63 + 12 项）、`cargo check -p tslib-jni`、
  `gradlew :app:compileDebugKotlin`、`:app:assembleDebug` 均通过。
- **单测**：`ScreenShareEventParserTest`（15 项：7 种事件 + 5 种信令 + null `mid`/`m_line` + 未知/畸形负载）通过；
  `gradlew :app:testDebugUnitTest` 全量 61 项通过（Android 单测经 `testImplementation(org.json:json)`
  提供真实 JSON 实现——android.jar 内的 org.json 是抛异常的桩）。

## 6. TS6 对端 SDP 兼容性（真机实测，2026-10-18）

> **结论先行**：TS6 原生客户端收到发布端 offer 后会运行「会话版本改写 + SDP 校验重写」管线
> （`StreamP2P::HandleRemoteOffer` → `MangleSessionVersion`），**结构不符即静默拒收**——不产生
> 任何错误回复与 ICE 候选，重试只会得到 `5638 AlreadyJoined`（真因在客户端 debug 级日志
> `MangleSessionVersion failed`）。手机端发布 offer 的硬性要求：
>
> 1. **必须包含第二条 `m=audio` 媒体线**（内容可极简，`a=inactive` 即可——V5 ❌ / V6 ✅）；
> 2. **视频媒体段方向必须为 `a=sendonly`**（`a=sendrecv` 被静默拒收——单因素 W2 ❌）；
> 3. offer 必须以 `\r\n` 结尾（探针实测：尾部少一个 CRLF 即被拒，3587 ❌ / 3589 ✅）。
>
> 其余手机端特征全部被接受：ICE 凭据含 `/`、扩展头 1–11、`msid:screen screen0`、
> `ice-options … renomination`、手机编解码表（含 H265 / red / ulpfec）、手机 ssrc/cname、
> 缺少 `rtcp-xr`/`rrtr`、多编解码音频段（8 个 PT）。

### 6.1 单因素变体矩阵（探针回放 → TS6 桌面客户端）

判定口径：客户端日志出现 `HandleRemoteOffer ENTRY` → `SetRemoteDescription CALL` →
`REMOTE-DESC-SET` → `ANSWER-SENT` 序列 = **接受**；仅 `ENTRY` 后静默 = **拒绝**。

| 变体 | 构造（相对基线单因素差异） | 大小 | 结果 |
| --- | --- | --- | --- |
| V0 | 原生客户端精确样本（对照） | 3589 | ✅ |
| V5 | 原生去音频 m= 线 | 2684 | ❌ 音频线必需 |
| V6 | 原生视频 + 极简音频（inactive） | 3413 | ✅ |
| V7 | 手机视频原样 + 极简音频 | 3332 | ❌ |
| V8 | 原生视频 + 原生音频去扩展头 | 3455 | ✅ |
| V9 | 手机视频原样 + 原生音频 | 3508 | ❌ |
| W1 | V6 + 手机 ICE 凭据（含 `/`） | 3413 | ✅ |
| W2 | V6 + 视频 `a=sendrecv` | 3413 | ❌ **唯一毒因素** |
| W3 | V6 + 手机 msid `screen screen0` | 3298 | ✅ |
| W4 | V6 + 扩展头 1–11（11 条） | 3799 | ✅ |
| W5 | V6 + `ice-options … renomination` | 3439 | ✅ |
| W6 | V6 + 手机编解码块（含 H265） | 3317 | ✅（mangle 运行 +36B） |
| W6b | 同 W6，去 H265 | 3093 | ✅ |
| W7 | V6 + 手机 ssrc/cname | 3409 | ✅（mangle 运行 +168B） |
| W8 | V6 去视频 rtcp-xr / rrtr | 3135 | ✅ |
| W10 | **手机视频原样 + `sendonly` + 极简音频** | 3332 | ✅ 决定性 |

> 第一轮 V1–V4（手机原样 / ICE 去 `/` / native msid 等）全拒——当时为「`sendrecv` + 缺音频线」
> 叠加效应，已由 W2 与 V5/V6 对照精确归因。W6/W7 中 mangle 管线实际运行并成功，证明手机
> 编解码块与 ssrc 完全可通过该校验。

### 6.2 手机端最终实现（`ScreenShareController.handleJoinRequest`）

- **视频方向**：`addTransceiver(track, RtpTransceiverInit(SEND_ONLY, listOf("screen")))`
  ——创建即定型 `a=sendonly`。**踩坑**：`addTrack` 后补 `setDirection(SEND_ONLY)` 实测未生效
  （首轮真机 offer 仍为 `a=sendrecv`），故必须用 `addTransceiver`；
- **占位音频线**：`addTransceiver(MEDIA_TYPE_AUDIO, RtpTransceiverInit(INACTIVE))`
  ——不发也不收音频，仅满足媒体线结构要求；
- **兜底**：`ensureVideoSendOnly()` 若发现视频段仍为 `a=sendrecv` 则原位改写；改写后交给
  `setLocalDescription`（保证线上字节与信令一致）。实测引擎已直接输出 `a=sendonly`
  （`rewritten=false`），兜底未触发；
- **留档**：最终 offer 原样写入
  `/sdcard/Android/data/com.yuaxi.ts6droid.cn/files/screenshare/last-offer.sdp`，
  `adb pull` 可做字节级核对（logcat 环形缓冲滚动极快，核查优先用该文件）。

### 6.3 实测 offer 结构（3525 字节，22:00 验收样本）

```
v=0
o=- 5608053823736516444 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0 1
a=extmap-allow-mixed
a=msid-semantic: WMS screen
m=video 9 UDP/TLS/RTP/SAVPF 96 97 39 40 98 99 100 101 43 44 103 104 107
…（a=mid:0 / a=extmap:1–11 / a=sendonly / a=msid:screen screen0 / rtpmap·fmtp·rtcp-fb …）
m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126
…（a=mid:1 / a=inactive / a=rtpmap:111 opus/48000/2 … / rtcp-fb·fmtp …）
```

要点：`sendonly ×1`、`sendrecv ×0`、`inactive ×1`、BUNDLE `0 1`、文件尾 `0D 0A`。

### 6.4 验收记录

- **发布方向（A3）**：手机（vivo V2505A，用户 `V2505A-Android`）→ TS6 桌面客户端
  （MoonCC233，clid 141）：`ENTRY 3525 → SRD-CALL 3561（mangle 运行）→ SRD-OK → ANSWER-SENT`，
  **画面可见**（21:54、22:00 两轮）；手机端日志
  `Offer for viewer 141: 3525B sendonly=true audioLine=true crlfEnd=true rewritten=false`。
- **观看方向（A4）**：桌面客户端发布 → 手机观看：`Join requested` → `Answer sent` →
  `Viewer ICE connected`，**画面可见**。
- 仍未覆盖：WebSpeak 实例互通（待可用实例）。

### 6.5 对端解码能力与编解码协商（2026-10-18 补充定位）

> **结论先行**：TS6 桌面客户端（CEF + FFmpeg 工具链）**只解 VP8 / VP9 / AV1**；
> 手机端 VP8/VP9 只有软编码器，而硬件编码器仅 H264/H265。因此**两端唯一交集是 VP8 软编**。

- **现象与证据**：手机默认码表 H264 置顶时，桌面端 `Trying to create decoder for unsupported
  format. Codec name: H264 …` → 落入 `NullVideoDecoder` → 画面黑屏、帧率 0；H265 则直接在
  answer 里被剔除（桌面 answer 恒等于 offer 减去 H265）。VP8 实测可正常建解码器并出画面。
- **修复**：`applyVideoCodecPreferences` 剔除 H264/H265（**连同其 apt 关联的 rtx 条目**）
  并把 VP8 置顶（保留 VP9/AV1/red/ulpfec）。
- **踩坑**：传码表时**必须连同 rtx / red / ulpfec 一起传入**，否则这些条目会被挤出 offer——
  曾出现 `m=video … 100 43`（仅 H264/H265、无任何重传条目）的 2586B 坏形态，
  重传 / FEC 能力归零；修复后 offer 恢复 ~3.5KB 正常形态
  （`m=video 9 UDP/TLS/RTP/SAVPF 96 97 39 40 98 99 103 104 107`，含全部 rtx/red/ulpfec）。


## 7. 后续工作

1. 系统声音采集（`AudioPlaybackCapture`，`withAudio=true`）——协议与 JS 侧已支持；
2. 自定义 ICE/TURN 设置项（对齐 WebSpeak 的管理端配置语义）；
3. 发布码率自适应与统计（`getStats` 指标上屏）；
4. B6：与 Pad 端发布桥共享媒体层设计（信令结构与控制器接口保持一致）。

> 已完成（2026-10-19）：观看入口增强（成员「直播」角标 / 频道直播标记 / 用户面板入口）
> 与观看窗口打磨（沉浸全屏、旋转、缩放、断流与异常提示），见 §3.2。

## 8. 真机验证步骤（设备接入后执行）

1. **构建 + 安装**：`cd phone && ./gradlew.bat :app:assembleDebug` →
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`

   > **vivo / OriginOS 踩坑记录**（2026-10-18 实测）：
   > - 若出现 `INSTALL_FAILED_ABORTED: User rejected permissions`：这是系统「USB 安装」确认
   >   闸门在**锁屏状态下无法弹窗**导致的自动拒绝。先 `adb shell input keyevent KEYCODE_WAKEUP`
   >   唤醒并解锁屏幕（`adb shell wm dismiss-keyguard`），再重试安装即可。
   > - 若出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不匹配）：设备上已有异签名旧版
   >   （如上游发布的 2.1.4-Han），需先 `adb uninstall com.yuaxi.ts6droid.cn` 再安装。
   > - 首次安装后可确认版本：`adb shell dumpsys package com.yuaxi.ts6droid.cn | findstr versionName`。
2. **关键日志**：`adb logcat -s ScreenShare ScreenShareService TsClient tslib-jni`
   （媒体会话 / 前台服务 / 事件桥 / Rust 侧）

   > **排查提示**：手机端调试日志量大、logcat 环形缓冲滚动很快（几分钟即可能覆盖），
   > 核对发布 offer 建议直接 `adb pull` 留档文件
   > `/sdcard/Android/data/com.yuaxi.ts6droid.cn/files/screenshare/last-offer.sdp`；
   > 对端是否接受以 TS6 客户端日志的 `HandleRemoteOffer ENTRY → SetRemoteDescription CALL →
   > REMOTE-DESC-SET` 序列为准（仅 `ENTRY` 后静默即被拒收，真因见 §6）。
3. **发布端（A3）**：连接服务器 → 顶栏共享图标 → 系统授权 → 日志应出现
   `Publisher started` → `Our stream started`；TS6 原生客户端 / WebSpeak 中应出现直播条目并可观看
4. **观看端（A4）**：他端发布 → 顶栏下方出现"观看"横幅 / 成员行出现「直播」角标 → 打开观看
   窗口 → 日志应出现 `Join requested` → `Answer sent` → `Video sink attached` →
   `Viewer phase <streamId> -> WATCHING`；可顺带验证：单击切换控制条、适应/填充、旋转按钮
   （横屏观看不断流）、发布方停止后窗口提示「直播已结束」并自动收起
5. **协议层闭环（A2b，无需手机）**：`cd tslib && cargo run -p screen-share-probe -- <server>:<port> --publish probe`
   应收到 `StreamStarted`；再用两个探针实例（`--publish` / `--join --source-clid <clid>`）交叉验证
   `JoinRequest`、offer/answer 与 ICE 中继。
6. **验收记录**：结果追加到 TASK.md 第 8 节进度记录（含机型 / 系统版本 / 对端类型）。
