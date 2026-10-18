# A1 调研交付：手机端基线（TS6_Droid_CN）

> 状态：✅ 调研完成（构建验证待 Android SDK 就绪，见 A1b）
> 来源：`phone/`（上游 `YUAXI/TS6_Droid_CN` `main@e79dbf6f`，2026-10-17，GPL-3.0）

## 1. 项目定位

- TeamSpeak 3 / TS6 的 Android 客户端，**Jetpack Compose** 构建界面，核心协议与音频由 **Rust `tslib`** 驱动（JNI 调用 `libtslib_jni.so`）。
- 上游链：`flamme-demon/TS6_Droid`（原始英文项目）→ `YUAXI/TS6_Droid_CN`（简中汉化 + 功能增强）。
- 维护活跃：v2.1.6-Han 发布于 2026-10-17（引入上游次日），含 CI 单元测试与签名发布流水线。

## 2. 技术架构

```
Compose UI (phone/app/src/main/kotlin/dev/tsdroid/ui)
   ↓
ViewModel / Service（背景悬浮窗、音频服务）
   ↓
bridge（TsClient.kt / AudioBridge.kt 等，JNI 边界）
   ↓
libtslib_jni.so（预编译，app/src/main/jniLibs/arm64-v8a）
   ↓
tslib Rust 工作区（tslib/，独立仓库 flamme-demon/tslib_multi）
   ├─ tslib-core    连接、身份(ECDH)、协议、文件、权限
   ├─ tslib-audio   Opus 编解码 / 采集播放（cpal）
   ├─ tslib-chat    BBCode 聊天
   ├─ tslib-channel 频道树
   ├─ tslib-bot / tslib-ffi / tslib-python / tslib-jni
   └─ 协议实现基于 tsclientlib（Apache-2.0）
```

关键文件：

- `phone/app/build.gradle.kts` —— 构建配置、签名回退策略、`buildRustLibs` 任务（cargo-ndk，引用本仓库外路径 `../tslib_multi`）
- `phone/app/src/main/kotlin/dev/tsdroid/bridge/TsClient.kt` —— JNI `Client` 封装；所有指令走 `launchNativeCommand`
- `phone/app/src/main/kotlin/dev/tsdroid/TsDroidApp.kt` —— `System.loadLibrary("tslib_jni")`
- `tslib/crates/tslib-core`、`tslib/crates/tslib-jni`（对应 `tslib/java/` 的 Java 类）

## 3. 构建配置

| 项 | 值 |
| --- | --- |
| AGP / Kotlin / Gradle | 8.7.3 / 2.1.0 / 8.14（wrapper） |
| compileSdk / minSdk / targetSdk | 35 / 29 / 35 |
| Java 目标 | 17（本机 JDK 21 可运行 Gradle 8.14） |
| ABI | 仅 `arm64-v8a`（`jniLibs` 中 x86_64 副本已过期，32 位缺失） |
| 签名 | 缺 `keystore.properties` 时回退 debug 签名，本地 `assembleDebug` 开箱即用 |
| CI | `.github/workflows/android-build.yml`（debug + 单测）、`android-release.yml`（标签发布签名 APK） |

**构建前置**：Android SDK（platform 35、build-tools、platform-tools）；修改 Rust 核心时还需要 Rust 1.75+、cargo-ndk、NDK `27.2.12479018`。

## 4. 现有功能清单（基线能力）

