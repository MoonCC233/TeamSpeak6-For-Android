# Mobile client

Android 端代码位于当前目录下的 `app/`。

- 语音协议：TeamSpeak 原生协议
- 屏幕共享协议：MSS / WebRTC
- 入口：`mobile/app`

## 使用指南

- 先启动信令服务：`cd server/signaling && npm install && npm start`
- 确认 Android 端已连接到同一 TeamSpeak 服务器和频道
- 在设置中填入信令地址，例如 `http://192.168.1.10:8765`
- 进入同一 room 后即可开始共享或观看屏幕

更多说明见 [../docs/USAGE.md](../docs/USAGE.md)
