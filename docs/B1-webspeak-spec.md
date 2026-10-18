# B1 调研交付：WebSpeak 规格拆解（Pad 端参照标准）

> 状态：✅ 调研完成（功能清单 + 技术规格 + Android 容器现状）
> 来源：`pad/`（上游 `EchoSixHIYA/WebSpeak-client-for-TeamSpeak` `master@32d84435`，2026-10-18，v0.2.8，AGPL-3.0-only）

## 1. 项目定位

自托管 TeamSpeak 3 / TS6 网页客户端与语音网关：浏览器打开网页即可加入频道；网关负责 TeamSpeak 协议连接，浏览器负责交互与音频。**本项目的 Pad 端目标 = 让 Android 平板获得与它完全一致的 UI 与功能。**

在线演示：<https://shanghai.webspeak.online>、<https://webspeak.online>

## 2. 技术架构

```
浏览器（Vue 3 + TS，web/）
  │  HTTPS / WSS（/api/join-ticket 票据 → /ws/voice 语音与控制 WebSocket）
  ▼
Node 网关（src/，Node ≥22.5）
  ├─ voice-bridge / session-manager / session-events  会话与目录
  ├─ screen-share-coordinator                          屏幕共享协调与信令转发
  ├─ webrtc-audio（werift）/ opus-codec                可选 WebRTC 低延迟语音
  ├─ ts-client + teamspeak-adapter                     @echosixhiya/teamspeak-client SDK
  ├─ admin / security / persistence                    SQLite + master.key + /admin 控制台
  └─ relay                                             UDP 加速中继模式
  ▼
TeamSpeak 3 / 6 服务器（标准客户端协议，无需 ServerQuery）

Android 容器（web/android + mobile/ + src/mobile/）
  └─ Capacitor + @capawesome/capacitor-nodejs（内嵌 nodejs-mobile Node 18.20.4）
     内嵌网关监听 127.0.0.1:3040，WebView 加载该地址
```

## 3. 功能全集（Pad 端复刻验收清单）

来源：`pad/docs/README.zh-CN.md` 特性表与高级功能章节。

| # | 能力 | 说明 |
| --- | --- | --- |
| F1 | TS3/TS6 兼容 | 按服务器版本自动探测协议 |
| F2 | 频道与成员 | 频道树、实时成员状态、切换频道、带锁频道图标、频道密码记忆 |
| F3 | 实时语音 | Opus；兼容传输（WebSocket）+ 可选 WebRTC 低延迟传输 |
| F4 | 音频控制 | 设备选择、音量、麦克风测试、闭麦、VOX（语音激活）、成员独立音量 |
| F5 | 浏览器端降噪 | 可开关，采集端处理（含可选 RNNoise） |
| F6 | 消息与互动 | 频道消息、服务器消息、私聊、戳一戳、耳语目标 |
| F7 | 桌面端伴奏 | 分享窗口/标签页声音到频道（桌面浏览器限定） |
| F8 | 身份与访问 | 身份保持、TS3 身份导入/转换/校验/导出、访客目标、邀请链接（可撤销/过期） |
| F9 | **跨端 P2P 屏幕共享** | 浏览器 ↔ TS6 原生客户端互相发现/发起/观看；1080p/60 FPS、播放器音量、全屏、观众列表、WebRTC 统计 |
| F10 | 皮肤系统 | 日间/夜间/ILLUSIA 内置皮肤 + `.wskin` 自定义 + 皮肤编辑器（需管理台） |
| F11 | 管理控制台 | 目标、访问策略、WebRTC、中继、邀请、会话、日志、诊断、备份（`/admin`） |
| F12 | 中继模式 | UDP 加速中继，专用于被拒连/不稳场景 |
| F13 | 多语言与响应式 | 中/英/德/俄/日；桌面与移动响应式布局 |
| F14 | 自托管形态 | Docker（amd64/arm64）、Windows x64、Linux x64/ARM64 服务端包、Android APK |

## 4. 屏幕共享技术规格（Pad 端最关键能力）

