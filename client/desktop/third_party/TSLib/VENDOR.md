# TSLib（vendored）

本目录是 [TS3AudioBot](https://github.com/Splamy/TS3AudioBot) 的 `TSLib/` 子目录副本，
**带本项目的本地改动**。它是 PC 客户端与 tsserver 通话所用的 TeamSpeak 协议层。

## 来源

| 项 | 值 |
|---|---|
| 上游仓库 | `https://github.com/Splamy/TS3AudioBot.git` |
| 上游路径 | `TSLib/` |
| 基线 commit | `a69a38d`（Merge pull request #1065 from OldGodShen/master） |
| 许可 | Open Software License 3.0（见本目录 `LICENSE`） |
| 引入方式 | 源码 vendor（非 submodule） |

## 为什么 vendor 而不是 submodule

1. **补丁必须常驻**。TSLib 原样**连不上 TeamSpeak 6 服务端**（见下），补丁是功能前提，
   不是可选优化。submodule 里的工作区改动不会被提交，克隆者拿到的是连不上的版本。
2. **离线可编**。vendor 后 `dotnet build` 不需要 `git submodule update`，也不需要构建机装 git。
3. **上游不再维护 TS6**。上游最后一次 release 面向 TS3/TS5，不会合并 TS6 相关改动，
   长期跟踪 submodule 没有收益，只会带来补丁冲突。

OSL-3.0 允许修改与再分发，条件是随分发提供对应源码并保留版权与许可声明。
本仓库开源即满足；本文件与根目录 `NOTICE` 共同构成改动声明。

## 本项目的改动

### 1. `Full/License.cs` — 支持 license chain block type 8（**功能前提**）

TS6 服务端（实测 6.0.0-beta12.1）在 license chain 里插入了一种 TSLib 不认识的
block（`type == 8`）。TSLib 遇到未知 type 直接返回
`Invalid license block type 8`，握手在密钥派生阶段就失败 —— 也就是说
**未打补丁的 TSLib 完全无法连接任何 TS6 服务端**。

该 block 的语义我们不关心（也不去逆向），密钥派生只需要它的**准确长度**。
实测布局为：偏移 42 起 12 字节不透明数据、偏移 54 起 null 结尾的 issuer 字符串、
其后 7 字节不透明数据，故 `read = 20 + nullStr.read`。

长度算错不会静默出错：下一个 block 的 `data[0] != 0` 会立刻触发
`Wrong key kind in license`，因此这个改动是**可自证的**。

配套改动：新增 `Ts6ExtensionLicenseBlock` 类型与 `ChainBlockType.Ts6Extension = 8`；
`default` 分支的报错补上 `HexDump`，便于将来遇到新 block type 时取证。

diff：+38 / -1，完整补丁见 `patches/0001-license-chain-block-type-8.patch`。

### 2. `TSLib.csproj` — 收窄 target framework

上游多目标 `netcoreapp3.1;netstandard2.0;netstandard2.1`，vendor 后**只保留
`netstandard2.1`**。原因：多个文件按 `NETSTANDARD2_1` / `NETCOREAPP3_1` /
`NETSTANDARD2_0` 条件编译（`Commands/TsString.cs`、`Query/TsQueryClient.cs`、
`Helper/SpanExtensions.cs`、`Helper/NativeLibraryLoader.cs`、
`TsBaseFunctions.FileTransfer.cs`、`dnc2_compat/`），
`netstandard2.1` 正是 TS6 兼容性探针实测通过的那条分支，保持一致可避免走到未验证的代码路径。
同时加 `IsPackable=false`（本项目不发布 NuGet 包）。

其余文件与上游 `a69a38d` 逐字节一致。

## 如何验证 vendor 副本没有其他改动

```powershell
git clone https://github.com/Splamy/TS3AudioBot.git /tmp/tsab
cd /tmp/tsab; git checkout a69a38d
git apply --directory=. <本目录>/patches/0001-license-chain-block-type-8.patch
# 之后 diff TSLib/ 与本目录，应只剩 TSLib.csproj、LICENSE、VENDOR.md、patches/ 的差异
```

## 相关文档

- [TSLib ↔ TS6 服务端兼容性实测报告](../../../../docs/desktop/tslib-ts6-compat.md) ——
  license 补丁的取证过程、图标写路径矩阵、TSLib 调用注意事项
- 根目录 [NOTICE](../../../../NOTICE) —— 第三方许可总览
