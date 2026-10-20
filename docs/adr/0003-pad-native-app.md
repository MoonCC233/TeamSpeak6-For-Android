# ADR-0003：Pad 端改为原生 Compose 应用并共享 `:core`

- 状态：**已采纳（Accepted）**，取代 [ADR-0001](0001-pad-approach.md)
- 日期：2026-10-20
- 相关：`TASK.md` 任务 B2/B2s/B3/B4/B5/B6、`docs/B1-webspeak-spec.md`

## 背景

ADR-0001（2026-10-18）为 Pad 选择了 **方案 A'：远程 WebSpeak 网关 + WebView 壳 + 原生扩展**，
并据此实施了 B2s（WebView 兼容性 spike）与 B3（壳应用体验）。2026-10-20，项目负责人明确改变
目标：**Pad 端不做 WebView 套壳，改为原生应用，尽可能还原 WebSpeak 的 UI**。

促使调整的已知事实：

1. WebView 壳的 UI 与功能完全依赖一个可达的 WebSpeak 网关实例，离线不可用，部署与版本
   对齐成本高；
2. Android WebView 不支持 `getDisplayMedia`，屏幕共享发布端本来就必须用原生
   MediaProjection + 原生 WebRTC 实现，WebView 只能承担观看端；
3. 手机端 A2–A4 已经验证了一套**原生 TS6 协议 + 语音 + 屏幕共享**实现（`tslib` JNI、
   `TsClient`、`AudioBridge`、`ScreenShareController`），Pad 可以直接复用，不必重复实现；
4. 用户明确要求原生体验，UI 保真度通过 Compose 按 WebSpeak 的 Vue 模板与样式逐项复刻来达成，
   而不是通过承载 Web 页面获得。

## 决策

**Pad 端采用原生 Android 应用（Kotlin + Jetpack Compose），与手机端共享 `:core` 原生核心。**

1. **工程形态**
   - 在现有 `phone/` Gradle 根工程内新增 `phone/pad/` 应用模块（`applicationId =
     com.yuaxi.ts6pad`，namespace `dev.tsdroid.pad`，minSdk 29 / compileSdk 35）；
   - 与 `:app`（手机端）并列依赖 `:core`，复用同一套协议、音频、数据与服务层；
   - 手机端 `:app` 的包名、签名、发布流水线与既有行为保持不变。

2. **核心共享（B6 落地）**
   - `phone/core/`（`:core`，namespace `dev.tsdroid.core`）承接：`dev.tslib` JNI 类与
     `arm64-v8a` `.so`、`bridge/`、`data/`、`screen/`、`service/`、`viewmodel/`、
     主题/图标及这些代码所需资源；
   - 通知与悬浮窗的「打开应用」Intent 通过 PackageManager 解析宿主启动 Activity，
     不反向依赖手机端 `MainActivity`；
   - 通知渠道 ID 保持 `ts_connection`，已有安装的渠道设置不丢失。

3. **连接方式**
   - Pad 直接通过 `tslib` 连接 TS3/TS6 服务器，**不再依赖 WebSpeak 网关或 Node 运行时**；
   - `pad/web` 仅保留为 UI/交互规格参照（AGPL-3.0 上游），不参与 Pad 构建；
   - `pad/shell`（WebView 壳）与 B2s spike 记录删除。

4. **UI 路线**
   - 以 `pad/web/src/views/WebClient.vue`、`components/web-client/*` 及全局样式为视觉与
     交互规格，在 Compose 中实现平板优先布局（频道树 / 成员卡片 / 聊天 / 底部语音栏 /
     屏幕共享卡片）；
   - 深色主题、间距、圆角、状态色与信息层级以 Web 端为准；大屏适配按横屏优先设计。

5. **首期范围**
   - 交付：WebSpeak 风格 UI + 真实连接、频道树、成员状态、文字聊天、语音收发与基础音频控制；
   - 后置：屏幕共享观看/发布、文件管理、管理台/皮肤等。屏幕共享直接复用 `:core` 的
     A3/A4 原生实现，不再走 B5 的「JS Bridge + 网关 native publisher」方案。

6. **许可证**
   - Pad 应用代码（`phone/pad/`）按 **AGPL-3.0-only** 提供，保留上游 WebSpeak 的版权与许可声明；
   - `:core` 源自 GPL-3.0 的手机端基线，经 ADR-0002 第 13 条与 AGPL-3.0 组合；Pad 分发物
     整体按 AGPL-3.0 处理，手机端单独分发仍为 GPL-3.0；仓库内保留 `phone/LICENSE` 与
     `pad/LICENSE`。

## 影响

- **正面**：无网关依赖；UI 不再受 WebView 能力与安全上下文限制；后台语音、音频路由、
  屏幕共享与手机端使用同一套已验证实现；双端修 bug 只改一份核心。
- **代价/风险**：UI 需要逐页复刻，功能对齐需按 F1–F14 清单逐项验收；`phone/pad` 的
  UI 工作量成为 Pad 端主要成本；共享核心的 API 变更需要同时回归手机端（构建 + 单测）。
- **B2s/B3/B5 终止**：WebView 兼容性 spike、壳体验、网关 native publisher 不再推进；
  历史结论保留在 git 历史中。
- **B4 重新定义**：从「壳内功能验证」改为「原生 UI/功能对齐 WebSpeak F1–F14」。
