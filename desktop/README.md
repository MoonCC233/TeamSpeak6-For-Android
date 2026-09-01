# Desktop companion

桌面端实现位于当前目录下。

- 负责连接同一套 MSS 信令
- 参与共享者/观察者的 WebRTC 协商
- 以同协议兼容方式与 Android 端互通

`server.js` 只做两件事：托管 UI 静态文件，以及把
[`server/signaling`](../server/signaling) 的信令实现挂到同一个端口上。协议本身没有第二份实现，
所以桌面端和手机端不会出现行为漂移。要改协议请改 `server/signaling/index.js`。

`main.js` 是 Electron 外壳。它不是直接 `loadFile('index.html')`，而是先在进程内起
`server.js`，再用 `loadURL('http://127.0.0.1:<port>')` 加载同一份 UI——这样外壳模式与浏览器
模式共用一个 origin（页面 CSP 是 `default-src 'self'`，file:// 下会失配），也共用同一份信令实现。

## 使用指南

### 桌面应用（Electron 外壳）

```bash
cd desktop/companion
npm install
npm start
```

外壳内置屏幕捕获选择器：Electron 没有系统级 picker 时 `getDisplayMedia()` 会直接失败，所以
`main.js` 注册了 `setDisplayMediaRequestHandler`，优先选整块屏幕（窗口列表取决于用户开了什么，
屏幕则一定存在）。系统声音只在 Windows 上抓取——Electron 的 loopback 只在 Windows 可用，其他
平台请求它会让整个捕获请求失败。

若 4173 被占用，外壳会退到一个随机端口并在控制台提示，UI 里的信令地址会自动填成实际端口。

### 浏览器模式（不装 Electron）

```bash
cd desktop/companion
npm install --omit=dev
npm run serve
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
