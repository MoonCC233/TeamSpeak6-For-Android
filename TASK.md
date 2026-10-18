# 开发任务指令：TeamSpeak 6 For Android

> 本文件是本仓库的**主开发任务指令**（Task Brief），供 AI 编码代理（以及人类协作者）
> 理解项目背景、执行开发任务、判断任务完成标准。开始任何工作前请完整阅读本文件。

---

## 0. 项目背景

- 目标：打造一个功能完善、体验优秀的 **TeamSpeak（兼容 TS3 / TS6）Android 客户端**。
- 技术路线：基于两个成熟的开源项目进行开发，而非从零造轮子：
  1. **`YUAXI/TS6_Droid_CN`**（https://github.com/YUAXI/TS6_Droid_CN）
     —— TeamSpeak 3/6 的 Android 客户端，使用 Jetpack Compose 构建，
     由 `flamme-demon/TS6_Droid` 汉化并增强而来。**作为手机端开发基线。**
  2. **`EchoSixHIYA/WebSpeak-client-for-TeamSpeak`**（https://github.com/EchoSixHIYA/WebSpeak-client-for-TeamSpeak）
     —— 自托管 TeamSpeak Web 客户端 / 浏览器语音网关（TS3 + TS6，Opus 语音，
     支持跨平台 P2P 屏幕共享）。**作为 Pad 端 UI 与功能规格的参照标准。**
- 两端差异化适配：
  - **手机端（Phone）**：在现有开源客户端基础上，补齐缺失的核心互动功能——**屏幕共享（屏幕分享）及其观看**。
  - **Pad 端（Tablet）**：**全新开发**，完全复刻 Web 版（WebSpeak）的 **UI 界面** 与 **全部功能**，
    追求与 Web 端一致的完整体验。

### 最终目标

一个功能完善、体验优秀的 TeamSpeak 移动客户端：

- 手机端：在成熟基线上增强核心互动能力（屏幕共享等），保持轻量稳定；
- Pad 端：与 Web 端体验完全对齐的功能全集 + 大屏优化交互；
- 两端共享协议/音频等底层能力，长期可收敛为同一代码库的手机/平板分支形态。

---

## 1. 范围与边界

**纳入范围**

- TeamSpeak 3 / TS6 服务器的连接、语音、频道与用户管理、聊天等客户端核心功能；
- 屏幕共享：本机共享（发布端）与观看他人共享（订阅端）；
- 手机（竖屏/小屏）与 Pad（横屏/大屏）的差异化 UI 适配。

**暂不纳入范围**

- 服务端实现（直接对接现有 TS3 / TS6 服务器）；
- 服务器管理/运维类工具（如 ts6-manager 之类的管理面板）。

**必须先行验证的开放事项（见任务 A1 / B1）——2026-10-18 已核清：**

- ✅ 屏幕共享协议形态已确认：TS6 屏幕共享 = **客户端协议命令**（`requeststreaminfo` /
  `setupstream` / `joinstreamrequest` / `streamsignaling` / `removeclientfromstream` /
  `stopstream`，由服务端中转 WebRTC 信令）+ P2P/TURN WebRTC 媒体。
  详见 `docs/B1-webspeak-spec.md` §4 与 `docs/A1-phone-baseline.md` §5。
- ✅ 许可证与合规已核清（phone=GPL-3.0、tslib=MIT/Apache-2.0、pad=AGPL-3.0-only、
  teamspeak-js=MIT），组合规则与仓库组织见 `docs/adr/0002-licensing-and-workspace.md`。
- ⚠️ 新增关键风险（详见 `docs/adr/0001-pad-approach.md`）：
  1. Android 任何 Web 内核均不支持 `getDisplayMedia` → 屏幕共享**发布端必须原生实现**（MediaProjection）；
  2. WebSpeak 安卓容器内嵌 Node 18.20.4，而其 TS SDK 要求 Node ≥22.12（官方自述连通性未验证）
     → Pad 路线的前提是先做运行时 spike。

---

## 2. 参考项目

| 项目 | 地址 | 在本任务中的角色 |
| --- | --- | --- |
| TS6_Droid_CN | https://github.com/YUAXI/TS6_Droid_CN | 手机端开发基线（Jetpack Compose，TS3/TS6 客户端） |
| WebSpeak-client-for-TeamSpeak | https://github.com/EchoSixHIYA/WebSpeak-client-for-TeamSpeak | Pad 端 UI 与功能参照标准（自托管 Web 客户端 / 网关） |

