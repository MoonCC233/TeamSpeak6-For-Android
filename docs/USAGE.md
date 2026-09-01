# 使用说明

本文档用于指导你如何在当前项目中启动、连接与使用 TeamSpeak9 风格的 Android 客户端，以及它的桌面伴生程序和信令服务。

## 1. 项目结构速览

- `mobile/app`：Android 客户端
- `desktop/companion`：桌面端伴生程序
- `server/signaling`：WebSocket 信令服务
- `docs/screenshare-protocol.md`：MSS 屏幕共享协议说明

注意：本项目的屏幕共享不是官方 TeamSpeak 桌面端协议，而是同协议互通方案。也就是说：

- 语音仍然走 TeamSpeak 原生协议，和官方客户端兼容
- 屏幕共享走自建 MSS/WebRTC 协议，必须和本项目实现的同协议端互通

## 2. 先决条件

- 一台能运行 Android App 的设备或模拟器
- 一台桌面端（Windows / macOS / Linux）
- 同一局域网或可互连的网络环境
- 同一个 TeamSpeak 服务器，并进入同一个频道
- 一个可访问的 WebSocket 信令地址

建议：在真实联调时，让 Android 与桌面端处于同一 Wi‑Fi / 局域网内，这样 P2P WebRTC 更容易成功。

## 3. 启动信令服务

在项目根目录执行：

```bash
cd server/signaling
npm install
npm start
```

默认监听：

```text
ws://127.0.0.1:8765
```

如果你要在局域网中让手机和电脑互连，需要把信令地址换成电脑的局域网 IP，例如：

```text
http://192.168.1.10:8765
```

Android 端和桌面端都要使用同一个信令服务地址。

可选环境变量（完整列表见 [../server/README.md](../server/README.md)）：

```bash
# 换端口
PORT=9000 npm start

# 开启房间准入令牌：客户端必须提供同样的值，否则被拒绝并断开
MSS_AUTH_TOKEN=my-shared-secret npm start

# 跨网络（非同一局域网）互通时下发 STUN/TURN
MSS_ICE_SERVERS=stun:stun.l.google.com:19302,turn:turn.example.com:3478 npm start
```

同一局域网内不配置 `MSS_ICE_SERVERS` 也能连通；跨运营商/跨 NAT 则至少需要一台 TURN。

跑一遍协议自测：

```bash
cd server/signaling
npm test
```

## 4. 启动桌面端伴生程序

桌面端有两种运行方式，界面和信令实现完全相同，按需选一种。

### 4.1 Electron 桌面应用（推荐）

```bash
cd desktop/companion
npm install
npm start
```

会打开一个原生窗口。外壳内部先在进程内起 `server.js`，再用 `http://127.0.0.1:4173` 加载界面，
所以外壳和浏览器模式共用同一个 origin 与同一份信令实现，不会出现行为差异。

外壳额外做了两件浏览器里不需要的事：

- 注册屏幕捕获选择器。Electron 在部分平台没有系统级 picker，不注册的话 `getDisplayMedia()`
  会直接失败。优先选整块屏幕，因为窗口列表取决于用户开了什么，屏幕则一定存在。
- 只在 Windows 上抓系统声音。Electron 的 loopback 采集只在 Windows 可用，其他平台请求它会让
  整个捕获请求失败，所以那里只发视频。

若 4173 已被占用，外壳会退到一个随机端口，控制台会提示，界面里的信令地址也会自动改成实际端口。

### 4.2 浏览器模式（不装 Electron）

```bash
cd desktop/companion
npm install --omit=dev
npm run serve
```

然后浏览器打开 `http://127.0.0.1:4173`。`--omit=dev` 会跳过 Electron 的下载（约 100MB）。
屏幕共享要靠浏览器自己的 `getDisplayMedia()`，共享时会弹出浏览器的选择框。

### 4.3 两种方式共通的说明

桌面端**不需要**单独启动信令服务：它复用 `server/signaling` 的同一份实现，把 UI 和信令
挂在同一个端口（默认 4173）上。也就是说局域网联调只跑这一个进程就够了，把手机的信令地址
填成 `http://<电脑IP>:4173` 即可。只有当你想让信令独立部署时，才需要按上一节单独启动 8765。

界面提供：

- 连接信令服务
- 开始共享
- 停止共享
- 观看远端共享
- 查看 WebRTC 状态

