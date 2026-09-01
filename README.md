# TeamSpeak9

TeamSpeak9 是一个完全独立实现的 TeamSpeak 风格客户端方案，不依赖 TeamSpeak 官方桌面端或官方 Android 客户端。项目基于 Kotlin + Jetpack Compose，目标是在移动端和桌面端实现接近官方 UI 的体验：频道树、文字聊天、原生协议语音，以及屏幕共享的观看与发起。

## 目标

- 手机端、桌面端、服务端三端都完全独立实现
- 语音走自研/兼容协议栈，服务端按官方 TeamSpeak 服务端做适配扩展
- 屏幕共享继续使用当前已完成的 MSS / WebRTC 协议
- UI 可仿照官方 TeamSpeak 风格，但不依赖官方应用或官方私有协议

## 使用文档

- [docs/USAGE.md](docs/USAGE.md)：快速上手、运行方式、局域网联调和常见问题
- [docs/screenshare-protocol.md](docs/screenshare-protocol.md)：屏幕共享协议说明
- [docs/TEAMSPEAK9-ROADMAP.md](docs/TEAMSPEAK9-ROADMAP.md)：TeamSpeak9 重构路线图和三端适配计划

## 项目状态

- [x] 阶段 1：Gradle / Compose 工程骨架、主题、CI
- [x] 阶段 2：领域模型与本地持久化
- [x] 阶段 3：完整 Compose UI（频道树、聊天、用户信息、设置）
- [x] 阶段 4：原生 TeamSpeak 协议语音（连接、频道、聊天、Opus 收发）
- [x] 阶段 5：屏幕共享（自建协议、WebRTC 收发、MediaProjection 采集）
- [x] 阶段 6：前台服务、通知、自动重连与打磨
- [x] 阶段 7：最小 MSS 信令服务端（WebSocket room 管理、peer 路由、announce/watch/offer/answer/candidate 转发）

### 已实现界面

- 书签列表与编辑（地址、端口、昵称、服务器 / 频道密码、自动连接）
- 频道树：折叠、Spacer 渲染、人数与密码标记、当前频道高亮
- 用户行：说话 / 静音 / 离开 / 指挥 / 屏幕共享状态图标
- 长按上下文菜单：加入频道、移动用户、踢出、封禁、poke、服务器组、频道增删改
- 聊天：服务器 / 频道 / 私聊多会话标签、未读角标、发送状态
- 底部语音控制条：麦克风、扬声器、按键说话、屏幕共享、频道指挥
- 屏幕标签页：信令连接状态、频道内共享列表、远端画面渲染、观众请求审批、共享选项（连接模式 / 分辨率 / 帧率 / 码率 / 隐私 / 音频 / 观众上限）
- 设置：昵称、语音处理、信令服务地址、屏幕共享码率帧率、通知

## 架构说明

