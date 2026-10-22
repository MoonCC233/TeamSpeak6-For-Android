# A3 交付：Android 屏幕共享媒体层与 UI（发布端 + 观看端）

> 状态：**实现完成；真机与 TS6 原生客户端双向互通验收通过**（2026-10-18）；
> A4 首页实时预览 / 主动退订重看 / 前后台恢复真机验收通过（2026-10-20）；
> Pad 原生端发布 / 观看 / 同时双流真机验收通过（2026-10-21）；
> 发布参数与出流统计修正（2026-10-22，见 §6.8）。
> 手机端 ↔ Pad 互看仍需在手机 APK 安装后复测（当前被 vivo 安装闸门阻断）。
> 协议事实来源见 `docs/A2-screen-share-protocol.md`；本文只描述媒体层与 UI 设计。
> SDP 兼容性根因与单因素变体矩阵见 §6。

## 1. 范围

- **A3（发布端）**：MediaProjection 采集 → WebRTC 编码 → 每观看者一条 PeerConnection → SDP/ICE 经
  `respondjoinstreamrequest` / `streamsignaling` 中转；对端（TS6 原生客户端 / WebSpeak）可见可看。
- **A4（观看端）**：发现直播流（`notifystreamstarted` 等）→ `joinstreamrequest` → 收到 offer 回 answer →
  `SurfaceViewRenderer` 渲染；含观看窗口入口。
- **不在本次范围**：系统声音采集（`withAudio=false`）、发布码率精细调优、自定义 TURN 设置项。
- **已补齐（2026-10-20）**：观看端主动退订 `joinstreamrequest is_remove=1`，用于退出观看/重看时
  让发布端立即关闭旧观看者 PC（见 §3.2 第 7 步、§6.6）。

## 2. 组件与文件清单

