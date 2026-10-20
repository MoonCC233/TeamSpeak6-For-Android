# TS6 Pad（`phone/pad`）

Pad 端原生 Android 客户端：Kotlin + Jetpack Compose，直接通过 `:core` 的 tslib JNI /
WebRTC 语音栈连接 TeamSpeak 3/6 服务器。不是 WebView 壳，也不依赖 WebSpeak 网关。

- `applicationId`：`com.yuaxi.ts6pad`
- `namespace`：`dev.tsdroid.pad`
- minSdk 29 / compileSdk 35 / targetSdk 35
- ABI：仅 `arm64-v8a`（复用 `:core` 的预编译 `libtslib_jni.so`）
- 许可证：**AGPL-3.0-only**，见本目录 [`LICENSE`](./LICENSE)

## 当前范围

- 加入页：书签、地址/昵称/密码/频道、麦克风权限引导、深浅两套大屏布局
- 已连接工作区：服务器栏、频道树、成员卡片与成员操作、语音活动舞台
- 频道聊天与私聊、未读计数、离线成员历史
- 语音收发、PTT / VAD / 常开模式、麦克风/扬声器静音、输入增益、VAD 阈值、
  全局音量、噪声抑制（下次连接生效）
- 后台语音由 `:core` 的前台服务承载；Pad 没有悬浮窗，启动时会关闭该设置

屏幕共享按 ADR-0003 后置，后续直接复用 `:core` 的 A3/A4 实现。

## 构建

```powershell
cd phone
.\gradlew.bat :pad:assembleDebug
# release（缺少签名凭据时回退 debug 密钥）
.\gradlew.bat :pad:assembleRelease -x buildRustLibs
```

产物：`phone/pad/build/outputs/apk/debug/pad-debug.apk`。

## 结构

- `MainActivity.kt`：权限流程、会话级 `ViewModelStoreOwner`、启动恢复已有连接
- `ui/JoinScreen.kt`：加入页
- `ui/WorkspaceScreen.kt`：已连接工作区、服务器切换/离开确认
- `ui/PeoplePanel.kt`：频道树 + 成员列表
- `ui/VoiceStage.kt`：语音活动卡片
- `ui/ChatPanel.kt`：频道/私聊消息与输入
- `ui/AudioDock.kt`、`ui/AudioSettingsDialog.kt`：底部语音栏与音频设置
- `ui/PadTheme.kt`、`ui/PadComponents.kt`：WebSpeak 深色主题与通用组件

UI/交互参照：本仓库 `pad/`（WebSpeak，AGPL-3.0-only）的 Vue 模板与样式，仅作为视觉规格，
不参与安卓构建。

## 已知限制

- 真机验收尚未完成（当前测试设备不可用）；模拟器无法加载 arm64-only JNI 库。
- 受密码保护的频道暂未弹出密码输入框。
- 通知权限被拒绝时仍可连接，但后台语音不会出现在通知抽屉中。
