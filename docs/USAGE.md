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

## 4. 启动桌面端伴生程序

```bash
cd desktop/companion
npm install
npm start
```

启动后，浏览器页面会提供：

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
   - `http://192.168.1.10:8765`
   - 或 `ws://127.0.0.1:8765`（仅本机调试）
4. Android 会根据当前 TeamSpeak 的 `serverUid + channelId` 自动计算同一 roomId
5. 选择“开始共享”或“观看共享”

如果两端都在同一 room 中，信令层会自动发现共享发布者和观众请求。

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

### Q: 端口或地址无法访问？

检查：

- 信令服务是否已启动
- 地址是否写成 `http://` / `ws://` 或 `https://` / `wss://`
- 电脑防火墙是否放行
- 手机和电脑是否在同一局域网

## 10. 相关文档

- [docs/screenshare-protocol.md](./screenshare-protocol.md)：屏幕共享协议定义
- [README.md](../README.md)：总览与项目结构说明
- [mobile/README.md](../mobile/README.md)：Android 端说明
- [desktop/README.md](../desktop/README.md)：桌面端说明
- [server/README.md](../server/README.md)：信令服务说明

## 11. 结论

当前项目可分成三部分：

- 语音：官方 TeamSpeak 原生协议
- 屏幕共享：自建 MSS/WebRTC 同协议链路
- 后端：WebSocket 房间信令服务器

只要 Android、桌面端和信令服务都使用同一套房间规则和同一套协议，就能在同一 TeamSpeak 频道下完成屏幕共享的公会式互通。
