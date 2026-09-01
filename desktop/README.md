# Desktop companion

桌面端实现位于当前目录下。

- 负责连接同一套 MSS 信令
- 参与共享者/观察者的 WebRTC 协商
- 以同协议兼容方式与 Android 端互通

## 使用指南

```bash
cd desktop/companion
npm install
npm start
```

或者使用 CLI：

```bash
cd desktop/companion
npm run start:cli -- --room room-123 --uid pc-a --name DeskA --publish
npm run start:cli -- --uid pc-b --name DeskB --watch p_xxx
```

更多说明见 [../docs/USAGE.md](../docs/USAGE.md)
