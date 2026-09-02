# 原生依赖：libopus

TSLib 的语音编解码通过 `[DllImport("libopus")]` 调用原生 libopus
（`third_party/TSLib/Audio/Opus/NativeMethods.cs`）。静态构造即触发
`Helper/NativeLibraryLoader.cs` 的 `PreloadLibrary()`，其搜索顺序为：

1. `{工作目录}/lib/x64/libopus.dll`
2. `{工作目录}/lib/libopus.dll`
3. `{程序集目录}/lib/x64/libopus.dll`
4. `{程序集目录}/lib/libopus.dll`

找不到时只在 NLog 里记一条 `Failed to load library`，**UI 仍能正常启动**，
但语音收发会在运行时失败。因此必须在打包阶段显式校验该文件存在。

## 放置方式

把 64 位 `libopus.dll` 放到本目录：

```
client/desktop/third_party/native/win-x64/libopus.dll
```

`TeamSpeak9.App.csproj` 会将其复制到输出目录的 `lib/x64/` 下（命中上面第 3 条路径）。
文件不存在时构建仅给出警告而不失败，以便在没有该二进制的环境下也能编译与跑单元测试。

## 为何不进仓库

libopus 是二进制产物，且各发行版的构建选项不同，因此不纳入版本控制
（见 `.gitignore` 中 `third_party/native/` 规则）。请从下列任一来源获取：

- 官方源码自行构建：<https://opus-codec.org/downloads/>（BSD-3-Clause）
- 已有的 TeamSpeak 3 客户端安装目录中同名文件
- vcpkg：`vcpkg install opus:x64-windows`，产物在 `installed/x64-windows/bin/opus.dll`
  （需重命名为 `libopus.dll`）

许可要求见仓库根目录 `NOTICE` 第 3 节。
