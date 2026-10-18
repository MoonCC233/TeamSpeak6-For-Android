# ADR-0001：Pad 端技术路线

- 状态：**提议（Proposed）**——待项目负责人确认后转为 Accepted
- 日期：2026-10-18
- 相关：`docs/B1-webspeak-spec.md`、`TASK.md` 任务 B2

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

## 建议

1. **立即执行 Node 运行时 spike（新的 B2s 任务）**：在 Node 18.20.4 环境运行 `pad/` 网关 + `teamspeak-js` SDK，对真实 TS3/TS6 服务器验证：能否导入、握手、收发音频与屏幕共享信令。
   - 通过 → 方案 A 定稿，按 ①②③④⑤ 推进；
   - 失败 → 评估两条修补路径：(a) 将 SDK/WebSpeak 降级到兼容 Node 18 的历史版本（记录功能损失）；(b) 自建 Node20+ 的 nodejs-mobile 运行时（高风险，仅当 (a) 不可接受）。(b) 都不可行 → 转方案 A'。
2. **无论哪条路线，MediaProjection 原生发布桥都是必需品**（约束 3），应在 spike 后优先启动，因为它同时服务于 Pad（方案 A/A'）与手机端（A3 的媒体层可共享设计）。
3. 方案 B 仅作为长期备选，在方案 A 系全部出路验证失败后再评估。

## 影响

- Pad 端首个里程碑从"工程初始化"改为"运行时 spike"（B2s），预计 1–2 天内可出结论。
- 屏幕共享的原生媒体层（MediaProjection + WebRTC）成为手机/Pad 双端的共享设计资产，B6 模块复用的重心。
- `pad/` 与 `phone/` 的许可证（AGPL-3.0 / GPL-3.0）差异不阻塞本决策，组合规则见 ADR-0002。