语音走 **TeamSpeak 原生 UDP 协议**，与桌面客户端完全一致 —— 只填服务器地址即可加入语音，无需服务端插件、WebQuery 凭据或桥接服务。协议实现基于 [ts3j](https://github.com/Manevolent/ts3j)，Opus 编解码使用纯 Java 的 [Concentus](https://github.com/lostromb/Concentus)。

| 能力 | 实现方式 |
| --- | --- |
| 连接、频道树、用户列表、权限、文字聊天 | TeamSpeak 原生协议（ts3j）+ 服务器推送通知 |
| 语音收发 | 原生协议语音包 + Opus（Concentus）+ 抖动缓冲与混音 |
| 屏幕共享 | 自建 MSS 协议（WebSocket 信令）+ WebRTC，支持 P2P 与服务器中转 |
| 连接保活 | WebSocket `ping` / `pong` 心跳，防止长时间空闲后 NAT 或代理静默断开信令连接 |

服务器状态由服务器主动推送，不做轮询。身份（identity）在首次启动时本地生成并持久化，与桌面客户端的身份机制一致。

### 屏幕共享

协议规范见 [docs/screenshare-protocol.md](docs/screenshare-protocol.md)，它同时是 PC 端伴生程序与信令服务的实现依据。要点：

- 信令走自建 WebSocket 服务，地址在设置中填写；留空则屏幕共享不可用。
- 房间 id 由 TeamSpeak 位置派生（`sha256("$serverUid|$channelId")` 取前 32 位），所以「同频道的人能互相看到共享」自动成立，换频道会自动重新入房。
- 两种连接模式：`P2P`（每位观众一条 PeerConnection）与服务器中转（上传一路给 SFU）。中转模式需要信令服务在 `welcome` 中声明 `sfuAvailable`，否则自动回退 P2P。
- 视频优先 H.264 Constrained Baseline，VP8 回退；码率同时通过 `RtpSender.setParameters` 与 SDP `b=AS:` 控制。

**互通限制**：TeamSpeak 6 的屏幕共享信令层没有任何公开文档（媒体层是 WebRTC，已由官方员工确认，但 SDP/ICE 如何经由 TeamSpeak 服务器交换未公开），因此**无法与官方客户端互看屏幕**。本项目的屏幕共享仅能与实现了 MSS 协议的客户端互通。语音不受此限制，与官方客户端完全互通。

### 项目目录结构

现在项目已按平台拆分为三大部分，并且按 TeamSpeak9 的产品方向来组织：

```text
TeamSpeak9/
├─ mobile/
│  ├─ README.md
│  └─ app/                 # Android 客户端：Compose UI + TeamSpeak 原生协议 + 屏幕共享
├─ desktop/
│  ├─ README.md
│  └─ companion/           # PC 端伴生程序：WebRTC + browser UI + CLI 验证工具
├─ server/
│  ├─ README.md
│  └─ signaling/           # MSS 信令服务：房间、announce/watch、offer/answer/candidate
├─ docs/
├─ gradle/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ README.md
└─ ...
```

其中：

- `mobile/app`：Android 端实现；语音走 TeamSpeak 原生协议，屏幕共享走 MSS/WebRTC
- `desktop/companion`：桌面端参考实现，可作为同协议观众/共享端参与互通
- `server/signaling`：后端信令服务，负责 room、peer、share/watch、offer/answer/candidate 转发

### 最小 MSS 信令服务端

当前已补齐最小可运行的 WebSocket 信令服务端，位于 `server/signaling/`。它实现了：

- room 管理：按 TeamSpeak 频道位置派生 `roomId`，同频道用户自动落在同一 room
- peer 注册：客户端 `hello` 后返回 `welcome`，并返回该 room 的现有 peers / shares
- share 状态广播：`announce` / `unannounce` 会广播 share-started / share-stopped
- watch / offer / answer / candidate 转发：支持 P2P 基础信令交换
- 心跳保活：每 25s 发送一次 `ping`，客户端通过 `pong` 回应，减少 NAT/代理导致的静默断线
- 连接生命周期：`peer-left` 与 `bye`/`error` 会在断开时清理状态

启动方式：

```bash
cd server/signaling
npm install
npm start
```

默认监听 `ws://127.0.0.1:8765`。目前服务端优先落地 P2P 最小可用链路，SFU 中转仍为后续扩展点。

同时补了一份 PC 端参考伴生程序，位于 `desktop/companion/`，它同时包含：

- 一个基于浏览器的桌面端界面，用于连接信令服务、开始/停止屏幕共享，并渲染远端共享画面
- 一个 CLI 版信令脚本，用于在无界面环境中验证同 room 的 `watch / offer / answer / candidate` 流程

```bash
cd desktop/companion
npm install
npm start     # 启动本地 UI + WebSocket 信令服务： http://127.0.0.1:4173
npm run start:cli -- --room room-123 --uid pc-a --name DeskA --publish
npm run start:cli -- --uid pc-b --name DeskB --watch p_xxx
npm test      # 端到端验证同 room 的 announce/watch/offer/candidate 流程
```

浏览器版会调用 `getDisplayMedia()` 与 `RTCPeerConnection` 实现真实的屏幕采集和远端渲染；CLI 模式则保留用于无界面验证和自动化测试。为了跨设备互通，Android 端和 PC 伴生端都需要连接到同一个信令服务并使用同一个 `roomId`（同一个 TeamSpeak 服务器 UID + 频道 ID 派生出的 room id）。

### 真实互通验证步骤

1. 启动信令服务：`cd server/signaling && npm install && npm start`
2. 启动 PC 伴生端：`cd desktop/companion && npm start`
3. 在 Android 中打开同一 TeamSpeak 服务器与频道，填写同样的信令服务地址，并确保两端都进入相同 `roomId`
4. 让 Android 或 PC 任一端点击“开始共享”并 `announce`
5. 另一端使用“观看”按钮或 `watch` 请求对方的 `publisherId`
6. 观察 `offer / answer / candidate` 交换，并确认远端视频流出现

#### 实时调试检查清单

- 房间派生必须一致：`RoomId.forChannel(serverUid, channelId)` 与 `deriveRoomId(serverUid, channelId)` 必须相同
- 信令地址可接受 `ws://` / `wss://`，也可接受 `http://` / `https://` 自动转换
- 发布端必须先 `announce`，观看端再 `watch`
- 观看端收到 `watch-request` 后必须发出 `offer`
- 发布端收到 `offer` 后发 `answer`
- 双端继续交换 `candidate`，直到 `connected` 或 `ontrack` 触发
- 也支持直接发送 `serverUid + channelId` 让服务端自动派生 `roomId`，用于与 Android 端完全一致的 TeamSpeak 频道归并

#### 局域网真实设备联调命令

在同一 Wi‑Fi / 局域网下，先在桌面端启动信令服务，然后用以下命令检查设备是否能在同房间互相看到：

```bash
cd server/signaling
npm install
npm start
```

另开一个终端：

```bash
cd desktop/companion
npm install
node lan-check.js --server http://192.168.1.10:8765 --server-uid <serverUid> --channel-id <channelId> --uid laptop-a --name LaptopA --publish
node lan-check.js --server http://192.168.1.10:8765 --server-uid <serverUid> --channel-id <channelId> --uid laptop-b --name LaptopB --watch p_xxx
```

也可以手动传 `--room`，但同一 TeamSpeak 服务器 + 频道下更推荐直接使用 `--server-uid` + `--channel-id` 来自动计算 `roomId`，这样和 Android 侧 `RoomId.forChannel(serverUid, channelId)` 完全一致。

在 Android 侧：

- 打开同一 TeamSpeak 服务器 + 频道
- 设置屏幕共享信令地址为 `http://192.168.1.10:8765`
- 进入同一 `roomId`
- 触发开始共享 / 观看

如果两端都能看到 `welcome`、`share-started`、`watch-request`、`offer`、`answer`、`candidate`，则说明同局域网 P2P 互通链路已成立。

同一 MSS 协议下，屏幕共享只要求双方都实现相同的 `watch / offer / answer / candidate` 交换，不依赖官方 TeamSpeak 桌面端的私有信令协议。

## 技术栈

- Kotlin 2.0，Jetpack Compose（Material 3）
- Hilt 依赖注入
- ts3j（TeamSpeak 原生协议）+ Concentus（Opus）
- Room + DataStore 本地持久化
- WebRTC (io.github.webrtc-sdk)，用于屏幕共享

## 构建

要求 JDK 17+ 与 Android SDK（compileSdk 35）。

```bash
./gradlew :app:assembleDebug
```

在 `local.properties` 中指定 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

## 风险说明

本项目与 TeamSpeak Systems GmbH 无隶属关系，TeamSpeak 为其所有者的商标。TeamSpeak 的服务条款（GTC §7.3 / §7.4 / §13.3）禁止对其客户端与协议进行逆向工程；本项目基于第三方开源协议实现，仅供学习与个人使用，不建议上架应用商店，使用者需自行评估合规风险。
