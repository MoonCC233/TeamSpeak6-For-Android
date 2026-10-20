# TeamSpeak 6 For Android

面向 Android 的 TeamSpeak（兼容 TS3 / TS6）客户端开发项目：**手机端**在成熟开源客户端基础上补齐屏幕共享等核心功能；**Pad 端**追求与 Web 版（WebSpeak）完全一致的 UI 与功能体验。

> 开发任务总纲见 [`TASK.md`](./TASK.md)；上游调研与决策记录见 [`docs/`](./docs/)。

---

## 目录结构

| 目录 | 内容 | 说明 |
| --- | --- | --- |
| `phone/` | Android 客户端（Jetpack Compose + Rust `tslib`） | 手机端开发基线，源自 `YUAXI/TS6_Droid_CN`（GPL-3.0） |
| `tslib/` | Rust 协议/音频核心（`tslib_multi`） | `phone` 所依赖的 JNI 原生库源码（MIT OR Apache-2.0），屏幕共享协议改动点 |
| `pad/` | Web 网关 + Vue 前端 + Capacitor 安卓容器 | Pad 端参照/基线，源自 `EchoSixHIYA/WebSpeak-client-for-TeamSpeak`（AGPL-3.0-only） |
| `references/teamspeak-js/` | TeamSpeak 协议 TypeScript 实现 | WebSpeak 的底层 SDK（MIT），**TS6 屏幕共享协议的事实参考实现** |
| `docs/` | 调研文档、ADR、规格文档 | 交付文档随代码一同提交 |
| `TASK.md` | 主开发任务指令 | 供 AI 代理与协作者阅读，含里程碑与验收标准 |

## 上游锁定版本（2026-10-18 引入）

| 本地目录 | 上游仓库 | 分支 @ 提交 | 引入日期 | 许可证 |
| --- | --- | --- | --- | --- |
| `phone/` | [YUAXI/TS6_Droid_CN](https://github.com/YUAXI/TS6_Droid_CN) | `main@e79dbf6f` | 2026-10-18 | GPL-3.0 |
| `tslib/` | [flamme-demon/tslib_multi](https://github.com/flamme-demon/tslib_multi) | `main@9326ed26` | 2026-10-18 | MIT OR Apache-2.0 |
| `pad/` | [EchoSixHIYA/WebSpeak-client-for-TeamSpeak](https://github.com/EchoSixHIYA/WebSpeak-client-for-TeamSpeak) | `master@32d84435` | 2026-10-18 | AGPL-3.0-only |
| `references/teamspeak-js/` | [EchoSixHIYA/teamspeak-js](https://github.com/EchoSixHIYA/teamspeak-js) | `main@bd80a322` | 2026-10-18 | MIT |

上游沿革：`phone` 源自 [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid) 的汉化增强分支；`tslib` 的协议实现基于 [ReSpeak/tsclientlib](https://github.com/ReSpeak/tsclientlib)（Apache-2.0）。

## 本机环境状态

| 依赖 | 状态 | 用途 |
| --- | --- | --- |
| JDK 21 | ✅ 已安装 | Gradle / Android 构建 |
| Node.js 24 + pnpm 12 | ✅ 已安装 | `pad/` 网关与前端开发 |
| GitHub 网络 | ✅ 经本地代理 `127.0.0.1:7897`（`git -c http.proxy=...`） | 拉取依赖与上游更新 |
| Android SDK | ✅ `D:\Android\Sdk`（platform-35、build-tools 35.0.0、platform-tools、NDK `27.2.12479018`） | 构建 `phone/` 与 `pad/` 的安卓产物 |
| Rust 1.99 + `aarch64-linux-android` 目标 + cargo-ndk | ✅ 已安装（`~\.cargo\bin`） | 修改 `tslib/` 并交叉编译 arm64 库 |
| VS Build Tools 2022（MSVC 14.44） | ✅ 已安装 | Rust 在 Windows 上的宿主编译链接 |

> 构建提示：Gradle 首次构建需下载依赖，直连超时的环境可加代理参数再执行：
> `$env:JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'`
> （Cargo 同理可用 `$env:HTTP_PROXY` / `$env:HTTPS_PROXY`。）

> 注意：`phone/` 的预编译 JNI 库当前仅 `arm64-v8a` 为最新（x86_64 已过期、无 32 位），**模拟器不可直接运行**，需 arm64 真机或自行重建 `.so`。

## 快速开始

1. 阅读 [`TASK.md`](./TASK.md) 与 `docs/adr/` 中的决策记录；
2. 安装 Android SDK（见 `TASK.md` 进度记录中的待办）；
3. 按里程碑 M1 → M2 → M3 → M4 推进，逐任务验收。

## 构建与发布（GitHub Actions）

发布流水线：`.github/workflows/release.yml`（当前只发布手机端 `phone/`，Pad 端成熟后再接入）。

| 触发方式 | 行为 |
| --- | --- |
| 推送 tag `v<版本>`（如 `v2.1.7-Han`） | 校验 tag 与 `phone/app/build.gradle.kts` 的 `versionName` 一致 → 构建 → 发布 Release |
| Actions 页面手动触发（`workflow_dispatch`） | 版本号留空则按 `patch/minor/major` 自动递增并同步 `versionCode`；构建通过后自动提交版本号、打 tag、发布 Release；取消勾选 `publish` 则只构建到 Artifacts |

Release 资产：`TS6_Droid_Han-<版本>.apk`（arm64-v8a）、同名 `.sha256` 校验文件、`mapping-<版本>.txt`（R8 混淆映射表）。
签名使用以下 4 个仓库 Secrets；缺失时回退 CI 临时 debug 密钥（**每次运行的证书都不同，无法覆盖安装**），并自动把 Release 标记为 Pre-release（仅供测试）：

```powershell
# 生成密钥（只需一次，务必离线备份；丢失后无法覆盖安装旧版本）
keytool -genkeypair -v -keystore release.keystore -alias ts6droid -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
# 取 base64，填入 SIGNING_KEYSTORE_BASE64
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))
```

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `release.keystore` 的 base64 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名（示例 `ts6droid`） |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

> 注意：`phone/gradle.properties`（`android.useAndroidX` 等）未纳入版本控制，工作流会在 CI 中自动生成；
> `phone/app/src/main/jniLibs/arm64-v8a/libtslib_jni.so` 是预编译产物，改动 `tslib/` 后需先在本机执行
> `gradlew buildRustLibs` 重建并提交，CI 不会重新编译 Rust。

## 许可证说明

本仓库是多个独立上游项目的**聚合开发仓**，各子目录遵循其原始许可证（见上表），版权归各上游作者所有；新增的跨端文档与工具以 GPL-3.0-or-later 授权。组合分发与共享模块的合规要求见 [`docs/adr/0002-licensing-and-workspace.md`](./docs/adr/0002-licensing-and-workspace.md)。