### 上游锁定版本（2026-10-18 引入，完整表见根 `README.md`）

| 目录 | 上游 | 分支 @ 提交 | 许可证 |
| --- | --- | --- | --- |
| `phone/` | YUAXI/TS6_Droid_CN | `main@e79dbf6f` | GPL-3.0 |
| `tslib/` | flamme-demon/tslib_multi | `main@9326ed26` | MIT OR Apache-2.0 |
| `pad/` | EchoSixHIYA/WebSpeak-client-for-TeamSpeak | `master@32d84435` | AGPL-3.0-only |
| `references/teamspeak-js/` | EchoSixHIYA/teamspeak-js | `main@bd80a322` | MIT |

### 本机环境状态（2026-10-18 更新）

✅ 已具备：JDK 21、Node 24 + pnpm、GitHub 网络（本地代理 `127.0.0.1:7897`）、
Android SDK（`D:\Android\Sdk`：platform-35 / build-tools 35.0.0 / build-tools 34.0.0 / platform-tools / NDK `27.2.12479018`）、
Rust 1.99.1（含 `aarch64-linux-android` 目标）、cargo-ndk 4.1.2、VS BuildTools 2022（MSVC 14.44，Rust 宿主编译链接）。
❌ 待接入：arm64 真机（USB 调试）与可用的 TS3/TS6 测试服务器。

> 注意：`phone/` 预编译 JNI 库仅 `arm64-v8a` 为最新 → 模拟器不可用，需 arm64 真机或重建 `.so`。

---

## 3. 任务分解

### A. 手机端适配（基于现有项目）

**基础**：以 `YUAXI/TS6_Droid_CN` 为起点，在现有功能基础上新增缺失的核心功能。

| 编号 | 任务 | 产出物 | 验收标准 | 状态 |
| --- | --- | --- | --- | --- |
| A1 | 建立开发基线（调研）：引入上游代码，梳理模块结构、构建方式、功能清单；核对许可证 | `docs/A1-phone-baseline.md` | 结构与功能清单完整；许可证合规检查通过 | ✅ 2026-10-18 |
| A1b | 构建验证：安装 Android SDK 后构建基线 APK | 可安装的 debug APK（`phone/app/build/outputs/apk/debug/app-debug.apk`） | `assembleDebug` 成功，arm64 真机可连接 TS3/TS6 服务器 | 🔄 `assembleDebug` 成功（2026-10-18）；真机连服待设备接入 |
| A2 | 需求验证与协议规格：提取 TS6 屏幕共享完整信令流程；评估 `tslib/` 改动量 | `docs/A2-screen-share-protocol.md`（7 命令 + 7 通知 + 信令 JSON + 双流程 + 改动清单） | 明确发布/观看两端实现路径与依赖协议；Rust 改动范围可估算 | ✅ 2026-10-18 |
| A2b | 原型验证：vendor `tsclientlib` 并打"未知通知透传"补丁；tslib 命令/通知最小闭环（真实 TS6 服务器） | 可运行的 spike | 未知通知可达 tslib；命令发送被服务器接受 | 🔄 模块+单测+JNI（含 9 个命令函数）完成，release .so 已重编（arm64-v8a，9.7 MB）；探针就绪，待真实 TS6 服务器与真机闭环 |
| A3 | **屏幕共享（发布端）**：MediaProjection 采集 → WebRTC 编码 → 信令 → 对端可见 | 可用的共享功能 | 手机可发起屏幕共享，TS6 原生客户端与 WebSpeak 均能发现并观看 | 🔄 核心已实现（MediaProjection 采集 + WebRTC 发布端 + 信令；编译通过，.so 已重编）；待真机与对端互通验证 |
| A4 | **屏幕共享（观看端）**：发现共享源、订阅并渲染画面（含旋转/全屏适配） | 可用的观看功能 | 手机可观看来自 TS6 原生客户端与 WebSpeak 的屏幕共享 | 🔄 核心已实现（事件发现 + WebRTC 接收 + SurfaceViewRenderer 渲染 + 观看入口）；待真机互通验证与旋转/全屏打磨 |
| A5 | **手机端 UI/交互适配**：竖屏与小屏优化，与控制面板、频道树等现有界面整合 | 适配后的界面 | 单手可用、不遮挡语音控制核心操作 | ⬜ |

### B. Pad 端适配（全新开发）

**设计目标**：完全复刻 Web 版（WebSpeak）的 **UI 界面** 和 **全部功能**。

