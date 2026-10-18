# A3 交付：Android 屏幕共享媒体层与 UI（发布端 + 观看端）

> 状态：**实现完成，编译通过；待真机与对端互通验证**（2026-10-18）。
> 协议事实来源见 `docs/A2-screen-share-protocol.md`；本文只描述媒体层与 UI 设计。

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
5. 每收到 `notifystreamjoinrequest(viewer_id)`：为该观看者创建 PC → 添加视频轨 → `createOffer` →
   `setLocalDescription` → `respondjoinstreamrequest(stream_id, viewer_id, offer)`；
6. 观看者回 `answer`（`streamsignaling`）→ `setRemoteDescription`；双向 ICE 候选经 `iceCandidate` 信令中继；
7. 停止：本地 `stopstream(stream_id)` + 释放采集/PC + 关闭前台服务（通知栏"停止"同路径）。

### 3.2 观看端（A4）

1. `notifystreamstarted` / `notifystreaminfo` → `remoteStreams` 状态流 → UI 出现"观看"横幅；
2. 点击观看 → `joinStream()` 创建 PC 并发送 `joinstreamrequest(stream_id, source_client_id)`；
3. 收到 `notifyrespondjoinstreamrequest(offer)` → `setRemoteDescription` → `createAnswer` →
   `setLocalDescription` → 经 `sendScreenShareAnswer` 回发（Rust 侧组装 JSON）；
4. 远端视频轨到达（`onAddTrack`）→ 绑定 `SurfaceViewRenderer`；
5. 关闭窗口 → `leaveStream()` 关闭 PC、解绑渲染器、释放 EGL。

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

## 6. 待验证清单（真机 / 互通）

| 项 | 方法 |
| --- | --- |
| MediaProjection 授权与采集 | 真机点共享 → 系统授权 → `notifystreamstarted` 回执（logcat `ScreenShare`） |
| 与 WebSpeak 互通（发布/观看双向） | WebSpeak 实例中观察直播条目并播放；反向用 WebSpeak 发布、手机观看 |
| 与 TS6 原生客户端互通 | 原生客户端加入频道后应能看到直播并可观看（H.264/VP8 协商） |
| Android 14+ 前台服务合规 | 目标机型（API 34/35）验证 startForeground 顺序与授权一次性令牌 |
| 观看端体验 | 旋转/全屏适配、断流提示、观看窗口关闭后的资源回收 |

## 7. 后续工作

1. 系统声音采集（`AudioPlaybackCapture`，`withAudio=true`）——协议与 JS 侧已支持；
2. 观看入口增强：成员卡片"直播中"角标、频道内提示（对齐 WebSpeak UI）；
3. 自定义 ICE/TURN 设置项（对齐 WebSpeak 的管理端配置语义）；
4. 发布码率自适应与统计（`getStats` 指标上屏）；
5. B6：与 Pad 端发布桥共享媒体层设计（信令结构与控制器接口保持一致）。

## 8. 真机验证步骤（设备接入后执行）

1. **构建 + 安装**：`cd phone && ./gradlew.bat :app:assembleDebug` →
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. **关键日志**：`adb logcat -s ScreenShare ScreenShareService TsClient tslib-jni`
   （媒体会话 / 前台服务 / 事件桥 / Rust 侧）
3. **发布端（A3）**：连接服务器 → 顶栏共享图标 → 系统授权 → 日志应出现
   `Publisher started` → `Our stream started`；TS6 原生客户端 / WebSpeak 中应出现直播条目并可观看
4. **观看端（A4）**：他端发布 → 顶栏下方出现"观看"横幅 → 打开观看窗口 → 日志应出现
   `Join requested` → `Answer sent` → `Video sink attached`
5. **协议层闭环（A2b，无需手机）**：`cd tslib && cargo run -p screen-share-probe -- <server>:<port> --publish probe`
   应收到 `StreamStarted`；再用两个探针实例（`--publish` / `--join --source-clid <clid>`）交叉验证
   `JoinRequest`、offer/answer 与 ICE 中继。
6. **验收记录**：结果追加到 TASK.md 第 8 节进度记录（含机型 / 系统版本 / 对端类型）。
