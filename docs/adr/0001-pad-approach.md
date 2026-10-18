# ADR-0001：Pad 端技术路线

- 状态：**已采纳（Accepted）——采用方案 A'（远程网关 + WebView 壳 + 原生扩展）**
- 日期：2026-10-18（决策日）
- 相关：`docs/B1-webspeak-spec.md`、`TASK.md` 任务 B2

> 决策记录：项目负责人于 2026-10-18 在三个候选方案中选择 **方案 A'**。
> 理由：避开内嵌 Node 运行时（18.20.4）与 SDK（要求 ≥22.12）的版本阻塞；
> Web UI 与功能 = Web 版本体（保真度天然 100%）；网关跑在真实服务器上（Node ≥22.5 无限制）。
> 代价：应用必须依赖一个可达的自部署/托管 WebSpeak 实例；原生屏幕共享发布桥与后台音频仍需自研。

## 背景

Pad 端目标："完全复刻 Web 版（WebSpeak）的 UI 界面和全部功能，确保最终体验与 Web 版一致。"

已核实的约束（2026-10-18）：

1. WebSpeak 官方仓库**已包含 Android 容器**（Capacitor + `@capawesome/capacitor-nodejs`），Web UI 原样复用，内嵌网关跑在 loopback。
2. 该容器内嵌 **Node 18.20.4**，而 WebSpeak 依赖的 TS SDK 要求 **Node ≥22.12**；nodejs-mobile 上游最新即 18.20.4，无升级路线。官方 README 自述真机连通性未验证。
3. **Android 上任何 Web 内核（WebView、Chrome for Android、Samsung Internet）都不支持 `getDisplayMedia`** —— 屏幕共享"发布端"在任何 Web 形态下都必须用原生 MediaProjection 实现；"观看端"在 WebView 内可用（WebRTC 收流正常）。
4. 自建原生客户端（Compose + tslib）需要从零复刻全部 UI 与业务功能，且协议层的屏幕共享工作与手机端 A2–A4 完全重复。

## 候选方案

### 方案 A：增强官方 Android 容器（Capacitor + Web UI 复用）——**建议采用（先 spike）**

在 `pad/web/android` 基础上继续开发：

- Web UI 与功能 = Web 版本体，**UI/功能保真度天然 100%**；
- 自包含（内嵌网关，无需外部服务器）；
- 需完成的工程：① 内嵌 Node 运行时可行性验证/升级（spike）；② 原生 MediaProjection 屏幕共享发布桥（Capacitor 插件，媒体经原生 WebRTC 或注入 WebView）；③ 前台服务与后台音频；④ 平板大屏布局打磨（Web 端已响应式）；⑤ 管理台/皮肤编辑器的可用性决策。

风险：① 是硬前提（Node 18.20.4 跑 22.12 要求的 SDK）。若 spike 失败且无法修复运行时，方案 A 只能降级为方案 A'。

### 方案 A'：远程网关 + WebView 壳 + 原生扩展（退路）

Pad 应用不内嵌网关，指向用户自部署/托管的 WebSpeak 实例（Web 版本身即此模型）：

- 无 Node 运行时问题（网关跑在真正的服务器上）；
- 壳应用负责：原生屏幕共享发布（MediaProjection + 原生 WebRTC，按网关浏览器协议接入 `/ws/voice` 的 screen-share 消息）、后台音频前台服务、平板全屏体验；
- 缺点：必须依赖一个可达的网关实例；离线不可用。

### 方案 B：原生 Compose 重写（不推荐作为首选）

复用 `tslib/` 协议层 + 原生 UI 逐页复刻 Web 版：

- 优点：与手机端共享全部底层（B6 模块复用最彻底）、原生性能与后台能力最强；
- 缺点：工作量 = 手机端全部功能 + Pad 全部功能（数倍于方案 A）；UI 保真靠"照着画"，不可能保证与 Web 版完全一致；
- 适用条件：只应在明确要求"纯原生实现"时启动。

## 决策（2026-10-18）

**采用方案 A'。** 实施要点：

1. **壳应用**（`pad/` 内新增原生外壳工程，基于 Capacitor 或纯 WebView）：
   - 目标形态：可配置网关地址（支持多实例/扫码或手输），横屏优先，平板大屏全屏体验；
   - Web UI 直接加载网关实例（Web 版本体），功能随 Web 端更新而更新；
2. **原生屏幕共享发布桥**：MediaProjection 采集 → 原生编码 → 通过网关浏览器协议
   （`/ws/voice` 的 screen-share 消息）接入会话，媒体经 WebRTC P2P/TURN；观看端直接由 WebView 内的 Web 应用承担；
3. **后台音频**：前台服务 + 音频焦点处理，保证横屏/后台场景语音不断；
4. **B2s spike 目标更新**：验证 Android WebView 加载 WebSpeak 实例的兼容性——
   麦克风采集授权、WebRTC 收听/观看、AudioWorklet/WASM Opus、屏幕观看、横屏布局，
   以及原生发布桥与网关协议的对接方式（Capacitor 插件 vs JS Bridge）。
5. **离线/自包含形态（方案 A）** 暂不实施，但保留未来评估：若 nodejs-mobile 出现
   Node ≥20 运行时，可重新评估内嵌网关。

## 影响

- B2s 重新定义为 **WebView 兼容性与发布桥 spike**（不再是内嵌 Node 运行时验证）：壳应用加载 WebSpeak 实例后，验证麦克风采集、WebRTC 收听/观看、Worklet/WASM、横屏布局，并确定原生发布桥与网关协议的对接方式。
- 屏幕共享的原生媒体层（MediaProjection + WebRTC 发布桥）成为手机/Pad 双端的共享设计资产，B6 模块复用的重心。
- 壳应用体验（横屏、安全区、后台音频、常亮）取代"逐页复刻 UI"成为 Pad 端主要原生工作量。
- `pad/` 与 `phone/` 的许可证（AGPL-3.0 / GPL-3.0）差异不阻塞本决策，组合规则见 ADR-0002。
