# 屏幕共享协议（MSS / MoonShare Signaling）

本文档定义本项目自建的屏幕共享信令协议。**这不是 TeamSpeak 6 官方的屏幕共享协议**，两者互不相通：官方桌面客户端看不到本协议发起的共享，本协议也看不到官方发起的共享。语音走 TeamSpeak 原生协议，与官方完全互通，不受本文档影响。

任何按本文档实现的端（Android、Windows 伴生程序、浏览器）之间都可以互看屏幕。

## 1. 总览

| 层 | 技术 |
| --- | --- |
| 信令 | WebSocket（`ws://` / `wss://`），JSON 文本帧 |
| 媒体 | WebRTC，视频 H.264 / VP8，可选音频 Opus |
| NAT 穿透 | ICE，STUN / TURN 由信令服务在 `welcome` 中下发 |

两种连接模式，由发起者在 `announce` 时声明：

- **`p2p`** — 发起者为每个观看者单独建一条 PeerConnection。上行带宽 = 单流码率 × 观看人数，且发起者 IP 会暴露给观看者。信令服务只转发消息，不碰媒体。
- **`sfu`** — 发起者只上传一路流给中转服务，服务再分发给所有观看者。上行恒定，IP 不暴露给观看者。需要信令服务同时具备 SFU 能力。

TeamSpeak 6 官方服务端在撰写本文时**尚未开放服务器端屏幕共享**（官方员工确认 SFU 仍在内部测试），所以 `sfu` 模式使用本项目自己的中转服务。

## 2. 房间与身份

房间隔离基于 TeamSpeak 的位置，让"同一个频道里的人能互相看到共享"这件事自动成立：

```
roomId = sha256("<serverUid>|<channelId>")  取前 32 个 hex 字符
```

- `serverUid` — TeamSpeak 虚拟服务器的唯一标识（`virtualserver_unique_identifier`）
- `channelId` — 当前频道 id

同一 TeamSpeak 频道内的成员算出同一个 `roomId`。换频道要先 `leave` 再 `hello` 进新房间。

每个连接的身份由客户端在 `hello` 中自报：

- `clientUid` — TeamSpeak 身份公钥指纹（Base64），跨会话稳定
- `tsClientId` — 当前会话的 TeamSpeak clid，短期有效
- `nickname` — 显示名

信令服务分配一个 `peerId`（本次 WebSocket 连接内唯一）并在 `welcome` 中返回。后续所有路由都用 `peerId`。

> **信令服务不验证身份。** `clientUid` 可以伪造，只用于展示和去重。若需要真正的准入控制，应在信令服务上接入 TeamSpeak 服务端校验，本文档不涉及。

## 3. 消息封装

所有消息是一个 JSON 对象，`type` 为判别字段：

```json
{ "type": "hello", "v": 1, ... }
```

- `v` — 协议版本，当前为 `1`。服务端收到不认识的版本应回 `error` 并关闭连接。
- 需要定向路由的消息带 `to`（目标 `peerId`）；服务端转发时填入 `from`（来源 `peerId`）。
- 未知 `type` 必须忽略而不是断开，便于后续向前兼容地加消息。

## 4. 客户端 → 服务端

### `hello` — 加入房间

```json
{
  "type": "hello",
  "v": 1,
  "roomId": "3f2a...",
  "clientUid": "yPHNqxr...",
  "tsClientId": 42,
  "nickname": "MoonCC233",
  "token": "shared-secret",
  "capabilities": { "canPublish": true, "canSubscribe": true, "codecs": ["H264", "VP8"] }
}
```

必须是连接后的第一条消息。服务端回 `welcome`。

`token` 仅在服务端配置了 `MSS_AUTH_TOKEN` 时必填，也可以改为放在连接 URL 的查询串里
（`ws://host:8765/?token=shared-secret`），两者等价，`token` 字段优先。校验失败返回
`unauthorized` 且 `fatal` 为 `true`。服务端未配置令牌时两者都被忽略。

一个 socket 只能 `hello` 一次；重复发送返回 `bad_request` 并断开。断线重连必须建新连接，
并且会拿到一个新的 `peerId`（见 §2）。

### `announce` — 开始共享

```json
{
  "type": "announce",
  "v": 1,
  "mode": "p2p",
  "privacy": "public",
  "hasAudio": true,
  "video": { "width": 1920, "height": 1080, "fps": 30, "bitrateKbps": 8000 },
  "audio": { "bitrateKbps": 128 },
  "viewerLimit": 0
}
```

- `mode` — `p2p` 或 `sfu`
- `privacy` — `public`（房间内所有人可看）/ `contacts`（仅 `allowedUids` 内可看）/ `private`（仅逐个批准）
- `allowedUids` — 可选，`privacy` 为 `contacts` 时生效
- `viewerLimit` — `0` 表示不限
- `video.width/height` 为期望上限，实际以 SDP 协商结果为准

服务端向房间内其他人广播 `share-started`。

