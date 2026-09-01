# Mobile client

Android 端代码位于当前目录下的 `app/`。

- 语音协议：TeamSpeak 原生协议（ts3j + Concentus/Opus），与官方客户端互通
- 屏幕共享协议：MSS / WebRTC（仅与本项目同协议端互通）
- Gradle 模块：`:mobile:app`

## 构建

```bash
# 在仓库根目录
./gradlew :mobile:app:assembleDebug
./gradlew :mobile:app:testDebugUnitTest
```

APK 输出在 `mobile/app/build/outputs/apk/debug/`。改过包名或 Hilt 相关代码后如果遇到
奇怪的生成代码错误，先 `./gradlew :mobile:app:clean`。

## 使用指南

- 启动信令服务：`cd desktop/companion && npm install && npm start`（UI 与信令同端口 4173），
  或单独部署 `cd server/signaling && npm install && npm start`（8765）
- 确认 Android 端已连接到同一 TeamSpeak 服务器和频道
- 在设置中填入信令地址，例如 `http://192.168.1.10:4173`
- 进入同一 room 后即可开始共享或观看屏幕

语音不需要信令服务，填服务器地址就能加入；信令只服务于屏幕共享，留空则屏幕共享不可用。

## 目录速览

```text
app/src/main/java/com/mooncc/teamspeak9/
├─ data/          # 仓库实现、DataStore 设置、Room 持久化
├─ domain/        # 领域模型与仓库接口
├─ ui/            # Compose 界面（书签、频道树、聊天、屏幕、设置）
├─ voice/         # 原生协议客户端、Opus 编解码、采集/播放、前台服务
└─ screenshare/   # MSS 信令客户端与 WebRTC 收发
```

更多说明见 [../docs/USAGE.md](../docs/USAGE.md)