- **连接**：TS3/TS6 服务器、书签、服务器信息卡、断线指数退避重连（WiFi/蜂窝切换立即重连）、云备份/迁移。
- **语音**：按住说话 / 语音激活 / 开放麦三种模式；麦克风增益 0~400%、输出增益 0~800%（软限幅）；按成员音量/静音（UID 持久化）；AEC 回声消除；音频焦点让位；低延迟语音轨自愈。
- **频道与成员**：频道树（含 TS3 spacer 渲染）、成员列表、说话状态、TS3/6 权限检查。
- **聊天**：频道/服务器/私聊（按 UID 归档）、未读角标、聊天记录持久化；内联图片按开关加载。
- **文件管理器**：浏览/下载/上传/新建/重命名/删除/分享、应用内图片预览。
- **身份**：UID 查看、`identity.ini` 导入/导出（换机保权限）。
- **体验**：Material3 + 磨砂材质、自绘图标集、三语（中/英/法）、动漫壁纸与自定义背景、壁纸缓存导出。
- **后台**：悬浮窗（成员/频道面板、按住说话、未读角标）、电池优化白名单入口、麦克风看门狗。
- **工程**：46 项单元测试、R8 精简（~15 MB APK）、应用内更新、崩溃报告浮层、应用内日志查看器。

## 5. 缺口分析：屏幕共享（核心任务）

**结论：屏幕共享（发布 + 观看）在 Kotlin 层与 Rust 层完全不存在。**

证据：

- `grep -i "screen.?share|屏幕共享|共享屏幕" phone/app/src/main/**/*.kt` → 0 命中；
- `grep -i "screen.?share|voip|webrtc" tslib/crates/**/*.rs` → 仅命中 Opus 的 `Application::Voip` 配置（与屏幕共享无关），无任何信令/媒体实现。

### 5.1 TS6 屏幕共享的协议形态（已核实）

从 WebSpeak 网关（`pad/src/server/screen-share-coordinator.ts`）确认：TS6 屏幕共享是**客户端协议命令 + 服务端中转 WebRTC 信令 + P2P 媒体**：

| 协议命令 | 用途 |
| --- | --- |
| `requeststreaminfo clid=<id>` | 查询某客户端的共享流信息（发现） |
| `setupstream` | 建立/发布流 |
| `joinstreamrequest` | 请求观看（加入流） |
| `streamsignaling` | 中转 SDP / ICE 信令 |
| `removeclientfromstream` | 移除观看者 |
| `stopstream` | 停止共享 |

媒体优先 WebRTC/ICE 直连；默认 STUN `stun:turn.teamspeak.com:3478`，可配置外部 TURN（`WEBSPEAK_SCREEN_SHARE_ICE_SERVERS`）；WebSpeak 网关不承载媒体。

### 5.2 手机端实现路径（A2/A3/A4 工作假设）

1. **协议层（Rust，`tslib/`）**：新增上述命令的发送与对应服务端通知的解析（通知名称与参数需从 `pad/` 协调器与 `references/teamspeak-js/` 的执行路径中完整提取）。**待验证**：tslib 现有命令发送通道能否透传新命令、通知分发是否需扩展枚举。
2. **桥接层（JNI/Kotlin）**：暴露流事件（发现/开始/停止/观众加入/信令消息）与操作（发布/观看/停止）。
3. **媒体层（Android 原生）**：引入 WebRTC 库（如 `org.webrtc` 系发行版）实现发布（`MediaProjection` 屏幕采集 → 编码 → 发送）与观看（接收 → `SurfaceViewRenderer` 渲染）。
4. **UI**：频道/成员上的共享状态与入口、观看窗口（含旋转/全屏）、权限与前台服务保活。

### 5.3 风险与前置

- tslib 是否支持任意命令透传与未知通知 → 决定 Rust 改动量（A2 首要验证项）。
- 通知名/参数格式属于**逆向工程范畴**，以 `references/teamspeak-js/` 为第一参考。
- 重建 `libtslib_jni.so` 需要 Rust + cargo-ndk + NDK `27.2.12479018`（环境未就绪）。
- 测试需 arm64 真机（模拟器无可用 `.so`）。
- 与 TS6 原生客户端及 WebSpeak 的**互通**是 A3/A4 的验收硬标准。

## 6. 结论

- 基线工程质量高、功能面广，作为手机端起点完全成立（M1 目标可达）。
- 屏幕共享是本项目在手机端的**唯一重大缺口**，且必须同时动 Rust 协议层与 Android 媒体层；工作量与风险集中在协议逆向与原生 WebRTC 集成。
