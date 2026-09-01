# Desktop companion

桌面端实现位于当前目录下。

- 负责连接同一套 MSS 信令
- 参与共享者/观察者的 WebRTC 协商
- 以同协议兼容方式与 Android 端互通

`server.js` 只做两件事：托管 UI 静态文件，以及把
[`server/signaling`](../server/signaling) 的信令实现挂到同一个端口上。协议本身没有第二份实现，
所以桌面端和手机端不会出现行为漂移。要改协议请改 `server/signaling/index.js`。

## 使用指南

```bash
cd desktop/companion
npm install
npm start
```

默认监听 `http://127.0.0.1:4173`，UI 与信令共用该端口。手机端把信令地址填成
`http://<电脑局域网IP>:4173` 即可，不需要另开信令进程。用 `PORT` 换端口，
`MSS_AUTH_TOKEN` / `MSS_ICE_SERVERS` / `MSS_HEARTBEAT_MS` 与独立信令服务同义，
说明见 [../server/README.md](../server/README.md)。

或者使用 CLI：

```bash
cd desktop/companion
npm run start:cli -- --room room-123 --uid pc-a --name DeskA --publish
npm run start:cli -- --uid pc-b --name DeskB --watch p_xxx
```

自测：

```bash
cd desktop/companion
npm test
```

更多说明见 [../docs/USAGE.md](../docs/USAGE.md)