| 编号 | 任务 | 产出物 | 验收标准 | 状态 |
| --- | --- | --- | --- | --- |
| B1 | 获取并运行 Web 端 WebSpeak，拆解交互设计与完整功能清单 | `docs/B1-webspeak-spec.md`（功能清单 F1–F14 + 技术规格） | 清单覆盖 Web 端全部可感知功能，与 Web 端实际行为一致 | ✅ 2026-10-18 |
| B2 | 技术选型：确定 Pad 端技术栈与架构，输出 ADR | `docs/adr/0001-pad-approach.md`（方案 A' 已采纳） | 决策记录完整，工程形态明确 | ✅ 2026-10-18 |
| B2s | **WebView 兼容性与发布桥 spike**：壳应用加载 WebSpeak 实例，验证麦克风采集、WebRTC 收听/观看、Worklet/WASM、横屏布局；确定原生发布桥对接方式（Capacitor 插件 vs JS Bridge） | spike 结论报告 | 各项能力结论明确；发布桥技术方案确定 | ⬜ 优先 |
| B3 | 壳应用体验打磨：横屏/大屏全屏、刘海安全区、返回键、后台音频前台服务、常亮 | 壳应用 | 平板全屏体验流畅，后台场景语音不断 | ⬜ |
| B4 | 功能对齐 Web 版：壳内功能由 Web 本体提供，跟踪 F1–F14 逐项验证并记录缺口 | 验证清单 | 清单逐项与 Web 版一致（管理台/皮肤编辑器按产品决策降级） | ⬜ |
| B5 | 屏幕共享：观看（WebView 内）+ 发布（原生 MediaProjection 桥），与 WebSpeak/TS6 原生客户端互通 | 可互通的功能 | 互通验收通过 | ⬜ |
| B6 | 双端共享：A3/A4 原生媒体层与 Pad 发布桥统一设计 | 共享模块 | 减少重复实现，两端独立可发布 | ⬜ |

---

## 4. 工作流与分支策略

```mermaid
flowchart TB
  main[main 主干]
  main --> A[手机端分支]
  main --> B[Pad 端分支]
  A --> A1[引入 TS6_Droid_CN 基线]
  A1 --> A2[屏幕共享 发布+观看]
  A2 --> A3[手机端适配]
  A3 --> main
  B --> B1[WebSpeak 功能/UI 规格拆解]
  B1 --> B2[Pad 端工程与选型]
  B2 --> B3[功能逐项复刻]
  B3 --> main
```

- 手机端与 Pad 端**并行推进**，各自在独立分支上开发，稳定后合入 `main`；
- 公共底层能力（协议、音频、屏幕共享信令、状态管理）优先抽取为共享模块，
  避免两端重复实现（对应任务 B6）；
- 每个任务完成后提供可运行产物 + 变更说明，禁止"最后一次性合并"。

---

## 5. 执行约定（面向 AI 代理）

1. **先验证、后动手**：涉及上游代码、协议行为、许可证的内容，先做验证性任务（A1/B1），
   禁止未经核实直接假设功能现状或接口行为。
2. **保持与上游一致的风格**：复用上游的目录结构、命名与既有设计模式；
   代码标识符使用英文，注释与说明文档使用中文。
3. **UI 文案国际化**：所有界面文案放入资源文件（strings），禁止硬编码，便于后续多语言。
4. **任务粒度**：按第 3 节表格逐任务交付；每个任务的完成以"验收标准"为准，
   未满足验收标准不得标记完成。
5. **不破坏存量功能**：新增功能不得导致现有语音、频道管理等能力回归。
6. **合规**：遵循两个上游项目的开源许可证，保留版权与许可声明；如两者许可证要求冲突，
   在 ADR 中记录并选择合规方案。
7. **文档同步**：架构决策（ADR）、功能清单、验收记录随代码一同提交到仓库。

---

## 6. 里程碑

| 里程碑 | 内容 | 完成标志 |
| --- | --- | --- |
| M1 | 双端基线就绪 | 手机端基线可构建可连服；Pad 端工程初始化；两份规格/决策文档完成 |
| M2 | 手机端屏幕共享闭环 | A3 + A4 验收通过，与 TS6 原生客户端 / WebSpeak 互通 |
| M3 | Pad 端功能全集 | B4 + B5 验收通过，与 Web 端功能对齐 |
| M4 | 双端体验收敛 | 共享模块抽取完成，双端可独立发布，回归测试通过 |