- **拓扑**：屏幕共享使用独立于语音的 peer connection；网关只做会话鉴权、共享状态管理与 SDP/ICE 信令转发（`streamsignaling`），**不承载屏幕媒体**。
- **媒体路径**：WebRTC/ICE 直连优先；默认 STUN `stun:turn.teamspeak.com:3478`；可配外部 TURN（`WEBSPEAK_SCREEN_SHARE_ICE_SERVERS`）。
- **TS6 互通协议命令**（`pad/src/server/screen-share-coordinator.ts`）：
  `requeststreaminfo`（发现）、`setupstream`（发布）、`joinstreamrequest`（观看）、
  `streamsignaling`（信令转发）、`removeclientfromstream`（移除观看者）、`stopstream`（停止）。
- **浏览器端实现**：`pad/web/src/voice/screen-share.ts`（采集、peer 协商、清理、诊断）；观看端在成员卡片/播放器组件中。
- **共享范围**：与 TeamSpeak 目标服务器和频道双重绑定（`targetKey + channelId`），换频道即离开。
- **原生观众**：`nativeViewerClids` / `screenShareNativeViewerJoined` 表明浏览器发布的流可被 TS6 原生客户端观看，反之亦然。

## 5. 语音技术规格（供 Pad/手机互通参考）

- **兼容传输**：浏览器 AudioWorklet 采集 48 kHz 单声道 PCM，每 20 ms 一帧（960 采样、1920 字节 Int16）经 WebSocket 二进制发送；网关用 Opus 编码转给 TeamSpeak。下行 Opus 带 3 字节头（codec + clientID），浏览器用 WebCodecs 解码播放。
- **WebRTC 传输（可选）**：网关用 werift 与浏览器协商实时媒体，支持桌面伴奏；失败自动回退兼容传输。
- **编解码实现差异**：服务端用 `@discordjs/opus`（原生），Android 包内用 `opusscript`（JS/WASM）。

## 6. Android 容器现状（`pad/web/android`、`pad/mobile/`）

| 项 | 现状 |
| --- | --- |
| 形态 | Capacitor 容器 + `@capawesome/capacitor-nodejs` 内嵌 Node 运行时（nodejs-mobile **18.20.4**） |
| 网关 | 内嵌监听 `127.0.0.1:3040`，WebView 加载；管理台与皮肤管理**不可用** |
| 构建 | `web/android` 带 gradle wrapper；产物分 `arm64-v8a` / `armeabi-v7a` / `x86_64`；debug 签名 sideload |
| 官方口径 | `mobile/README.md` 明确：真机 TeamSpeak 连通性与双向音频**未验证**；APK 仅供 sideload 测试 |
| **阻塞 1：运行时版本** | 其 TS SDK（`references/teamspeak-js`）当前要求 **Node ≥22.12**，内嵌运行时仅 18.20.4；nodejs-mobile 最新版本即 18.20.4（2024-10），无更新路线 |
| **阻塞 2：屏幕采集** | Android WebView **不支持 `getDisplayMedia`**；且 caniuse 证实 **Chrome for Android 亦不支持** → 任何 Web 内核内都无法直接发布屏幕共享，必须原生 MediaProjection |
| 缺失能力 | 前台服务（后台音频/常亮）、原生屏幕共享桥、平板大屏布局打磨、后台保活 |

> 上述两处阻塞直接决定 Pad 端技术路线，分析与建议见 `docs/adr/0001-pad-approach.md`。

## 7. 对 Pad 端的启示

1. **UI 复刻**的最可靠方式就是复用 `web/` 本体（构建产物原样载入容器），而非用原生 UI 重画。
2. **屏幕共享发布**与**后台音频**是 Web 形态在 Android 上的硬缺口，需要原生扩展（Capacitor 插件或伴生服务）。
3. **网关运行时**是自包含方案的前提，必须先做 Node 18.20.4 可行性 spike；若不成立，退路是"远程网关 + WebView 壳 + 原生扩展"（详见 ADR-0001 备选 A'）。
4. Pad 端验收以第 3 节功能清单 F1–F14 为基准（管理台 F11 与皮肤 F10 可按产品决策降级）。