也可以没有 UI 直接做 CLI 测试：

```bash
cd desktop/companion
npm run start:cli -- --room room-123 --uid pc-a --name DeskA --publish
npm run start:cli -- --uid pc-b --name DeskB --watch p_xxx
```

## 5. 连接 TeamSpeak 并进入同一房间

屏幕共享房间 ID 不是随便填的，而是根据 TeamSpeak 的位置派生：

```text
roomId = sha256("<serverUid>|<channelId>") 前 32 位 hex
```

也就是说：

- 同一 TeamSpeak 服务器 + 同一频道
- 计算出来的 roomId 相同
- 共享与观看能自动匹配到同一房间

如果你知道 `serverUid` 与 `channelId`，也可以手动指定：

```bash
cd desktop/companion
node lan-check.js --server http://192.168.1.10:8765 --server-uid <serverUid> --channel-id <channelId> --uid laptop-a --name LaptopA --publish
node lan-check.js --server http://192.168.1.10:8765 --server-uid <serverUid> --channel-id <channelId> --uid laptop-b --name LaptopB --watch p_xxx
```

## 6. Android 端如何使用

在 Android 端：

1. 打开 TeamSpeak 服务器并进入目标频道
2. 打开屏幕共享相关页面
3. 填写信令地址，例如：
   - `http://192.168.1.10:4173`（桌面伴生程序自带信令，推荐）
   - `http://192.168.1.10:8765`（独立部署的信令服务）
   - 或 `ws://127.0.0.1:8765`（仅本机调试）
4. Android 会根据当前 TeamSpeak 的 `serverUid + channelId` 自动计算同一 roomId
5. 选择“开始共享”或“观看共享”

如果两端都在同一 room 中，信令层会自动发现共享发布者和观众请求。

服务端若配置了 `MSS_AUTH_TOKEN`，把令牌拼在地址后面即可：`http://192.168.1.10:4173/?token=my-shared-secret`。

## 7. 屏幕共享的完整流程

推荐顺序：

1. 启动信令服务
2. 启动桌面伴生程序
3. Android 端或桌面端先发起共享
4. 另一端发起观看请求
5. 发起端收到 `watch-request` 后发送 `offer`
6. 观看端返回 `answer`
7. 双方交换 `candidate`
8. WebRTC 建立连接后，远端画面显示出来

关键检查项：

- `hello` 后必须收到 `welcome`
- 共享端必须先 `announce`
- 观看端必须后 `watch`
- `offer` / `answer` / `candidate` 需要在同一 room 中交换
- 若一端没看到远端画面，优先检查网络、roomId、信令地址和是否同频道

## 8. 真实联调建议

在真实设备上建议按下面流程：

1. 让 Android 手机和电脑连接同一 Wi‑Fi
2. 在电脑上启动信令服务
3. 在 Android 中填入电脑本机 IP 的 WebSocket 地址
4. 让手机与电脑都进入同一个 TeamSpeak 服务器和频道
5. 一端开始共享，另一端观看
6. 如出现 P2P 连接失败，先检查 NAT / 防火墙 / 同网段情况

如果后续需要更稳的跨网络连接，可以再扩展为 SFU / 中继模式，而不是仅保留 P2P。

## 8.5 语音功能一览

语音与屏幕共享是两条独立链路：**语音不需要信令服务**，填完服务器地址就能加入。官方客户端的
语音能力全部已实现，操作入口如下：

| 功能 | 入口 |
| --- | --- |
| 麦克风静音 / 扬声器静音 | 底部控制条 |
| 按键说话 / 声音激活（含阈值） | 底部控制条切换，阈值在设置里 |
| 回声消除、噪声抑制、自动增益 | 设置 → 语音处理 |
| 输出音量、麦克风增益 | 设置 |
| 离开状态与留言 | 顶部菜单 |
| 频道指挥、优先发言、申请发言 | 底部控制条 |
| 单独静音某人 / 调节某人音量 | 长按用户 → 音频 |
| 耳语（多频道 + 多用户） | 底部控制条「耳语」按钮 → 选「指定频道/用户」，再按住说话 |
| 指挥组耳语（按组寻址） | 底部控制条「耳语」按钮 → 选「按组耳语」，再按住说话 |

耳语在按住期间会绕过按键说话设置，只发给选定目标；松开自动收尾。目标里的频道 id 会在断线
重连后恢复，用户 id 不会——用户 id 是每次连接才分配的临时句柄，恢复它会指向错误的人。

