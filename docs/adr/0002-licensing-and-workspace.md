# ADR-0002：许可证合规与仓库组织方式

- 状态：**已采纳（Accepted）**
- 日期：2026-10-18

## 背景

本仓库聚合了多个许可证不同的上游项目，并以"修改上游 + 引入新代码"的方式开发。需要明确：许可清单、合规模拟边界、仓库组织方式（为何不用 submodule）、新增代码的授权。

## 许可证清单（已核实）

| 组件 | 位置 | 许可证 | 义务要点 |
| --- | --- | --- | --- |
| TS6_Droid_CN（手机端） | `phone/` | **GPL-3.0** | 分发修改版需提供完整对应源码；保留版权声明 |
| tslib_multi（Rust 核心） | `tslib/` | **MIT OR Apache-2.0** | 保留版权与许可声明即可 |
| tsclientlib（协议实现，仅被 tslib 引用） | 外部依赖 | Apache-2.0 | 与 tslib 许可相容 |
| WebSpeak（Pad 端参照/基线） | `pad/` | **AGPL-3.0-only** | 面向网络提供服务时须向用户提供对应源码（本项目全程开源，天然满足）；保留版权声明 |
| teamspeak-js（协议参考/SDK） | `references/teamspeak-js/` | **MIT** | 保留版权与许可声明 |
| 本项目新增（文档/脚本/跨端代码） | 根目录、`docs/` | GPL-3.0-or-later | 与两端许可均兼容 |

各子目录内**保留上游 LICENSE 文件**，任何分发物（APK、服务端包）不得移除。

## 关键合规判断

1. **GPL-3.0 与 AGPL-3.0 的组合**：两者第 13 条互相允许"链接或组合"，组合结果整体按 AGPL-3.0 处理。因此 B6（手机/Pad 共享模块）在许可上可行，但**被共享进 Pad 的代码必须以 AGPL-3.0 提供**（而对手机单独分发时仍可按 GPL-3.0）。
2. **屏幕共享协议知识**（A2 从 WebSpeak/teamspeak-js 提取的命令名、流程）：协议命令属于互操作性事实，不受版权保护；但**代码不得照抄**，`tslib/` 与 `phone/` 中的实现须独立编写（可参考流程，不复制 AGPL 代码文本）。这是 A2/A3 必须遵守的红线。
3. **分发审查点**：
   - 手机 APK（基于 GPL-3.0 基线）→ 分发时附对应源码（本仓库即源码）。
   - Pad 分发物（若包含 AGPL 部件）→ 附 AGPL-3.0 源码与许可声明；若走"远程网关"形态，网关侧修改也须提供源码。

## 仓库组织方式

采用**聚合仓 + 目录内联**（vendoring）：上游代码整树复制进 `phone/`、`tslib/`、`pad/`、`references/`，去除各自 `.git`，并在根 README 记录**上游仓库与精确提交 SHA**。

不采用 submodule 的理由：多上游需要本仓库内直接修改（屏幕共享协议改动同时涉及 `tslib` 与 `phone`），submodule 的独立提交工作流会显著增加 AI 代理与人工协作的摩擦。

**上游同步方式**：需要拉取上游更新时，重新浅克隆对应上游，与 `phone/`/`pad/` 做 diff，人工挑选合并；上游 SHA 记录在根 README 与本节，更新时同步修订。

**第三方依赖 vendor 策略**：对需要打补丁的第三方库（首个案例：`tsclientlib`，用于 TS6 屏幕共享通知透传，见 `docs/A2-screen-share-protocol.md` §7.1），同样以整树 vendor 方式置于对应子项目内（如 `tslib/vendor/tsclientlib/`），保留其 LICENSE 文件并记录上游 SHA；`Cargo.toml` 以 path 依赖引用（不使用 `[patch]` git 源，避免构建期网络依赖）。vendor 树须通过 `[workspace] exclude` 排除在我们的 Cargo workspace 之外（防止其 dev-dependencies 参与解析，历史上导致 `jni-sys` 版本死锁）；`tslib/Cargo.lock` 予以提交以锁定解析结果，保证构建可复现。

## 影响

- 新增代码按所在目录归属许可：`phone/` 内新增 → GPL-3.0；`pad/` 内新增 → AGPL-3.0-only；`tslib/` 内新增 → MIT OR Apache-2.0。
- 根目录与 `docs/` 下的项目文档、工具脚本 → GPL-3.0-or-later。
- 未来若新增"双端共享模块"目录，默认按 AGPL-3.0-only 授权（满足第 13 条组合要求），并在目录内注明。
- 本项目所有产物最终以开源方式分发；如未来出现闭源分发需求，需重新评估本 ADR。
