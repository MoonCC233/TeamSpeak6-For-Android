# B2s：Pad WebView 兼容性与发布桥 spike

> 状态：**⏳ 进行中**（2026-10-20）
> 产出：`pad/shell/`（原生 WebView 壳）、`pad/shell/tools/`（离线探测服务器 + 能力页）
> 关联：`docs/adr/0001-pad-approach.md`（方案 A'）、`docs/B1-webspeak-spec.md`

## 1. 目标与边界

B2s 要回答两个问题：

1. **Android WebView 能否直接承载 WebSpeak Web 端的核心能力**：麦克风采集、
   WebRTC 收听/观看、AudioWorklet、WASM、WebSocket、横屏大屏布局。
2. **原生屏幕共享发布桥怎么接**：Capacitor 插件，还是 JS Bridge + 原生 WebRTC？

B2s 不实现完整发布桥（B5 负责），也不做 B3 体验打磨。目标是用最小壳应用拿到明确的
能力结论和技术路线。

## 2. 方案回顾

ADR-0001 已选定 **方案 A'：远程网关 + WebView 壳 + 原生扩展**。因此：

```text
Pad Shell (Android WebView)
  │  HTTPS/WSS
  ▼
自托管 WebSpeak 网关（Node ≥22.5，跑在服务器/PC）
  │  TeamSpeak 协议
  ▼
TS3 / TS6 服务器
```

- Web UI = 上游 `pad/web` 原样构建产物，保真度天然 100%；
- 壳只补 Android 侧能力：WebView 生命周期、麦克风权限、全屏、文件选择、后台音频、屏幕共享发布；
- **不内嵌 Node**（官方 Capacitor 容器的 Node 18 与 SDK 要求 Node ≥22 冲突，ADR 已排除）。

## 3. 已实现（B2s 壳）

工程：`pad/shell/`，纯 Java + Android 平台 API，无第三方运行时依赖。

| 能力 | 实现 |
| --- | --- |
| 横屏沉浸 | `sensorLandscape`、隐藏系统栏、`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`、常亮 |
| WebView | JS/DOM storage/数据库、自动播放、混合内容、安全浏览、调试开关 |
| 麦克风 | `WebChromeClient.onPermissionRequest` → 运行时 `RECORD_AUDIO` → 仅授予 `RESOURCE_AUDIO_CAPTURE` |
| 权限最小化 | 只对当前网关 scheme+host+port 的 origin 授权，其他 origin 拒绝 |
| HTTPS | 证书错误提示；可选“允许自签名证书（仅调试）” |
| 文件 | `ACTION_GET_CONTENT` + `onShowFileChooser` |
| 全屏 | `onShowCustomView` / `onHideCustomView`，Back 先退全屏 |
| 导航 | 同网关留在 WebView，外部 http(s)/mailto/tel 交系统；Back 两次退出 |
| 设置 | 右上角齿轮：重载、改网关、清 WebView 数据、关于 |
| JS Bridge | `window.TS6PadNative` 占位：`getVersion/getPlatform/isScreenShareAvailable/getGatewayUrl` |

APK 包名 `com.yuaxi.ts6pad`，可与手机端 `com.yuaxi.ts6droid.cn` 共存。

## 4. 离线验证工具

因为当前 PC 出网受限（GitHub / 在线 Demo 超时），先提供不依赖真实网关的验证路径：

```powershell
cd pad
npm --prefix web ci
npm --prefix web run build

cd shell
node tools/demo-server.mjs 8080
```

手机 USB 连接后：

```powershell
adb reverse tcp:8080 tcp:8080
```

壳内填：

- `http://localhost:8080/capability.html`：能力探测；
- `http://localhost:8080/`：WebSpeak UI（mock `/api/public-config`，可渲染加入页）。

`demo-server.mjs` 提供 WebSpeak 静态构建、mock 公共配置、`/ws/echo` 回声，
以及一个不会真正连服的 `/ws/voice` 占位。`capability.html` 完全自包含，测试：