重复 `announce` 是合法的：整条共享描述被新值替换，房间内其他人收到一条新的
`share-started`，之后加入的人在 `welcome.shares` 里看到的也是新值。切换清晰度或
开关共享音频时用它，不需要先 `unannounce`。

`viewerLimit` 与 `allowedUids` 由服务端强制执行（见 `watch`），不依赖客户端自律。
`privacy: private` 的最终裁决权仍在发起者手上——服务端照常转发 `watch-request`，
由发起者选择回 `offer` 还是 `bye`。

### `unannounce` — 停止共享

```json
{ "type": "unannounce", "v": 1 }
```

服务端广播 `share-stopped`，并让所有相关 PeerConnection 收到 `bye`。

### `watch` — 请求观看

```json
{ "type": "watch", "v": 1, "publisherId": "p_7c1e" }
```

- `p2p` 模式：服务端把 `watch-request` 转发给发起者，由发起者决定是否 `offer`
- `sfu` 模式：服务端直接回一个 `offer`

服务端在转发前会先校验，任一条不满足则回 `error` 且不通知发起者：

| 条件 | 失败时的 `code` |
| --- | --- |
| `publisherId` 在同一房间内存在 | `no_such_peer` |
| 该 peer 正在共享 | `not_sharing` |
| 未超过 `viewerLimit` | `viewer_limit` |
| `allowedUids` 为空或包含请求方的 `clientUid` | `not_allowed` |

通过校验后请求方即占用一个观众名额，直到它 `unwatch`、发起者 `unannounce`、
任一方 `bye`，或它离开房间/被心跳驱逐。已在观看的人重复 `watch` 不会重复占名额。

### `unwatch` — 停止观看

```json
{ "type": "unwatch", "v": 1, "publisherId": "p_7c1e" }
```

### `offer` / `answer` — SDP 交换

```json
{ "type": "offer", "v": 1, "to": "p_9a3f", "sdp": "v=0\r\no=- ...", "streamId": "screen" }
```

```json
{ "type": "answer", "v": 1, "to": "p_7c1e", "sdp": "v=0\r\no=- ..." }
```

`sfu` 模式下发起者的 `to` 填 `"sfu"`。

### `candidate` — ICE 候选

```json
{
  "type": "candidate",
  "v": 1,
  "to": "p_9a3f",
  "candidate": "candidate:1 1 UDP 2130706431 192.168.1.9 51234 typ host",
  "sdpMid": "0",
  "sdpMLineIndex": 0
}
```

`candidate` 为空字符串表示候选收集结束。

### `bye` — 关闭某条连接

```json
{ "type": "bye", "v": 1, "to": "p_9a3f", "reason": "publisher stopped" }
```

### `leave` — 离开房间

```json
{ "type": "leave", "v": 1 }
```

### `ping`

```json
{ "type": "ping", "v": 1, "nonce": 8817 }
```

客户端每 20 秒发一次。服务端回 `pong` 并带回同一个 `nonce`。

服务端同样会主动发 `ping`（间隔见 `welcome.heartbeatMs`，默认 25 秒）。**任何**消息都算活跃，
所以专门回 `pong` 不是必需的，但连续两个心跳周期内一条消息都没发过的连接会被断开，
其占用的观众名额随之释放。挂起到后台的手机端应保证仍能按周期发 `ping`。

## 5. 服务端 → 客户端

### `welcome`

```json
{
  "type": "welcome",
  "v": 1,
  "peerId": "p_9a3f",
  "roomId": "3f2a...",
  "heartbeatMs": 25000,
  "sfuAvailable": true,
  "iceServers": [
    { "urls": ["stun:stun.example.com:3478"] },
    { "urls": ["turn:turn.example.com:3478?transport=udp"], "username": "u", "credential": "p" }
  ],
  "peers": [ { "peerId": "p_7c1e", "clientUid": "aBc...", "tsClientId": 17, "nickname": "Alice" } ],
  "shares": [ { "publisherId": "p_7c1e", "nickname": "Alice", "mode": "p2p", "hasAudio": true,
                "video": { "width": 1920, "height": 1080, "fps": 30, "bitrateKbps": 8000 } } ]
}
```

`sfuAvailable` 为 `false` 时客户端不应使用 `sfu` 模式，应回退到 `p2p` 并提示用户。

`welcome` **只在 `hello` 之后发送一次**，是一份全量快照。服务端不会重放它——房间成员变化
全部走增量的 `peer-joined` / `peer-left` / `share-started` / `share-stopped`。客户端因此可以
放心地在收到 `welcome` 时重置本地房间状态。

### `peer-joined` / `peer-left`

```json
{ "type": "peer-joined", "v": 1, "peer": { "peerId": "p_1b2c", "clientUid": "dEf...", "tsClientId": 23, "nickname": "Bob" } }
```

```json
{ "type": "peer-left", "v": 1, "peerId": "p_1b2c" }
```

### `share-started` / `share-stopped`

