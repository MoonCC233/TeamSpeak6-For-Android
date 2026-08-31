# TeamSpeak6-For-Android

TeamSpeak 6 风格的 Android 语音协作客户端。使用 Kotlin + Jetpack Compose 构建，目标是在移动端提供接近桌面端的使用体验，包括频道树、文字聊天、语音通话、以及屏幕共享的观看与发起。

## 项目状态

当前处于分阶段开发中。已完成：

- [x] 阶段 1：Gradle / Compose 工程骨架、主题、CI
- [x] 阶段 2：领域模型与 WebQuery 管控客户端
- [x] 阶段 3：完整 Compose UI（频道树、聊天、用户信息、设置）
- [ ] 阶段 4：WebRTC 语音与屏幕共享
- [ ] 阶段 5：前台服务、通知、重连与打磨

### 已实现界面

- 书签列表与编辑（含桥地址、API Key、自动连接）
- 频道树：折叠、Spacer 渲染、人数与密码标记、当前频道高亮
- 用户行：说话 / 静音 / 离开 / 指挥 / 屏幕共享状态图标
- 长按上下文菜单：加入频道、移动用户、踢出、封禁、poke、服务器组、频道增删改
- 聊天：服务器 / 频道 / 私聊多会话标签、未读角标、发送状态
- 底部语音控制条：麦克风、扬声器、按键说话、屏幕共享、频道指挥
- 设置：昵称、语音处理、屏幕共享码率帧率、通知、轮询间隔

## 架构说明

TeamSpeak 6 的语音与屏幕共享传输协议未公开文档化，第三方客户端无法直接复刻。本项目采用混合架构：

| 能力 | 实现方式 |
| --- | --- |
| 服务器 / 频道 / 用户 / 权限 / 文字聊天 | TeamSpeak 官方 WebQuery (HTTP) 接口 |
| 语音收发、屏幕共享收发 | 自建 WebRTC 桥接服务（信令 + SFU） |

即：管控面走官方接口，媒体面走自建桥。连接官方服务器时可获得完整的管控与聊天体验；语音与屏幕共享需要配套的桥接服务端。

## 技术栈

- Kotlin 2.0，Jetpack Compose（Material 3）
- Hilt 依赖注入
- Retrofit + OkHttp + kotlinx.serialization
- Room + DataStore 本地持久化
- WebRTC (io.github.webrtc-sdk)

## 构建

要求 JDK 17+ 与 Android SDK（compileSdk 35）。

```bash
./gradlew :app:assembleDebug
```

在 `local.properties` 中指定 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

## 许可

本项目与 TeamSpeak Systems GmbH 无隶属关系。TeamSpeak 为其所有者的商标。
