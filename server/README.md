# Signaling server

服务端实现位于当前目录下，是 **MSS 屏幕共享信令协议的唯一实现**。

- 负责房间和 peer 注册
- 处理 announce / watch / offer / answer / candidate
- 提供同协议的 WebSocket 连接入口
- 服务端强制观众上限与白名单，不再依赖客户端自律
- 心跳驱逐掉线 peer，避免僵尸 peer 长期占用观众名额

桌面端 (`desktop/companion`) 通过 `require('../../server/signaling')` 复用同一份实现，
两端不会出现协议漂移。请不要在桌面端另写一份。

## 启动方式

```bash
cd server/signaling
npm install
npm start
```

默认监听 `ws://127.0.0.1:8765`。在局域网联调时，请将地址改为电脑的 LAN IP，例如 `http://192.168.1.10:8765`。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8765` | 独立进程模式下的监听端口 |
| `MSS_AUTH_TOKEN` | 空 | 房间准入令牌。设置后，客户端必须在 `hello` 的 `token` 字段或连接 URL 的 `?token=` 中提供相同值，否则收到 `unauthorized` 并被断开。**留空表示任何能连到该端口的人都能加入任意房间**，仅适合可信局域网。 |
| `MSS_ICE_SERVERS` | 空 | 逗号分隔的 ICE 服务器列表，会随 `welcome` 下发给客户端。例如 `stun:stun.l.google.com:19302,turn:turn.example.com:3478`。跨网络（非同一局域网）互通需要至少一个 TURN。 |
| `MSS_HEARTBEAT_MS` | `25000` | 心跳间隔。连续两个周期没有任何消息的 peer 会被驱逐。 |

令牌比较使用 `crypto.timingSafeEqual`，长度不同直接判否。

## 测试

```bash
cd server/signaling
npm test
```

覆盖 roomId 派生、hello 的两种寻址形式、announce/watch/offer 路由、心跳、离开广播、
重复 announce 覆盖、观众上限、白名单、未共享拒绝以及 `MSS_AUTH_TOKEN` 准入。

更多说明见 [../docs/USAGE.md](../docs/USAGE.md)
