# Signaling server

服务端实现位于当前目录下。

- 负责房间和 peer 注册
- 处理 announce / watch / offer / answer / candidate
- 提供同协议的 WebSocket 连接入口

## 启动方式

```bash
cd server/signaling
npm install
npm start
```

默认监听 `ws://127.0.0.1:8765`。在局域网联调时，请将地址改为电脑的 LAN IP，例如 `http://192.168.1.10:8765`。

更多说明见 [../docs/USAGE.md](../docs/USAGE.md)