「按组耳语」是官方客户端的另一种寻址方式，两种模式互斥，选其一即会清掉另一种。组模式先选
类型（服务器组 / 频道组 / 频道指挥 / 全部客户端），前两种还要再选具体的组；然后选生效范围
（当前频道、父频道、子频道、整个频道树等 7 种）。组 id 是服务端持久 id，断线重连后会恢复。

注意：服务端可能对耳语另设权限。若组耳语没人收到，先确认账号在服务端拥有对应的耳语权限。

## 9. 常见问题

### Q: 手机和电脑不在同一房间怎么办？

检查：

- 是否同一 TeamSpeak 服务器
- 是否同一频道
- 是否使用了相同的 `serverUid` / `channelId`
- 是否同一信令地址

### Q: 连接成功但看不到共享画面？

检查：

- 共享端是否先 `announce`
- 观看端是否发出 `watch`
- `offer` / `answer` 是否成功交换
- ICE candidate 是否完成

如果 `watch` 之后立刻收到错误提示，那是服务端主动拒绝了，按 `code` 对照：

| 提示中的 code | 原因 | 处理 |
| --- | --- | --- |
| `not_sharing` | 对方已经停止共享 | 等对方重新开始共享 |
| `viewer_limit` | 共享者设置了观众上限且已满 | 等有人退出，或让共享者调大上限 |
| `not_allowed` | 共享者把可见范围设成了「仅联系人」，你的 `clientUid` 不在名单里 | 让共享者把你加进允许列表，或改成公开 |
| `unauthorized` | 服务端配了 `MSS_AUTH_TOKEN`，客户端没提供或提供错了 | 核对令牌 |

`private`（逐个批准）模式下不会报错，而是等共享端弹窗确认，被拒绝时表现为收到 `bye`。

### Q: 挂后台一会儿就掉线？

服务端每 25 秒发一次心跳，连续两个周期收不到客户端任何消息就会断开连接并释放其观众名额。
被系统冻结的手机端会命中这个规则。回到前台后重连即可（会拿到一个新的 `peerId`，属正常行为）。
心跳间隔可用 `MSS_HEARTBEAT_MS` 调整。

### Q: 端口或地址无法访问？

检查：

- 信令服务是否已启动
- 地址是否写成 `http://` / `ws://` 或 `https://` / `wss://`
- 电脑防火墙是否放行
- 手机和电脑是否在同一局域网

### Q: 能和官方 TeamSpeak 客户端互看屏幕吗？

不能。语音走的是 TeamSpeak 原生协议，和官方客户端完全互通；屏幕共享走的是本项目自建的
MSS 协议，只能和同样实现了 MSS 的端互通（本项目的手机端与桌面端）。官方的屏幕共享信令层
没有公开文档，无法对接。

### Q: 跨网络（不同城市/不同运营商）能用吗？

语音可以，屏幕共享需要额外配置。P2P 在双方都处于对称 NAT 后面时会打洞失败，此时必须给
信令服务配置 TURN：`MSS_ICE_SERVERS=turn:your-turn:3478`。服务端会把它随 `welcome` 下发给
两端。SFU 中转模式客户端已支持，但服务端尚未实现，会自动回退到 P2P。

## 10. 相关文档

- [docs/screenshare-protocol.md](./screenshare-protocol.md)：屏幕共享协议定义
- [docs/TEAMSPEAK9-ROADMAP.md](./TEAMSPEAK9-ROADMAP.md)：三端重构路线图
- [README.md](../README.md)：总览、语音功能对照表与已知限制
- [mobile/README.md](../mobile/README.md)：Android 端说明与构建命令
- [desktop/README.md](../desktop/README.md)：桌面端说明
- [server/README.md](../server/README.md)：信令服务说明与环境变量

## 11. 结论

当前项目可分成三部分：

- 语音：TeamSpeak 原生协议，官方语音功能已全部实现（含耳语、优先发言、发言申请、单人静音/音量）
- 屏幕共享：自建 MSS/WebRTC 同协议链路
- 后端：WebSocket 房间信令服务器，三端共用同一份实现

只要 Android、桌面端和信令服务都使用同一套房间规则和同一套协议，就能在同一 TeamSpeak 频道下完成屏幕共享的公会式互通。
