# Vendor 记录：tsclientlib（含 TS6-ANDROID-PATCH）

本目录是上游 `tsclientlib` 仓库的 **vendor 副本**（不含 `.git`），用于承载本项目的
“未知命令/通知透传”补丁（屏幕共享功能的前置条件）。

## 来源

| 项 | 值 |
| --- | --- |
| 上游仓库 | https://github.com/ReSpeak/tsclientlib |
| 上游提交 | `ee3bc6f45a7137db7793ba5593a321df400d53e5` |
| 拉取日期 | 2026-10-18 |
| 子模块 `utils/tsproto-structs/declarations` | https://github.com/ReSpeak/tsdeclarations @ `83cb8a94b65021bb8ec4beb339e5bfbdd93df60f`（已展开为普通目录，随本仓库提交） |
| 许可证 | MIT OR Apache-2.0（见 `LICENSE-MIT`、`LICENSE-APACHE`；declarations 同为双许可） |

## 补丁内容（搜索标记 `TS6-ANDROID-PATCH`）

| 文件 | 改动 |
| --- | --- |
| `tsclientlib/src/lib.rs` | ① 新增 `UnknownCommand { name, content }` 结构与 `StreamItem::UnknownCommand` 变体；② `handle_command()` 中 `InMessage::new` 解析失败时，若错误为 `ParseError::UnknownCommand`，不再丢弃而是透传原始命令内容（上游行为：一律 warn 后丢弃） |
| `tsclientlib/src/sync.rs` | 匹配新增变体（同步封装层直接忽略，由异步 API 消费） |
| `../Cargo.toml`（tslib 根） | 4 个 TS 依赖由 `git = ...` 改为 `path = ...` 指向本目录 |

## 目的与用法

- 上游 `tsproto`/`ts-bookkeeping` 的消息声明仅覆盖 TS3 协议；TS6 屏幕共享下发的是
  `notifystreamstarted` / `notifystreamsignaling` 等**未知通知命令**，在上游会被直接丢弃，
  导致 `tslib` 无法感知屏幕共享。
- 透传后，`tslib` 侧使用 `tsproto_packets::commands::CommandParser` 解析
  `content` 字段即可获得命令名与参数（转义由 parser 处理）。
- 协议细节见 `docs/A2-screen-share-protocol.md`。

## 升级步骤（上游同步）

1. 重新浅克隆上游（记录新 SHA），并在克隆目录内
   `git submodule update --init` 展开 declarations；
2. 复制整树覆盖本目录（排除 `.git`）；
3. 按上表重新应用补丁（搜索 `TS6-ANDROID-PATCH` 标记可核对）；
4. 更新本文件中的 SHA 与日期；
5. 在 `tslib/` 下运行 `cargo check -p tslib-core` 验证。
