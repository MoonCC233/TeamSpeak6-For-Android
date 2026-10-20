# TS6 Pad Shell（B2s）

Pad 端的原生 WebView 外壳。按 [ADR-0001](../../docs/adr/0001-pad-approach.md) 的方案 A'
实现：**UI 与业务功能来自自托管 WebSpeak 实例，原生层只负责 Android 无法在浏览器里完成的部分**。

当前目标（B2s）：验证 WebView 能否承载 WebSpeak 的麦克风采集、WebRTC 收听/观看、Worklet/WASM、
横屏布局，并确定原生屏幕共享发布桥的对接方式（JS Bridge）。

> B3 已加入后台语音前台服务、音频焦点与安全区留白，待真机验收；发布桥仍属 B5。

## 目录

```
pad/shell/
├── app/                         Android 应用模块（纯 Java，无第三方运行时依赖）
├── gradle/                      Gradle wrapper（与 phone/ 相同版本）
├── tools/
│   ├── demo-server.mjs          本地 WebSpeak UI + 能力探测页服务器
│   └── capability.html          WebView 麦克风/WebRTC/WebAudio/WebSocket 探测页
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 构建

前置：JDK 17、Android SDK（`compileSdk 35`），`local.properties` 中 `sdk.dir` 指向 SDK。

```powershell
cd pad/shell
.\gradlew.bat :app:assembleDebug
```

APK：`pad/shell/app/build/outputs/apk/debug/app-debug.apk`（包名 `com.yuaxi.ts6pad`，可与手机端共存）。

安装：

```powershell
adb install -r pad/shell/app/build/outputs/apk/debug/app-debug.apk
```

vivo 等系统安装器可能弹出风险确认；勾选“已了解风险”后继续安装即可。

## 连接 WebSpeak

首次启动会弹出网关地址对话框。支持三种方式：

1. **公网/自建 HTTPS（推荐）**：`https://你的域名`，网关需要有效证书。
2. **本机调试（localhost 安全上下文，麦克风可用）**：
   ```powershell
   adb reverse tcp:3040 tcp:3040
   ```
   然后在 Pad 中填 `http://localhost:3040`。
3. **在线 Demo**：`https://shanghai.webspeak.online`（网络可达时）。

> `getUserMedia` 要求安全上下文：HTTPS 或 `localhost`。
> 直接填局域网 `http://10.x.x.x:3040` 通常无法使用麦克风；请给网关配 HTTPS，
> 或在开发阶段用 `adb reverse` + `localhost`。
> 本机网关必须使用仓库锁定的 EchoSixHIYA fork SDK；Windows 调试用
> `WEBSPEAK_MOBILE=1` + `opusscript`。真实 TS6 语音会话/共享观看已在桌面 Chrome 验证，
> Pad WebView 真机待测；完整启动步骤见 `docs/B2s-pad-webview-spike.md` §4.2。

对话框中的“允许自签名 HTTPS 证书”仅用于调试，正式部署请使用有效证书。

## B3 后台语音与安全区

- 语音活跃时（页面 `getUserMedia` 返回带音频轨的流），壳启动 `VoiceSessionService`：
  `microphone` 类型前台服务 + 常驻通知 + `AUDIOFOCUS_GAIN`；此时退到后台不会调用
  `WebView.onPause()`，让 WebRTC/JS 继续运行。
- 音频焦点变化以 `window` 事件 `ts6pad:audiofocus` 暴露（`detail.state` 为
  `gain`/`transient-loss`/`loss`），留给 Web 侧后续接入静音/暂停策略。
- Android 13+ 首次授予麦克风权限时会同时请求通知权限；拒绝时前台服务仍运行，但通知可能不可见。
- 设置菜单可开关“安全区留白”（按 display cutout 给根视图留白，默认开）。
- 待真机验证：后台语音连续性、通知、音频焦点、切后台/回前台、刘海安全区。

## B2s 离线验证（无需真实网关）

构建 Web 端：

```powershell
cd pad
npm --prefix web ci
npm --prefix web run build
```