- 环境：UA、`isSecureContext`、原生 Bridge 注入；
- 能力：`getUserMedia`、`getDisplayMedia`、`RTCPeerConnection`、编解码列表、
  `AudioContext`/`AudioWorklet`、`WebSocket`、`localStorage`、`indexedDB`、WASM、
  `MediaRecorder`、`MediaStreamTrackProcessor`、`OffscreenCanvas`；
- 麦克风：采集 + 电平表 + `getSettings()`；
- WebRTC 回环：麦克风/振荡器 + Canvas 视频，A/B PC 本机 SDP/ICE，收端 `getStats`
  统计 `inbound-rtp` 的 packets/bytes/frames；
- WebSocket 回声；
- `getDisplayMedia` 失败探测。

## 5. 验证矩阵

| # | 验证项 | 方法 | 结果 |
| --- | --- | --- | --- |
| 1 | WebSpeak UI 在 Chromium 内核渲染 | 桌面 Chrome headless 打开本地 `web/dist` + mock 配置 | ✅ 2026-10-20：加入页渲染，`siteName=TS6 Pad 本地演示`，`data-ws-page=home` |
| 1b | 能力页 WebRTC 回环逻辑（桌面 Chrome 对照） | headless Chrome + CDP 点击“开始回环” | ✅ 2026-10-20：收到 audio/video `ontrack`；`inbound-rtp` 音频 150 包、视频 70 包/44 帧；编解码列表含 VP8/H264/AV1/VP9、opus |
| 2 | WebSpeak UI 在 Android WebView 渲染 | Pad 壳加载 `http://localhost:8080/` | ⏳ 待真机 |
| 3 | 麦克风采集 + 权限桥 | `capability.html` → 请求麦克风 | ⏳ 待真机 |
| 4 | WebRTC 音视频回环 + 编解码 | `capability.html` → 回环 + `getStats` | ⏳ 待真机 |
| 5 | AudioWorklet / WASM | 能力页 + WebSpeak 语音链路 | ⏳ 待真机 |
| 6 | WebSocket | 能力页回声 | ⏳ 待真机 |
| 7 | 横屏/大屏布局 | Pad 壳加载 WebSpeak 加入页 | ⏳ 待真机 |
| 8 | 真实网关麦克风/收听/观看 | 可达的自托管 WebSpeak 实例 | ⏳ 阻塞：当前网络无法访问上游 Demo / GitHub；需要可用的自建实例或恢复网络 |
| 9 | 屏幕共享观看（WebView） | 真实网关 + TS6 桌面共享 | ⏳ 待网关 |
| 10 | 屏幕共享发布（原生） | B5 实现 | ⏳ B5 |

## 6. 阶段性结论

### 6.1 已验证

- **Web 端构建可用**：`pad/web` 在当前环境成功 `vue-tsc + vite build`；产物可被静态服务器加载，
  加入页在 Chromium 内核正常渲染，说明 WebView 路线没有构建层面的阻塞。
- **能力探测页可用**：桌面 Chrome 打开后环境/编解码/特性列表正常输出；用 CDP 驱动
  WebRTC 回环，收到 audio/video 轨道，`inbound-rtp` 音频 150 包、视频 70 包/44 帧，
  编解码列表含 VP8/H264/AV1/VP9 与 opus，说明探测页逻辑本身正确；同一页面可直接在
  WebView 中打开，作为真机对照。
- **壳应用可构建**：`pad/shell` 的 debug APK 构建通过（无第三方依赖，冷构建约 1 分钟）。

### 6.2 关键技术判断（待真机确认）

1. **`getDisplayMedia` 在 Android WebView 中不存在**（Chrome for Android 也不支持）。
   Pad 的“发布屏幕共享”必须走原生 `MediaProjection`，这与 ADR-0001 的判断一致。
2. **麦克风权限必须双层桥接**：WebView 的 `onPermissionRequest` 只负责网页权限，
   Android 的 `RECORD_AUDIO` 仍需 `requestPermissions`；两层都通过后 `getUserMedia` 才能成功。
3. **安全上下文是硬约束**：`getUserMedia` 只在 HTTPS 或 `localhost` 可用。局域网
   `http://10.x` 即使 WebView 允许明文，也不会被当作安全上下文。开发用 `adb reverse`
   + `localhost`，正式部署必须 HTTPS。