```json
{
  "type": "share-started",
  "v": 1,
  "share": { "publisherId": "p_7c1e", "nickname": "Alice", "mode": "p2p", "hasAudio": true,
             "video": { "width": 1920, "height": 1080, "fps": 30, "bitrateKbps": 8000 } }
}
```

```json
{ "type": "share-stopped", "v": 1, "publisherId": "p_7c1e" }
```

### `watch-request` — 仅 `p2p`

```json
{ "type": "watch-request", "v": 1, "from": "p_9a3f", "nickname": "MoonCC233", "clientUid": "yPH..." }
```

发起者据此建 PeerConnection 并回 `offer`。`privacy` 为 `private` 时应先询问用户。

### `offer` / `answer` / `candidate` / `bye`

与客户端→服务端同形，`to` 换成 `from`：

```json
{ "type": "offer", "v": 1, "from": "p_7c1e", "sdp": "v=0\r\no=- ...", "streamId": "screen" }
```

### `error`

```json
{ "type": "error", "v": 1, "code": "room_full", "message": "viewer limit reached", "fatal": false }
```

`fatal` 为 `true` 时服务端随后会关闭连接。已定义的 `code`：

| code | 含义 |
| --- | --- |
| `bad_version` | `v` 不受支持（fatal） |
| `bad_request` | 消息格式非法，或在同一 socket 上重复 `hello` |
| `unauthorized` | 服务端配置了 `MSS_AUTH_TOKEN` 但令牌缺失或不匹配（fatal） |
| `not_in_room` | 未 `hello` 就发了其他消息（fatal） |
| `no_such_peer` | `to` / `publisherId` 不存在 |
| `not_sharing` | `watch` 的目标当前没有在共享 |
| `viewer_limit` | 超过 `viewerLimit` |
| `not_allowed` | `privacy` 拒绝，即请求方的 `clientUid` 不在 `allowedUids` 内 |
| `sfu_unavailable` | 请求了 `sfu` 但服务端不支持 |
| `already_publishing` | 该 peer 已在共享 |

### `pong`

```json
{ "type": "pong", "v": 1, "nonce": 8817 }
```

## 6. 时序

### P2P：观看者 V 观看发起者 P

```
V → S   watch { publisherId: P }
S → P   watch-request { from: V }
P → S   offer { to: V, sdp }
S → V   offer { from: P, sdp }
V → S   answer { to: P, sdp }
S → P   answer { from: V, sdp }
P ↔ S ↔ V   candidate（双向，直到收集结束）
P ↔ V   媒体直连
```

### SFU：发起者 P 上传，观看者 V 拉流

```
P → S   announce { mode: "sfu" }
P → S   offer { to: "sfu", sdp }
S → P   answer { sdp }
P → S   candidate { to: "sfu" } …
        （P 的流已到达服务端）

V → S   watch { publisherId: P }
S → V   offer { from: "sfu", sdp }
V → S   answer { to: "sfu", sdp }
S ↔ V   candidate …
```

## 7. 媒体约定

为保证跨端互通，实现方必须满足：

- 视频编码优先 **H.264 Constrained Baseline**（`profile-level-id=42e01f`），VP8 作为回退。Android 端硬件编码器对 H.264 支持最好；桌面端两者都可用。
- 视频轨的 `streamId` 固定为 `"screen"`，音频轨（若有）与视频轨同 stream。
- 码率通过 `RtpSender.setParameters` 的 `maxBitrateBps` 控制，同时在 SDP 上加 `b=AS:` 行兜底老实现。
- 帧率通过采集端限制，不依赖 SDP。
- 观看端应容忍分辨率中途变化（桌面端切换显示器、手机旋转）。
- 音频为可选。`hasAudio` 为 `false` 时 SDP 中不应出现音频 m-line。

## 8. 实现现状

| 端 | 状态 |
| --- | --- |
| Android 观看 | 已实现 |
| Android 发起（`MediaProjection`） | 已实现 |
| Android 服务器中转模式 | 客户端已实现，等待服务端 |
| 信令服务 | 已实现，见 [`server/signaling`](../server/signaling) |
| SFU 中转 | 待实现（`welcome.sfuAvailable` 恒为 `false`） |
| 桌面伴生程序 | 已实现，见 [`desktop/companion`](../desktop/companion) |

信令服务只有 [`server/signaling/index.js`](../server/signaling/index.js) 一份实现。桌面伴生程序
通过 `attachSignaling()` 复用它，把 UI 静态文件和信令挂在同一个端口上，这样局域网联调只需要
一个进程。**不要为某一端另写一份协议实现**——历史上两份实现漂移过，正是之前互通失败的原因。

自己实现服务端的话，最小集是：维护 `roomId → peers` 映射、按 `to` 转发、广播
`share-started` / `share-stopped` / `peer-joined` / `peer-left`。不涉及媒体，任何语言都能在几百行内写完。
`sfu` 模式额外需要一个 WebRTC 服务端实现（如 mediasoup、Pion、LiveKit）。