| 层 | 文件 | 职责 |
| --- | --- | --- |
| Rust（协议） | `tslib/crates/tslib-core/src/screen_share.rs` | 7 命令构造（含 `joinstreamrequest is_remove=0/1`）/ 7 通知解析 / 信令 JSON 构造（A2b 已交付） |
| Rust（JNI） | `tslib/crates/tslib-jni/src/client.rs` | `native*` 屏幕共享命令函数（含 `LeaveScreenShare`、`SendScreenShareAnswer`、`SendScreenShareIceCandidate`） |
| Java 绑定 | `tslib/java/.../dev/tslib/Client.java`、`phone/app/src/main/java/dev/tslib/Client.java`（两份同步） | 公开方法 + native 声明 |
| Kotlin（桥） | `phone/.../bridge/ScreenShareEvents.kt` | `ScreenShareEvent` / `ScreenShareSignal` 模型 + 宽松 JSON 解析 |
| Kotlin（桥） | `phone/.../bridge/TsClient.kt` | `screenShareEvents` 事件流（replay=16）+ 屏幕共享命令包装（含 `leaveScreenShare`） |
| Kotlin（媒体） | `phone/.../screen/ScreenShareController.kt` | WebRTC 会话编排（发布端每观看者 PC / 观看端 PC）、信令分发、观看阶段状态机、VP8 SDP 裁剪、退订/首帧看门狗 |
| Kotlin（服务） | `phone/.../service/ScreenShareService.kt` | `mediaProjection` 前台服务（授权会话承载 + 通知栏停止按钮） |
| Kotlin（UI） | `phone/.../ui/screen/ServerScreen.kt` | 顶栏共享开关（授权流程）+ 远端流横幅"观看"入口 |
| Kotlin（UI） | `phone/.../ui/component/ScreenSharePreview.kt` | 首页实时预览卡片、`VideoSurface`（按视频宽高比布局）、连接信息对话框 |
| Kotlin（UI） | `phone/.../ui/component/ScreenShareViewerOverlay.kt` | 全屏观看覆盖层（与预览共用会话、各自独立渲染器、控制条/缩放/旋转/返回键） |
| 资源 | `res/values{,-en,-zh-rCN}/strings.xml`、`ui/icon/AppIcons.kt` | 三语文案 + `ScreenShare` 图标 |
| 清单 | `AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限；`ScreenShareService`（`foregroundServiceType="mediaProjection"`） |

## 3. 端到端流程

### 3.1 发布端（A3）

1. 用户在服务器界面点顶栏共享图标 → `MediaProjectionManager.createScreenCaptureIntent()` 征求授权；
2. 授权通过 → `ScreenShareService.start(...)` 启动 `mediaProjection` 前台服务（**先 startForeground，再
   getMediaProjection/createVirtualDisplay**，Android 14+ 合规；每次会话均需重新授权）；
3. 控制器创建 `ScreenCapturerAndroid`（`SurfaceTextureHelper` + `VideoSource(isScreencast=true)`），
   分辨率等比缩放至长边 ≤1440（偶数对齐）、24 fps；运行期连续两个统计周期命中
   `qualityLimitationReason=cpu` 时，再把输出长边降 15%（下限 1080，12 秒冷却）；
4. 发送 `setupstream(name, has_audio=false, bitrate=4608, ...)`；服务端回 `notifystreamstarted`（含
   `stream_id`）后进入直播态；
5. 每收到 `notifystreamjoinrequest(viewer_id)`：为该观看者创建 PC → `addTransceiver` 添加视频轨
   （方向 **SEND_ONLY**）→ 追加 `INACTIVE` 占位音频 m= 线 → `createOffer` → 视频方向兜底校正 +
   `setLocalDescription` → `respondjoinstreamrequest(stream_id, viewer_id, offer)`（兼容性要求见 §6）；
6. 观看者回 `answer`（`streamsignaling`）→ `setRemoteDescription`；双向 ICE 候选经 `iceCandidate` 信令中继；
7. 停止：本地 `stopstream(stream_id)` + 释放采集/PC + 关闭前台服务（通知栏"停止"同路径）。

### 3.2 观看端（A4）

1. `notifystreamstarted` / `notifystreaminfo` / `requeststreaminfo` → `remoteStreams` 状态流 →
   顶栏下方**实时预览卡片**（`ScreenSharePreview`）+ 成员行「直播」角标 / 频道行直播标记
   （`ChannelTree` 接收 `streamingUserIds`）；预览卡片检测到远端流后**自动入会**，显示直播角标、
   发布者昵称、分辨率/帧率、信息/全屏/退出按钮。
2. 点击全屏（预览卡片 / 横幅 / 成员角标 / 用户面板「观看屏幕共享」）→ 若预览已有会话则复用；
   否则 `joinStream()` 创建 PC 并发送 `joinstreamrequest(stream_id, source_client_id)`。
3. 收到 `notifyrespondjoinstreamrequest(offer)` → **观看端 VP8 裁剪**（见 §6.6）：把 offer 视频
   m= 行裁到只剩 VP8 + RTX + RED/ULPFEC，并删除被删 PT 的 `a=rtpmap` / `a=fmtp` / `a=rtcp-fb`，
   再 `setRemoteDescription` → `createAnswer` → `setLocalDescription` → 经
   `sendScreenShareAnswer` 回发（Rust 侧组装 JSON）。裁剪保证 libwebrtc 生成只含 VP8 的合法
   answer，避免手机端落到不支持的 H264 解码器而黑屏。
4. 远端视频轨到达（`onAddTrack`）→ 以**首帧检测包装**（`FirstFrameSink`）绑定
   `SurfaceViewRenderer`：首帧真正渲染后才把会话阶段切到 `WATCHING`，避免"已连接但黑屏"
   被当成观看成功；换绑渲染器（重组）时自动解挂旧 sink 再挂新渲染器。
5. 首页预览与全屏各自持有独立 `SurfaceViewRenderer`，但**共用同一个 WebRTC 会话**；布局统一走
   `VideoSurface`：按视频宽高比显式计算尺寸，`fit` 留黑边、`fill` 放大后由窗口裁切，
   不把 `SurfaceViewRenderer` 强行拉成非视频比例（否则桌面 2560×1600 画面会纵向拉伸）。
6. 全屏覆盖层 `ScreenShareViewerOverlay`（非 Dialog）：
   - 沉浸全屏：隐藏系统栏（可上滑唤出）、保持屏幕常亮、退出时恢复系统栏与屏幕方向；
   - 控制条：单击画面切换显隐，观看中 3.5 秒无操作自动隐藏；
   - 「适应 / 填充」缩放切换（`SCALE_ASPECT_FIT` / `SCALE_ASPECT_FILL`）；
   - 「旋转」按钮：传感器横屏 ↔ 跟随系统（`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` /
     `UNSPECIFIED`），退出时恢复进入前的方向；
   - 返回键：overlay 根节点 `focusable` + `onKeyEvent` 拦截 `Key.Back`，不依赖 `BackHandler`；
   - 阶段提示（由 `ScreenShareController.viewerPhases` 驱动）：加入中 / 连接画面中 /
     重连中 / 直播已结束 / 被拒绝 / 连接失败；「已结束」「被拒绝」3 秒后自动收起。
7. 退出观看（预览退出按钮 / 全屏停止按钮）→ `leaveStream()`：
   - 先发 `joinstreamrequest(..., is_remove=1)` 主动退订，发布端收到 `notifystreamclientleft`
     后立即关闭该观看者的旧 PC；本地再关闭 PC、解绑 sink、释放 EGL；
   - 首页保留一行紧凑入口「发布者 · 屏幕共享 → 观看」，可随时重看；重看会创建全新 PC，
     不复用旧会话；
   - 全屏返回键 / 关闭按钮只收起全屏覆盖层，回到首页实时预览；预览会话继续，不重新协商；
   - 应用退到后台（`ON_PAUSE`）同样 `leaveStream()`，回前台（`ON_RESUME`）自动重新 join；
   - 发布方停止（`StreamStopped`）→ 观看会话释放但保留阶段与流快照，窗口显示「直播已结束」；
   - 加入后 **20 秒无首帧**（`JOINING / CONNECTING / RECONNECTING`）→ 阶段置为 `FAILED` 并释放
     PC；首次 SDP `setRemoteDescription` 失败时快速失败；已 `WATCHING` 的会话收到失败的重协商
     offer 则忽略，避免打断正在播放的画面。

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
| 编码器 | `DefaultVideoEncoderFactory`；码表剔除 H.264/H.265 并把 VP8 置顶（对端兼容性要求，见 §6.5） |

## 5. 构建与验证现状

- **JNI 符号**：`llvm-nm -D` 验证 release `.so`（arm64-v8a，strip 后 **9.8 MB**）导出全部 10 个屏幕共享/流函数（含新增 `nativeLeaveScreenShare`）。
- **构建修复**（相对上游脚本的差异）：
  - 去除 `--features vendored-openssl`：依赖树为纯 Rust 加密栈（rustls/aws-lc-rs），`openssl` 仅是
    tslib-jni 的未引用可选依赖；
  - Windows 交叉编译必须 `CMAKE_GENERATOR=Ninja`（默认 VS 生成器会在 `audiopus_sys` 处失败）；
  - `CARGO_PROFILE_RELEASE_STRIP=symbols`（debug 版 .so 高达 193 MB，strip 后 9.8 MB）；
  - Gradle 任务 `buildRustLibs` 已同步（`../tslib`、`--release`、arm64-v8a、Ninja 路径、SDK 路径读取 `local.properties`）。
- **编译验证**：`cargo test -p tslib-core`（63 + 12 项）、`cargo check -p tslib-jni`、
  `gradlew :app:compileDebugKotlin`、`:app:assembleDebug` 均通过。
- **单测**：`ScreenShareEventParserTest`（15 项：7 种事件 + 5 种信令 + null `mid`/`m_line` + 未知/畸形负载）通过；
  `gradlew :app:testDebugUnitTest` 全量 61 项通过（Android 单测经 `testImplementation(org.json:json)`
  提供真实 JSON 实现——android.jar 内的 org.json 是抛异常的桩）。
- **2026-10-22 回归**：`:core:compileDebugKotlin`、`:core:testDebugUnitTest`、`:pad:assembleDebug`、
  `:app:assembleDebug`、`:pad:lintDebug`、`:app:lintDebug` 均通过（发布统计 / CPU 降档修正后）。

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
- **观看方向（A4，2026-10-18 首通）**：桌面客户端发布 → 手机观看：`Join requested` → `Answer sent` →
  `Viewer ICE connected`，**画面可见**。
- **观看端实时预览 / 黑屏修复（2026-10-20）**：vivo V2505A（`V2505A-Android`）观看 TS6 桌面端
  `MoonCC233` 的 2560×1600 桌面共享（流 `df0aaa96-97f0-4d16-a4d0-ae8380665560`）：
  - 首页自动入会并实时出画面，预览显示「直播」角标、`MoonCC233`、`2560 x 1600 @30fps`；
  - 观看端把 offer 视频 PT 从 `39 102 104 98 96 97 103 107 40 99 116 117 118` 裁为
    `96 97 116 118`，answer 为 VP8；统计 `codec=VP8 dec=libvpx 2560x1600@30 rx≈7–9 Mbps
    decTime≈15–25ms`；
  - 全屏 fit/fill、竖屏/横屏旋转、Back 退出全屏均通过；`VideoSurface` 保证桌面比例不被拉伸。
- **主动退订 / 重看 / 前后台（2026-10-20）**：
  - 退出观看 → 紧凑入口「MoonCC233 · 屏幕共享 → 观看」→ 点「观看」→ 新 PC 在 1–2 秒内
    `WATCHING`，连续两轮重看均出画面；
  - 日志顺序：`Left stream` → 新 `Join requested` → `Viewer offer video line (pruned)` →
    `Viewer ICE connected` → `Viewer phase ... -> WATCHING`；不再出现
    `SDP set failure: The order of m-lines ...`；
  - 后台（`ON_PAUSE`）→ `Left stream`；回前台（`ON_RESUME`）→ 自动重新 join 并恢复画面；
  - 修复前重看卡在「正在连接画面…」的根因是发布端保留旧 viewer PC 并继续下发重连 offer；
    `joinstreamrequest is_remove=1` 让发布端收到 `notifystreamclientleft` 并清理旧 PC。
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


### 6.6 观看端 VP8 裁剪与重看稳定性（2026-10-20）

- **观看端黑屏根因**：TS6 桌面端 offer 同时携带 VP8/VP9/AV1/H264 等多个视频 PT，libwebrtc
  生成的 answer 可能选中手机不支持的 H264，或 answer 的 PT 与桌面端解码器不匹配，落入
  `NullVideoDecoder`（现象：ICE 已连接、`codec=null`、无首帧）。
- **修复**：在 `setRemoteDescription` **之前**修改远端 offer：视频 m= 行只保留
  `VP8 + RTX + RED/ULPFEC` 的 PT（实机 `39 102 104 98 96 97 103 107 40 99 116 117 118`
  → `96 97 116 118`），并同步删除被删 PT 的 `a=rtpmap` / `a=fmtp` / `a=rtcp-fb` 行。
  这样 libwebrtc 只能生成 VP8 组的合法 answer，手机端走 `libvpx` 软解。
- **重看卡死根因**：观看端退出时只关闭本地 PC，未通知发布端；发布端保留旧 viewer PC，
  下一次 join 时仍按旧会话下发后续 offer，m-line 顺序与新会话不一致 →
  `Failed to set remote offer sdp: The order of m-lines in subsequent offer doesn't match
  order from previous offer/answer`，且旧看门狗只检查 `JOINING`，进入 `CONNECTING` 后永久转圈。
- **修复**：退出观看/后台时发送 `joinstreamrequest is_remove=1`（`LeaveScreenShare`，Rust →
  JNI → Java → Kotlin），发布端收到 `notifystreamclientleft` 关闭旧 PC；重看使用全新 PC。
  同时把 20s 首帧看门狗扩展到 `JOINING / CONNECTING / RECONNECTING`，首次 SDP 失败时快速
  `FAILED` 并释放 PC；已 `WATCHING` 的失败重协商仅记日志，不打断画面。
- **实测**：连续两轮退出 → 紧凑入口 → 重看均 `WATCHING`，无 `SDP set failure`；前后台切换
  同样通过。发布端抖动/重传优化暂缓（A3 侧独立事项）。

### 6.7 同时发布 + 观看的 ICE 路由（2026-10-21）

- **现象**：Pad 同时发布自己的屏幕并观看远端直播时，双方均能发现直播、观看端也已生成并发送
  answer，但画面停在「正在连接画面…」，20 秒后判定失败；只观看不发布时正常。
- **根因**：`notifystreamsignaling` 的 ICE 候选原先只按 `clid` 查找发布端的 `peers`。当同一位
  对端既在观看我们、又被我们观看时，该 `clid` 同时存在于 `publisher.peers` 与 `viewers`；
  观看端收到的候选被误投到发布端的 `PeerConnection`，观看端 PC 始终拿不到候选。
- **修复**：ICE 候选先按 stream id 判断归属——仅当 `event.streamId == publisher.streamId`
  时走发布端 `peers[event.peerId]`，否则走 `viewers[event.streamId]`；answer 仍只属于发布端。
- **实测**：iQOO Pad5c（Android 15）同时发布整屏并观看 `MoonCC233` 桌面流，货架与全屏均稳定
  在 2560×1600@约 30fps；后台返回后发布与观看自动恢复；通知栏「停止」只停止发布，观看画面继续。

### 6.8 双向互看的信令竞态与出流统计（2026-10-21/22）

Pad 原生端与桌面端同时发布后，暴露出几类只在「近同时开始 / 双向互看」时出现的信令竞态；
同时发布端 `getStats` 的 `tx` 长期显示 `0kbps`。修正如下：

- **own-stream 认领**：`notifystreamstarted` 不能因为 `publisher.streamId == null` 就认领；
  先比较 `event.clientId == tsClient.clientId`。双方几乎同时开始共享时，先到的事件可能是
  对方的流；误认领会把自己的流当成远端流，双向都卡在「无法连接」。`StreamInfo` 同样忽略
  own client 的条目；若已误认领，后续属于自己的 `StreamStarted` 会关闭旧 peers 并自愈。
- **answer 后 ICE**：发布端 `setRemoteDescription(answer)` 的 `onSetSuccess` 之前收到观看端
  ICE，先放入 `pendingCandidates`，成功后统一 flush；避免移动端信令稍慢时候选被静默丢弃，
  表现为 `Answer applied` 后仍停在 CONNECTING。
- **JoinRequest / offer 容错**：`notifystreamstarted` 丢失时用 JoinRequest 的 streamId 兜底；
  同一 viewer 的陈旧 PeerConnection（FAILED/CLOSED/DISCONNECTED 或创建超过 8 秒）在
  重复 JoinRequest 时替换；重复 offer 用 `lastOfferSdp` 去重；`JoinResponse` 里携带的
  offer 也走同一 `handleViewerOffer` 路径（TS6 原生发布端可能把首个 SDP 放在这里）。
- **ICE 分流**：见 §6.7；发布 / 观看双向同时存在时按 streamId 而非仅 clid 路由。
- **失败清理**：观看端 SDP / ICE 失败走 `failViewer` 释放 PC；发布端 answer 失败关闭对应
  viewer peer；`closePublisherPeer` 同时清掉该 viewer 的统计采样，防止重连后累计字节
  归零导致负码率。
- **出流统计**：`outbound-rtp` 同时存在 RTX 时取 `bytesSent` 最大的主视频流；
  `remote-inbound-rtp` 先按 `localId == outbound.id`、再按 SSRC 配对，区间丢包用
  `packetsLost` / `packetsSent` 的增量。码率公式为 `Δbytes × 8 / Δt(ms)`——单位就是
  kbit/s；旧实现多除了一次 1000，所有 <1Mbps 的区间都被截断为 `tx=0kbps`。
- **参数默认值**：发布 1440 长边 / 24fps / 4Mbps 上限 / 150kbps 下限，
  `MAINTAIN_RESOLUTION`（带宽波动优先降帧率、保文字清晰）；软件 VP8 在 1080p 级
  持续 `lim=cpu` 时自动降档到 1080 长边。

真机观测（iQOO Pad5c）：Pad 发布 + 同时观看桌面流时 1440×1012@24、`lim=none`，
上传约 1.2Mbps；纯静态画面约 0.3Mbps；连续 CPU 受限后自动降到 1080×756。

> **网络拓扑**：Pad 曾连接手机热点 `czx`（192.168.43.105/24），桌面 / 手机在校园 Wi-Fi
> `10.15.73.0/22`；跨网段只能走 srflx，桌面观看统计为 `pair=prflx-srflx/udp`、
> RTT 150–300ms。Pad 切到同一校园 Wi-Fi（10.15.75.147/22）后才具备 host-host 直连条件。
> 手机 ↔ Pad 互看的最终复测需两端处于同一可互通的 Wi-Fi（AP 客户端隔离会阻断 host 候选）。

## 7. 后续工作

1. 系统声音采集（`AudioPlaybackCapture`，`withAudio=true`）——协议与 JS 侧已支持；
2. 自定义 ICE/TURN 设置项（对齐 WebSpeak 的管理端配置语义）；
3. 发布端统计上屏（当前 `getStats` 指标输出到 logcat；观看端已有实时统计面板）；
4. B6：与 Pad 端发布桥共享媒体层设计（信令结构与控制器接口保持一致）；
5. 发布端抖动/重传优化（A3；已加入码率 / 丢包 / RTT 观测与 CPU 降档，弱网策略仍待真机调优）。

> 已完成（2026-10-19）：观看入口增强（成员「直播」角标 / 频道直播标记 / 用户面板入口）
> 与观看窗口打磨（沉浸全屏、旋转、缩放、断流与异常提示），见 §3.2。
>
> 已完成（2026-10-20）：首页实时预览卡片、观看端 VP8 offer 裁剪（黑屏修复）、
> `joinstreamrequest is_remove=1` 主动退订与重看/前后台稳定性，见 §3.2、§6.4、§6.6。
>
> 已完成（2026-10-21）：Pad 同时发布 + 观看的 ICE 候选按 stream id 分流，
> 修复双端互看卡在 CONNECTING；iQOO Pad5c 真机验证通过，见 §6.7。
>
> 已完成（2026-10-22）：own-stream 认领、answer 后 ICE、JoinResponse / 重复 offer
> 容错、失败清理与出流统计修正，发布参数收敛到 1440/24 + CPU 自适应降档，见 §6.8。

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
4. **观看端（A4）**：他端发布 → 首页自动出现实时预览卡片（成员行出现「直播」角标）→ 日志应出现
   `Join requested` → `Answer sent` → `Video sink attached` → `Viewer phase <streamId> -> WATCHING`；
   预览卡片应显示直播角标、发布者昵称、分辨率/帧率；点全屏进入覆盖层；可验证单击切换控制条、
   适应/填充、旋转按钮（横屏观看不断流）、返回键退出全屏、发布方停止后窗口提示「直播已结束」并自动收起
5. **观看端重看 / 前后台（A4，2026-10-20 增补）**：
   - 点预览退出按钮 → 紧凑入口「昵称 · 屏幕共享 → 观看」；再点「观看」→ 2 秒内再次
     `Viewer phase <streamId> -> WATCHING`；日志应有 `Left stream` → 新 `Join requested`，
     且无 `SDP set failure`；
   - 按 Home 退后台 → 日志 `Left stream`；回前台 → 自动重新 join 并出画面；
   - 全屏下 `adb shell input keyevent KEYCODE_BACK` 退出全屏，应回到预览且会话继续。
6. **Pad 原生端（B5，2026-10-21 增补）**：`cd phone && ./gradlew.bat :pad:assembleDebug` →
   `adb install -r pad/build/outputs/apk/debug/pad-debug.apk`；
   - Pad 发布 → 桌面观看：日志 `Publisher started` → `Our stream started` →
     `Offer sent to viewer ...` → `Answer applied`；
   - Pad 观看桌面：`Join requested` → `Answer sent` → `Viewer ICE connected` →
     `Viewer phase ... -> WATCHING`；
   - 同时发布 + 观看：检查 `Stats v=... pair=host-host/udp tx=...kbps ... lim=...` 与
     `Viewer stats ... rx=...kbps`；两端应在同一可互通 Wi-Fi（避免热点 / AP 客户端隔离）。
7. **协议层闭环（A2b，无需手机）**：`cd tslib && cargo run -p screen-share-probe -- <server>:<port> --publish probe`
   应收到 `StreamStarted`；再用两个探针实例（`--publish` / `--join --source-clid <clid>`）交叉验证
   `JoinRequest`、offer/answer 与 ICE 中继。
8. **验收记录**：结果追加到 TASK.md 第 8 节进度记录（含机型 / 系统版本 / 对端类型）。