4. **WebView 的 `onPause` 会暂停页面**：当前壳在 `onPause` 调用 `webView.onPause()`，
   后台语音会断。B3 必须改为前台服务 + 音频焦点，并在后台保持 WebView/语音连接。
5. **HTML5 全屏需要 `onShowCustomView`**：壳已接管，否则观看端全屏视频可能只显示在页面内。
6. **文件上传/下载不是默认可用**：上传已用 `ACTION_GET_CONTENT` 接管；Blob 下载（身份导出、
   皮肤导出）仍需 B4 的桥接或 JS 注入。

## 7. 发布桥选型：JS Bridge（非 Capacitor）

### 7.1 决策

**采用 JS Bridge + 原生 WebRTC 发布，不采用 Capacitor 插件。**

理由：

- 方案 A' 不使用官方 Capacitor 容器的内嵌 Node；为一个纯 WebView 壳引入 Capacitor
  运行时和插件体系，增加体积与构建复杂度，收益仅为“插件调用形式”。
- Web UI 不需要改造成 Capacitor 应用；`@JavascriptInterface` 已足够暴露原生能力。
- 真正的难点不是调用形式，而是**媒体路径**：WebView 无法把原生 `MediaProjection` 的
  `VideoTrack` 变成页面的 `MediaStream`，所以不能简单 polyfill `getDisplayMedia`。
  必须由原生侧建立独立的 WebRTC PeerConnection，并把屏幕共享信令接入 WebSpeak 网关。

### 7.2 B5 对接草案

```text
Pad Shell
  ├─ WebView: WebSpeak UI（观看端、语音、频道）
  ├─ Native ScreenShareService
  │    ├─ MediaProjection → Surface → 原生 WebRTC VideoTrack
  │    ├─ 复用手机端 A3 的采集/编码经验（io.github.webrtc-sdk:android）
  │    └─ 通过网关的屏幕共享协议发送 offer / ICE / 停止
  └─ JS Bridge（控制面）
       ├─ isScreenShareAvailable()
       ├─ startScreenShare(options, requestId)
       ├─ stopScreenShare()
       └─ native → JS 事件：状态/错误/停止
```

- 控制面用 `window.TS6PadNative`，异步结果通过 `evaluateJavascript` 回传（`@JavascriptInterface`
  不能直接返回 Promise）；
- 媒体面由原生 WebRTC 直接与网关/对端协商，**不经过 WebView**；
- 网关侧需要新增/复用“原生发布者”入口：Pad 原生客户端携带 WebView 会话的 join ticket
  连接 `/ws/voice`（或专用 native publisher 端点），以同一会话身份发送 screen-share offer，
  网关再转发给 TS6 服务器/其他观看者；
- Web UI 的“共享屏幕”按钮由 B5 的轻量注入脚本接管：检测 `TS6PadNative` 后改为调用原生桥，
  隐藏/替换浏览器 `getDisplayMedia` 路径。B2s 不修改上游 Web 代码，只保留 Bridge 占位。

### 7.3 为什么不在 WebView 里 polyfill `getDisplayMedia`

即使原生桥返回一个 `MediaStream`，WebView 也无法把一个**原生**视频轨道注入页面的
`RTCPeerConnection`；`getDisplayMedia` 的返回值必须是 Web 内核内部的 `MediaStreamTrack`。
因此“原生采集 + 页面内发布”不可行，必须把发布会话放到原生侧。

## 8. 下一步

1. 真机执行第 5 节 #2–#7，把结果补进本文件；确认 WebView 版本、编解码、麦克风/回环统计。
2. 准备一个可达的自托管 WebSpeak 实例（或恢复网络访问上游 Demo），完成 #8–#9：
   真实网关加入语音、收听、观看 TS6 桌面共享。
3. B3：后台音频前台服务、音频焦点、刘海安全区、设置入口与全屏体验打磨。
4. B5：实现原生发布桥与网关侧 native publisher 端点，完成 Pad → TS6/WebSpeak 互通。
5. B4：按 F1–F14 清单逐项验证 Web 功能，记录 Blob 下载、多窗口、管理台/皮肤编辑器等缺口。