> `pad/web` 的 `src/shared/skin-css.ts` 会引用根 `pad` 的 `postcss`、`postcss-selector-parser`、
> `postcss-value-parser`。如果根 `npm ci` 被 git SDK 的网络阻塞，可先执行
> `npm install --no-save postcss postcss-selector-parser postcss-value-parser` 再构建；
> 当前工作区已经生成 `pad/web/dist`，也可以直接使用。
> 注意：这个 git SDK 是网关**必需的 EchoSixHIYA fork**，不要替换成 npm 上的
> `@honeybbq/teamspeak`（握手会失败）；网络受限时的 fork 源码获取方式见 spike §4.2。
> `demo-server.mjs` 本身只用 Node 内置模块，不依赖 `ws` 等 npm 包。

启动本地演示服务器：

```powershell
cd pad/shell
node tools/demo-server.mjs 8080
```

它提供：

| 路径 | 内容 |
| --- | --- |
| `http://localhost:8080/` | 构建后的 WebSpeak UI（mock 了 `/api/public-config`，可渲染加入页） |
| `http://localhost:8080/capability.html` | WebView 能力探测页 |
| `http://localhost:8080/api/public-config` | 固定目标 `ts.mooncc.cn#9987` 的 mock 配置 |
| `ws://localhost:8080/ws/echo` | WebSocket 回声 |

手机通过 USB 连接后：

```powershell
adb reverse tcp:8080 tcp:8080
```

在 Pad 壳中把网关地址改为：

- `http://localhost:8080/capability.html`：跑能力探测；
- `http://localhost:8080/`：看 WebSpeak UI 的横屏渲染。

探测页会逐项显示 `isSecureContext`、`getUserMedia`、`RTCPeerConnection`、`AudioWorkletNode`、
`WebSocket`、`WebAssembly`、`getDisplayMedia`（Android WebView 预期缺失）等结果，并提供：

- 麦克风采集 + 电平表；
- 麦克风/振荡器 + Canvas 视频的本机 WebRTC 回环（含 `getStats` 收包/解码统计）；
- WebSocket 回声；
- `getDisplayMedia` 失败探测。

## 壳已实现

- 横屏沉浸式 WebView，隐藏系统栏，屏幕常亮，刘海 `shortEdges` + B3 安全区留白（display cutout）；
- 网关地址持久化、修改、清除 WebView 数据；
- 麦克风运行时权限 → `WebChromeClient.onPermissionRequest` 桥接；
- `getUserMedia` 仅对当前网关 origin 授权，其他 origin 拒绝；
- HTTPS 证书错误提示 + 可选自签名调试；外部链接交给系统浏览器；
- 文件选择器（Web 端上传/导入用）；
- HTML5 全屏（`onShowCustomView`）与返回键处理；
- 后台语音：注入 `getUserMedia` 钩子，语音活跃时启动 `microphone` 前台服务，后台不暂停 WebView；
- 音频焦点：`VoiceSessionService` 请求/释放 `AUDIOFOCUS_GAIN`，向页面派发 `ts6pad:audiofocus` 事件；
- `window.TS6PadNative` JS Bridge（版本/平台/网关/`setVoiceActive`/`isScreenShareAvailable=false`）。

## 已知限制 / 后续

| 项 | 状态 |
| --- | --- |
| 屏幕共享**发布**（`getDisplayMedia`） | Android WebView 不支持；B5 用原生 `MediaProjection` + 原生 WebRTC，通过 JS Bridge/网关协议对接 |
| 屏幕共享**观看** | 桌面 Chrome 已用真实网关验证 TS6 共享观看（2560×1600）；Android WebView 待真机 |
| 后台音频 | ✅ B3 已实现 `microphone` 前台服务 + 音频焦点，语音活跃时后台不暂停 WebView；待真机连续性验证 |
| 设置入口 | 右上角半透明齿轮；B3 安全区留白已加入，手势/菜单仍可继续打磨 |
| Blob/文件下载（身份导出等） | 暂未实现，B4 处理 |
| `window.open` / 多窗口（管理台、皮肤编辑器） | 当前禁用，B4 按产品决策降级或接管 |
| 本地 HTTP 局域网 | 麦克风受安全上下文限制；正式使用请 HTTPS |

## 许可

本目录位于 `pad/` 上游目录内，按 **AGPL-3.0-only** 提供（见 `pad/LICENSE`）。
新增外壳代码与 `pad/` 组合同样按 AGPL-3.0-only 分发。