---

## 7. 快速启动（Sequential Thinking Start）

AI 代理接到本指令后的参考执行顺序：

1. 阅读本文件第 0–5 节，理解背景、边界与约定；
2. ~~执行 **A1** 与 **B1**~~（2026-10-18 已完成，见第 8 节）；
3. 根据验证结果细化 A2–A5、B2–B6 的技术方案，如与预设有出入，先更新本文件再继续；
4. 按里程碑 M1 → M2 → M3 → M4 推进，逐任务交付并验收。

---

## 8. 进度记录

### 2026-10-18（项目启动）

- ✅ **A1 完成**：`phone/` 基线核清（架构 / 构建 / 功能 / 许可）；屏幕共享缺口确认（Kotlin 与 Rust 层均无实现）
  → `docs/A1-phone-baseline.md`
- ✅ **B1 完成**：WebSpeak 功能全集（F1–F14）与技术规格拆解；TS6 屏幕共享协议命令全部确认
  → `docs/B1-webspeak-spec.md`
- ✅ 上游四项目引入工作区（`phone/` `tslib/` `pad/` `references/teamspeak-js/`），提交 SHA 已锁定（见第 2 节）
- ✅ **ADR-0001 提议**：Pad 路线建议"增强官方 Capacitor 容器"，前置 Node 运行时 spike → `docs/adr/0001-pad-approach.md`
- ✅ **ADR-0002 采纳**：许可证合规与仓库组织方式 → `docs/adr/0002-licensing-and-workspace.md`
- ✅ **决策**：Pad 端采用**方案 A'（远程网关 + WebView 壳）**，ADR-0001 已定稿
- ✅ **环境**：Android SDK（platform-35 / build-tools 35.0.0 / NDK 27.2.12479018）、Rust 1.99.1（含 `aarch64-linux-android` 目标）、VS BuildTools 全部就位；cargo-ndk 安装中
- ✅ **A2 完成**：TS6 屏幕共享协议规格（7 命令 + 7 通知 + 信令 JSON + 双流程）→ `docs/A2-screen-share-protocol.md`；确认 `tsclientlib` 需打"未知通知透传"补丁（已定 vendor 方案）
- ✅ **A1b 构建成功**：`assembleDebug` 通过（9m17s，Gradle 8.14 经本地代理下载依赖）；APK 产出于 `phone/app/build/outputs/apk/debug/app-debug.apk`；真机连服验证待设备接入
- 🔄 **A2b 核心完成**：`screen_share` 模块（7 命令构造 + 7 通知解析 + 12 项单元测试）与 `Event::ScreenShare` 已落地（`cargo test -p tslib-core` 全量 63 项通过；`cargo check -p tslib-jni` 通过）；JNI/Python 映射为 `screen_share` 事件；验证探针 `tslib/examples/screen-share-probe` 待真实 TS6 服务器执行（`--publish`/`--join`）
- ✅ **A3 JNI 与绑定完成**：9 个屏幕共享 JNI 命令函数（含 `answer`/`iceCandidate` 信令写回）+ 两份 `Client.java` + `TsClient.kt` 命令包装与 `screenShareEvents` 事件流；`ScreenShareEvents.kt` 事件模型/解析
- ✅ **Android `.so` 交叉编译修复**：去除不必要的 `vendored-openssl`（依赖树为纯 Rust 加密栈）；Windows 交叉编译须 `CMAKE_GENERATOR=Ninja`（默认 VS 生成器在 `audiopus_sys` 处失败）；release + `CARGO_PROFILE_RELEASE_STRIP=symbols` → `libtslib_jni.so` **9.7 MB**（arm64-v8a，对比 debug 193 MB），9 个屏幕共享符号经 `llvm-nm` 验证导出
- ✅ **A3/A4 媒体层与 UI 实现**（编译通过）：`ScreenShareController`（发布端每观看者 PC + offer / 观看端 answer + ICE 编排）、`ScreenShareService`（`mediaProjection` 前台服务，Android 14+ 合规）、观看窗口 `ScreenShareViewerDialog`、顶栏共享开关与远端流入口；WebRTC 依赖 `io.github.webrtc-sdk:android:150.7871.01` → `docs/A3-screen-share-android.md`
- 🔜 **后续**：接入 arm64 真机后执行 A1b 连服 / A2b 探针闭环 / A3+A4 屏幕共享互通验证；B2s WebView 兼容性 spike（依赖一个可达的 WebSpeak 实例）
