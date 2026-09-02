# ts9-stream 部署与运维指南

本文档描述屏幕共享旁挂服务 `ts9-stream` 的构建、配置、部署与排障。

信令协议规范见 [tssp-v1.md](../protocol/tssp-v1.md)，配置文件的逐项注释见
[config.example.yaml](../../server/ts9-stream/config.example.yaml)。本文不重复抄注释，
重点讲**怎么选值**和**为什么**。

---

## 1. 它是什么，不是什么

`ts9-stream` 是一个独立的 Go 单二进制服务，为本项目的 Android 与 PC 客户端提供
屏幕共享所需的**信令**（WebSocket，TSSP v1 协议）与**媒体转发**（WebRTC SFU）。

它**不是** tsserver 的补丁、插件或代理：

- 不修改、不逆向、不反编译官方 `tsserver`（其许可证第 7.3 条明确禁止）。
- 不介入 TS 语音 / 文字 / 频道流量，两条链路完全独立。
- 对 tsserver 的唯一依赖是**只读 ServerQuery 查询**，用于验证「某个客户端确实以某个
  身份在线于某个频道」。

由此带来一条必须提前知晓的限制：**本服务的屏幕共享与官方 TS6 客户端不互通。**
官方客户端看不到本项目客户端共享的画面，反之亦然。这是不逆向官方私有信令的必然代价。

### 1.1 客户端的两条连接

```
                     ┌──────────────────────────┐
                     │ tsserver (闭源, 不改动)   │
                     │ 语音 / 文字 / 频道 / 权限  │
                     └──▲───────────────────▲────┘
        TS 私有协议 UDP  │                   │ ServerQuery (ssh, TS6 无 raw)
             9987        │                   │ 仅 ts9-stream 使用, 只读
                         │                   │
   ┌─────────────────────┴──┐      ┌─────────┴───────────────────────┐
   │ Android (Kotlin)       │ WSS  │ ts9-stream (Go)                 │
   │ PC (WPF / C#)          │◄────►│  · TSSP v1 信令   :10099/tcp    │
   │  采集 / 编码 / 渲染     │10099 │  · pion SFU 媒体  udp 范围      │
   └────────────────────────┘      └─────────────────────────────────┘
              ▲                                    │
              └────── SRTP (SFU 转发 或 P2P 直连) ──┘
```

客户端先连 tsserver 拿到自己的 `clid` / `uid` / `cid`，再带着这些字段向 ts9-stream
发 `hello`；服务端用 ServerQuery 反查核对后签发短时效令牌。tsserver 掉线时客户端
必须同步关闭流会话。

### 1.2 两种媒体模式

| 模式 | 媒体路径 | 服务器带宽 | 适用场景 |
|---|---|---|---|
| `sfu` | 发布者 → ts9-stream → 每个订阅者 | 高（N 路下行） | 多人观看、对称 NAT、需要稳定出图 |
| `p2p` | 发布者 ↔ 订阅者直连 | 零（仅信令） | 1 对 1、双方 NAT 友好、想省带宽 |

两种模式共用同一套信令，由客户端在 `setup` 时选择，服务端通过 `modes` 决定允许哪些。
P2P 若 15 秒内 ICE 未连通，客户端会自动回落到 SFU；**SFU 失败不会回落 P2P**
（SFU 失败通常意味着服务端配置问题，回落只会掩盖故障）。

---

## 2. 部署拓扑

### 2.1 同机部署（推荐）

ts9-stream 与 tsserver 跑在同一台机器上。

```
┌─────────────────────────────────────────────────┐
│ 一台主机                                         │
│                                                 │
│  tsserver          ts9-stream                   │
│   9987/udp  语音     10099/tcp  TSSP (WSS)      │
│  30033/tcp  文件     40000-42000/udp  SFU 媒体  │
│  10022/tcp  query ◄──── 127.0.0.1 only          │
└─────────────────────────────────────────────────┘
```

优点：

- ServerQuery 走 `127.0.0.1`，**query 端口完全不需要对外开放**。
- 天然落在 tsserver 的 `query_ip_allowlist.txt` 内（该文件默认就含 `127.0.0.1`），
  不会被 ServerQuery flood 保护误伤。

代价：SFU 的转发带宽与 tsserver 争抢同一张网卡。若预期并发观看人数多（例如
10 路 3 Mbps 的流 = 30 Mbps 下行），优先考虑分机部署。

### 2.2 分机部署

ts9-stream 独立一台机器（例如带宽更便宜的机器）。此时必须额外做两件事：

1. **把 ts9-stream 的 IP 加进 tsserver 的 `query_ip_allowlist.txt`**（一行一个 IP）。
   否则周期性的 `clientinfo` 查询会触发 ServerQuery flood 保护并被临时封禁，
   表现为客户端 `hello` 大面积返回 `QUERY_UNAVAILABLE`。
2. **query 端口只在内网 / VPN / 安全组白名单内可达**。见 [§7.2](#72-query-端口绝不能暴露公网)。

```
┌──────────────────┐   query (内网/VPN)   ┌──────────────────────┐
│ tsserver 主机     │◄─────────────────────│ ts9-stream 主机       │
│ 9987/udp         │  10022/tcp           │ 10099/tcp            │
│ 10022/tcp (内网)  │                      │ 40000-42000/udp      │
└──────────────────┘                      └──────────────────────┘
```

### 2.3 多虚拟服务器

一个 ts9-stream 实例可以服务多个虚拟服务器：在 `servers` 数组里为每个虚拟服务器
写一个条目即可。客户端 `hello` 携带的 `server_addr` 用于选中对应条目，匹配不到
则返回 `UNKNOWN_SERVER`。

多个虚拟服务器在**同一个 tsserver 实例**上时，它们共享同一个 query 端口，
靠 `virtual_port` 区分（服务端内部执行 `use port=<virtual_port>`）。

---

## 3. 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| Go | 1.25+ | 仅构建时需要；部署机不需要装 Go |
| tsserver | TS6 | 需开启 ServerQuery。TS6 只有 `ssh` / `http` / `https`，本服务走 `ssh` |
| ServerQuery 账号 | 只读，2 条权限 | 见 [§5](#5-准备-serverquery-账号) |
| TLS 证书 | 生产必需 | 见 [§6](#6-tls-证书) |
| 公网可达 | 1 个 TCP 端口 + 1 段 UDP 端口 | 见 [§7](#7-防火墙与网络) |
| 操作系统 | Linux / Windows | 交叉编译无外部依赖（纯 Go，`CGO_ENABLED=0`） |

### 3.1 tsserver 不在本仓库内 🔑

TeamSpeak 官方服务端是专有软件，其许可协议第 7.3 条禁止复制与再分发，因此
**本仓库不包含 `tsserver` 二进制**（`.gitignore` 里已排除 `server/win/` 与 `server/linux/`）。

自行从官方下载后解压到对应目录，本项目的文档与脚本按这个布局引用它：

```
server/
  win/        tsserver.exe + ssh.dll + tsdb_*.dll + doc/ + serverquerydocs/ + sql/
  linux/      tsserver + libssh.so.4 + libtsdb_*.so + doc/ + serverquerydocs/ + sql/
  ts9-stream/ 本项目的旁挂服务（在仓库内）
```

下载地址：<https://teamspeak.com/en/downloads/#server>（选 **TeamSpeak 6 Server**）。
本项目的兼容性结论基于 **6.0.0-beta12.1** 实测，见
[TSLib ↔ TS6 服务端兼容性实测报告](../desktop/tslib-ts6-compat.md)。

ts9-stream 与 tsserver 之间只有一条**只读** ServerQuery 连接，不修改、不代理、
不逆向 tsserver，因此对 tsserver 的版本没有硬性下限，但 §5.4 描述的 query 端点
差异（TS6 移除了 raw query）要求 **TS6**。

---

## 4. 构建

在 `server/ts9-stream/` 目录下执行。

### 4.1 本机构建

```bash
cd server/ts9-stream
go build -trimpath -ldflags "-s -w -X main.version=0.1.0" -o ts9-stream ./cmd/ts9-stream
```

- `-trimpath` 去掉二进制里的绝对构建路径。
- `-s -w` 去掉符号表与 DWARF，体积约小 30%。
- `-X main.version=` 覆盖 [main.go](../../server/ts9-stream/cmd/ts9-stream/main.go) 里的
  `version` 变量（默认 `0.1.0-dev`），会出现在 `-version` 输出、启动日志与
  `/healthz` 响应里。建议填 git tag 或 `git describe --tags --always`。

### 4.2 交叉编译到 Linux

在 Windows 上为 Linux 服务器构建（PowerShell）：

```powershell
$env:CGO_ENABLED = "0"
$env:GOOS = "linux"
$env:GOARCH = "amd64"
go build -trimpath -ldflags "-s -w -X main.version=0.1.0" -o ts9-stream ./cmd/ts9-stream
```

本项目不使用 cgo，`CGO_ENABLED=0` 下产出的是完全静态的二进制，可以直接丢进
任何 glibc / musl 发行版，也可以放进 `scratch` 容器镜像。

### 4.3 构建前自检

```bash
go vet ./...
gofmt -l .          # 应无输出
go test ./...
```

`gofmt -l` 若列出文件，通常是行尾被写成了 CRLF。仓库的 `.gitattributes` 已强制
`*.go text eol=lf`，但**已经存在于工作区的文件不会被自动转换**，需要手动改回 LF。

---

## 5. 准备 ServerQuery 账号

ts9-stream 只做只读校验，**不要用 `serveradmin`**。下面创建一个仅有必要权限的
专用 query 账号。

用任意 ServerQuery 客户端（`ssh`、`curl` 走 WebQuery、TeamSpeak 客户端的 query 面板）以
`serveradmin` 登录后执行。

> **TeamSpeak 6 注意**：TS6 服务端已**移除**明文 raw query 端点（10011），
> 只提供 `ssh`（10022）、`http`（10080）、`https`（10443）。
> 因此 TS6 上不能用 `telnet` 连 query，也不要把 `query_protocol` 配成 `raw`。详见 [§5.4](#54-ts6-与-ts3-的-query-端点差异)。

### 5.1 需要的权限

| 权限 | 用途 | 是否必需 |
|---|---|---|
| `b_virtualserver_select` | 执行 `use port=N` 选中虚拟服务器 | 必需 |
| `b_client_info_view` | 执行 `clientinfo` 读取 uid / cid / 服务器组 | 必需 |
| `b_virtualserver_client_list` | 执行 `clientlist` | 可选（当前实现只用 `clientinfo`） |

`clientlist` 若要看到别的频道里的客户端，还需要 `i_channel_subscribe_power`
不低于目标频道的 `i_channel_needed_subscribe_power`。当前实现只用 `clientinfo`，
不受此限制；列出该权限是为了将来扩展时有据可查。

### 5.2 逐条命令

```
# ① 建一个 ServerQuery 组（type=2 表示 query 组）
servergroupadd name=ts9stream type=2
# → 返回 sgid=<N>，记下这个 N

# ② 给组加权限
servergroupaddperm sgid=<N> permsid=b_virtualserver_select permvalue=1 permnegated=0 permskip=0
servergroupaddperm sgid=<N> permsid=b_client_info_view permvalue=1 permnegated=0 permskip=0

# ③ 建 query 登录账号（先切到服务器实例级）
use 0
queryloginadd client_login_name=ts9stream
# → 返回 cldbid=<M> client_login_password=<随机密码>
#    密码只在此处返回一次, 立刻记下来

# ④ 把该账号加入上面建的组
servergroupaddclient sgid=<N> cldbid=<M>
```

创建 query 登录需要 `b_serverquery_login_create` 权限；`queryloginlist` 需要
`b_serverquery_login_list`（用于事后核对账号是否存在，不会显示密码）。

### 5.3 验证

```
login ts9stream <密码>
use port=9987
clientinfo clid=1
```

`clientinfo` 返回 `error id=0` 或 `error id=512 msg=invalid\sclientID` 都算正常
（后者只是说 clid=1 当前没人用）；返回 `error id=2568 msg=insufficient\sclient\spermissions`
则说明权限没加对。

### 5.4 TS6 与 TS3 的 query 端点差异

TeamSpeak 6 服务端（实测 `6.0.0-beta12.1`）的 query 端点与 TS3 不同：

| 端点 | TS3 | TS6 |
|---|---|---|
| raw / telnet（10011） | ✅ 有 | ❌ **已移除**，端口不监听 |
| ssh（10022） | ✅ 有 | ✅ 有（banner `SSH-2.0-libssh_0.11.4`） |
| http WebQuery（10080） | 需插件 | ✅ 内置 |
| https WebQuery（10443） | 需插件 | ✅ 内置 |

TS6 启动日志只打印 `listening for ssh query on …` 与 `listening for http query on …` 两行，
没有 raw query 的监听行。实测依据见
[TSLib ↔ TS6 兼容性报告 §7](../desktop/tslib-ts6-compat.md)。

因此：

- **`query_protocol` 的默认值是 `ssh`**（默认端口 10022），在 TS6 与 TS3 上都能用。
- 只有连接 **TS3** 服务端且明确想用明文端口时，才把 `query_protocol` 设为 `raw`（默认端口 10011）。
- ts9-stream 目前只实现了 raw 与 ssh 两种**行协议**后端，尚未实现 HTTP WebQuery 后端。
  这在 TS6 上不成问题（ssh 可用），但若将来 tsserver 关掉 ssh 只留 http，需要补一个后端。

`raw` 与 `ssh` 两种后端的行为差异：

| | `raw` | `ssh` |
|---|---|---|
| 默认端口 | 10011 | 10022 |
| 传输 | 明文 telnet | SSH 加密 |
| 认证 | 连接后发 `login` 命令 | 在 SSH 层完成，**不再发 `login`** |
| TS6 支持 | ❌ | ✅ |
| 推荐场景 | 仅 TS3 同机 `127.0.0.1` | 默认，任何场景 |

> **SSH 主机密钥说明**：ts9-stream 的 SSH 客户端使用 `InsecureIgnoreHostKey`，
> 即不校验 tsserver 的 query 主机密钥。原因是该密钥由 tsserver 首次启动时随机生成，
> 没有可信的分发渠道，硬编码指纹反而会在重装服务器后造成静默故障。
> **因此 query 端点只能暴露在可信网络内**（同机 `127.0.0.1` 或内网），
> 不可跨公网访问 —— 否则存在中间人风险。见 [§7.2](#72-query-端口绝不能暴露公网)。

---

## 6. TLS 证书

TSSP v1 要求生产环境使用 `wss://`。`listen.tls_cert` 与 `listen.tls_key` 必须
**同时**提供（只给一个会在启动时报错）；两者都不给且 `runtime.dev_insecure=false`
时同样拒绝启动。

### 6.1 Let's Encrypt（推荐）

有域名时首选。以 certbot 为例：

```bash
certbot certonly --standalone -d stream.example.com
```

```yaml
listen:
  tls_cert: "/etc/letsencrypt/live/stream.example.com/fullchain.pem"
  tls_key:  "/etc/letsencrypt/live/stream.example.com/privkey.pem"
```

证书续期后需要让 ts9-stream 重新加载 —— 当前实现在启动时读取一次证书，
**不监听文件变化**，所以在 certbot 的 deploy hook 里重启服务：

```bash
# /etc/letsencrypt/renewal-hooks/deploy/ts9-stream.sh
#!/bin/sh
systemctl restart ts9-stream
```

重启会断开所有正在进行的共享（客户端会收到 `bye` 并自动重连），因此建议把续期
安排在低峰时段。

证书文件的读取发生在**降权之后**，所以要保证运行服务的用户能读到 `privkey.pem`：

```bash
# 让 ts9-stream 用户能读 letsencrypt 私钥
groupadd -f tls-readers
usermod -aG tls-readers ts9-stream
chgrp -R tls-readers /etc/letsencrypt/live /etc/letsencrypt/archive
chmod -R g+rX /etc/letsencrypt/live /etc/letsencrypt/archive
```

### 6.2 自签证书

没有域名（例如纯内网部署）时：

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout privkey.pem -out fullchain.pem \
  -subj "/CN=stream.lan" \
  -addext "subjectAltName=DNS:stream.lan,IP:10.0.0.5"
```

**必须填 `subjectAltName`**，只有 CN 的证书会被现代 TLS 栈（含 .NET 与 Android）
直接拒绝。客户端侧需要手动信任该证书的指纹：

```bash
openssl x509 -in fullchain.pem -noout -fingerprint -sha256
```

把输出的指纹配进客户端的「信任的服务器指纹」设置项。

### 6.3 放在反向代理后面

也可以让 nginx / Caddy 终结 TLS，ts9-stream 只监听 `127.0.0.1` 明文：

```yaml
listen:
  addr: "127.0.0.1:10099"
runtime:
  dev_insecure: true   # 允许明文 ws://
```

⚠️ 这里 `dev_insecure: true` 有副作用：缺少 `token_secret` 时会**自动生成临时密钥**，
进程重启后所有令牌失效。放在反代后面时**务必显式配置 `token_secret`**，
并确保 `127.0.0.1:10099` 不会被别的途径直接访问到。

nginx 侧要注意 WebSocket 升级与足够长的读超时：

```nginx
location /tssp/v1 {
    proxy_pass http://127.0.0.1:10099;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    # 注意用 $remote_addr 而不是 $proxy_add_x_forwarded_for：
    # 后者会保留客户端自带的 XFF，让限流可被伪造绕过。见 §7.5
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_read_timeout 120s;   # 必须大于 runtime.read_timeout
}
```

同时**必须**在 ts9-stream 侧把代理声明为可信，否则 `X-Forwarded-For` 会被忽略、
所有客户端都会被算成同一个 IP（来自代理）而共享同一份限流配额：

```yaml
listen:
  trusted_proxies: ["127.0.0.1"]
```

详见 [§7.5](#75-x-forwarded-for-与可信代理)。

注意 SFU 的媒体流是 UDP，**不经过反向代理**，仍需按 [§7](#7-防火墙与网络) 直接放通。

---

## 7. 防火墙与网络

### 7.1 端口清单

| 端口 | 协议 | 方向 | 归属 | 是否需公网 | 说明 |
|---|---|---|---|---|---|
| 10099 | tcp | 入 | ts9-stream | **是** | TSSP 信令（WSS）。同时提供 `/healthz` `/readyz` |
| 40000–42000 | udp | 入 | ts9-stream | **是**（SFU 模式） | SFU 媒体，对应 `media.udp_port_min/max` |
| 3478 | udp+tcp | 出/入 | TURN | 可选 | P2P 在对称 NAT 下必需 |
| 5349 | tcp | 出/入 | TURNS | 可选 | TLS 上的 TURN |
| 9987 | udp | 入 | tsserver | 是 | TS 语音 |
| 30033 | tcp | 入 | tsserver | 是 | 文件传输（含频道图标） |
| 10022 | tcp | 入 | tsserver | **否** | ServerQuery ssh（TS6 默认走这条），见下 |
| 10080 / 10443 | tcp | 入 | tsserver | **否** | TS6 WebQuery（http / https），本服务暂未使用 |
| 10011 | tcp | 入 | tsserver | **否** | ServerQuery raw，**仅 TS3**（TS6 已移除），见下 |

UDP 端口范围留多大：每路 WebRTC 连接（一个发布或一个订阅）会占用少量端口，
按「峰值并发连接数 × 4，再乘 2 冗余」估算即可。示例的 2000 个端口对几十路流
绰绰有余。**不配置 `udp_port_min/max` 时 pion 会用随机高位端口**，此时无法写
精确的防火墙规则，只能放通整段临时端口范围 —— 所以生产环境建议显式限定。
两项必须同时配置，只配一个会在启动时报错。

### 7.2 query 端口绝不能暴露公网

ServerQuery 端口（10022 / 10080 / 10443，TS3 上还有 10011）**只应对 ts9-stream 所在主机可达**：

- `ssh`（10022）虽然加密，但 ts9-stream 侧不校验主机密钥（见 [§5.4](#54-ts6-与-ts3-的-query-端点差异)），
  跨公网存在中间人风险。
- `raw`（10011，仅 TS3）是**明文**协议，密码在网络上裸奔。
- WebQuery（10080）同样是明文 HTTP，API key 会随请求头发送。
- query 账号即使只有只读权限，暴露在公网也会成为暴力破解目标。

正确做法：

```bash
# 同机部署：query 只绑回环，从根本上不可能被外部访问
#   tsserver 命令行: query_ip=127.0.0.1

# 分机部署：用防火墙精确放通 ts9-stream 的 IP
ufw allow from 10.0.0.5 to any port 10022 proto tcp
```

### 7.3 NAT 与 `public_ip`

如果 ts9-stream 跑在 NAT 或云主机后面（网卡上是内网地址、对外是弹性公网 IP），
**必须**配置 `media.public_ip`：

```yaml
media:
  public_ip: "203.0.113.20"
```

否则 pion 只会收集到内网候选地址（如 `10.0.0.5`），外部客户端拿到这些候选后
永远连不通，表现为「信令一切正常、协商完成、但画面一直是黑的」。

云厂商上可以这样自动取（首次部署时手工确认一次即可）：

```bash
# 阿里云 / 腾讯云 / AWS 的 metadata 端点各不相同, 下面是通用兜底
curl -s https://api.ipify.org
```

不确定值是否正确时，用 `TS9STREAM_PUBLIC_IP` 环境变量临时覆盖来对比验证，
不必改配置文件。

### 7.4 ServerQuery flood 保护

tsserver 的 `query_ip_allowlist`（默认 `query_ip_allowlist.txt`，新装时含 `127.0.0.1`）
里的主机会被**豁免** ServerQuery flood 保护。

- 同机部署：天然在白名单内，无需操作。
- 分机部署：**必须**把 ts9-stream 的 IP 追加进该文件（一行一个 IP），然后重启 tsserver。

漏了这一步的典型表现：服务刚启动一切正常，随着在线人数增加，客户端 `hello`
开始间歇性返回 `QUERY_UNAVAILABLE`，且 ts9-stream 日志里出现 query 连接被
对端关闭的记录。

`auth.query_cache_ttl`（默认 3s）会把同一客户端的查询结果缓存起来以削减查询量，
但它不能替代白名单。

### 7.5 X-Forwarded-For 与可信代理

按 IP 的 hello 失败限流（`limits.hello_fail_*`）依赖于正确识别客户端 IP。
ts9-stream 默认**只用 TCP 对端地址**，完全忽略 `X-Forwarded-For`。

这个默认值很重要：如果无条件信任该头，任何直连的攻击者只要每次请求换一个伪造的
`X-Forwarded-For`，就能让每次尝试都落到不同的限流桶里，从而无限次暴力猜测
`clid`/`uid` 组合，限流形同虚设。

因此：

- **直接暴露给客户端**（没有反向代理）：`trusted_proxies` 留空。这是默认值，不用管。
- **放在反向代理后面**：必须显式声明代理地址，否则所有客户端会被算作同一个 IP
  （代理的 IP），一个人触发封禁会连带封掉所有人。

```yaml
listen:
  trusted_proxies:
    - "127.0.0.1"      # 同机 nginx
    - "10.0.0.0/8"     # 内网负载均衡集群
```

接受单个 IP 或 CIDR 两种写法，IPv4 与 IPv6 都支持；域名会在启动时报错
（IP 需要在每次请求时同步比较，无法容忍 DNS 解析的开销与不确定性）。

请求来自可信代理时，取 `X-Forwarded-For` 的**最右侧**一段作为客户端 IP ——
这一段是可信代理直接观察到的对端，左侧各段都可以被客户端伪造。所以反向代理
必须配成**覆盖**而不是追加该头（nginx 用 `$remote_addr` 而非
`$proxy_add_x_forwarded_for`）。

---

## 8. 配置详解

完整的逐项注释见 [config.example.yaml](../../server/ts9-stream/config.example.yaml)。
本节只讲怎么选值。

复制一份开始改：

```bash
cp config.example.yaml /etc/ts9-stream/config.yaml
chmod 600 /etc/ts9-stream/config.yaml    # 里面可能有 query 密码
```

### 8.1 最小可用配置

生产环境真正必填的只有四项：

```yaml
listen:
  tls_cert: "/etc/ts9-stream/fullchain.pem"
  tls_key:  "/etc/ts9-stream/privkey.pem"

servers:
  - server_addr: ["ts.example.com:9987"]
    query_host: "127.0.0.1"
    query_protocol: "ssh"
    query_user: "ts9stream"
    query_password: ""      # 用 TS9STREAM_QUERY_PASSWORD 提供

media:
  public_ip: "203.0.113.20" # 在 NAT 后面时必填
```

`auth.token_secret` 通过环境变量提供（见 [§8.5](#85-环境变量)）。其余项全部
有合理默认值，**不确定就不要动**。

### 8.2 完整默认值表

未在配置文件中出现的项会取下列默认值（由 `Default()` 与 `Validate()` 共同确定）。

| 配置项 | 默认值 | 备注 |
|---|---|---|
| `listen.addr` | `:10099` | |
| `listen.base_path` | `/tssp/v1` | 必须以 `/` 开头 |
| `listen.trusted_proxies` | 空（不信任任何代理） | 只有来自这些地址的 `X-Forwarded-For` 才被采信 |
| `listen.tls_cert` / `tls_key` | 空 | 必须同时给；都为空且非 dev 模式则报错 |
| `log.level` | `info` | `debug`\|`info`\|`warn`\|`error` |
| `log.format` | `text` | `text`\|`json` |
| `auth.token_secret` | 空 | 非 dev 模式下为空则报错 |
| `auth.token_ttl` | `10m` | |
| `auth.renew_leeway` | `2m` | 配 0 或 ≥ `token_ttl` 时自动取 `token_ttl / 5` |
| `auth.query_cache_ttl` | `3s` | 设太长会让「刚换频道」的客户端短暂鉴权失败；配 `0` 则禁用缓存 |
| `servers[i].query_protocol` | `ssh` | TS6 已移除 raw；`raw` 仅 TS3 可用 |
| `servers[i].query_host` | 从 `server_addr[0]` 推导 | |
| `servers[i].query_port` | `ssh`→`10022`，`raw`→`10011` | |
| `servers[i].query_user` | `serveradmin` | 建议改成专用账号 |
| `servers[i].query_password` | 无默认 | **为空直接报错** |
| `servers[i].virtual_port` | 从 `server_addr[0]` 的端口推导 | |
| `servers[i].query_timeout` | `5s` | |
| `ice.turn_credential_ttl` | `10m` | |
| `limits.max_bitrate_kbps` | `4000` | |
| `limits.max_streams_per_channel` | `4` | |
| `limits.max_viewers_per_stream` | `16` | |
| `limits.max_streams_per_client` | `1` | |
| `limits.hello_timeout` | `10s` | |
| `limits.hello_fail_window` | `5m` | |
| `limits.hello_fail_max` | `10` | |
| `limits.hello_ban_time` | `5m` | |
| `limits.max_message_bytes` | `262144` | TSSP v1 规范值，够放最大 SDP |
| `limits.max_sessions` | `0` | 0 = 不限 |
| `limits.negotiation_timeout` | `30s` | |
| `modes` | `[sfu, p2p]` | 只接受这两个值 |
| `media.video_codecs` | `[H264, VP8]` | **只支持这两种**，大小写自动归一化 |
| `media.audio_codecs` | `[opus]` | |
| `media.pli_interval` | `3s` | 0 = 只在订阅接入时请求一次关键帧 |
| `media.udp_port_min` / `max` | 空 | 必须同时配或同时不配 |
| `media.public_ip` | 空 | 必须是合法 IP |
| `runtime.dev_insecure` | `false` | |
| `runtime.ping_interval` | `20s` | |
| `runtime.read_timeout` | `60s` | **若 ≤ `ping_interval` 会被强制改为 3×`ping_interval`** |
| `runtime.shutdown_grace` | `10s` | |

`server_addr` 会被归一化：缺端口补 `9987`，IPv6 需写成 `[::1]:9987`，
同一实例内重复地址会报错。

### 8.3 怎么选限流与码率

`limits` 的默认值针对「小型社群、单台 100 Mbps 上行的机器」。按下式估算 SFU 上行：

$$
\text{上行带宽} \approx \sum_{\text{每条流}} \text{bitrate} \times \text{该流的观看人数}
$$

默认上限（4 Mbps × 4 条流 × 16 人）理论峰值达 256 Mbps，对多数机器是过量的。
按实际带宽收紧，例如 50 Mbps 可用上行：

```yaml
limits:
  max_bitrate_kbps: 2500        # 1080p30 屏幕内容够用
  max_streams_per_channel: 2
  max_viewers_per_stream: 8     # 2 × 2.5 × 8 = 40 Mbps 峰值
```

屏幕共享的内容多为静态文本与 UI，H.264 在 2–3 Mbps 下的 1080p30 观感通常已足够；
盲目提高码率只会浪费带宽。

`max_streams_per_client: 1` 是有意的保守值 —— 一个客户端同时共享多个屏幕，
在 UI 上难以表达，也容易被滥用来占带宽。

### 8.4 怎么选鉴权参数

- `token_ttl` 默认 10 分钟。调短会增加 `renew` 频次（但 `renew` 不查 ServerQuery，
  成本极低）；调长会让「客户端已离开频道但令牌仍有效」的窗口变大。10 分钟是
  安全与开销的平衡点。
- `hello_fail_max: 10` / `hello_fail_window: 5m` / `hello_ban_time: 5m` 是按来源 IP
  的失败计数。**注意 NAT 场景**：同一出口 IP 后面有很多用户时（校园网、企业网），
  一个人反复失败会连带封掉其他人。这类场景可把 `hello_fail_max` 调高到 30–50。
  放在反向代理后面时，还必须配 `listen.trusted_proxies`，否则所有客户端会共享
  同一份配额 —— 见 [§7.5](#75-x-forwarded-for-与可信代理)。
- `query_cache_ttl: 3s`：客户端切频道后，最多 3 秒内的鉴权仍会用旧的频道 ID，
  可能导致 `IDENTITY_MISMATCH`。客户端会自动重试，不必调整。

### 8.5 环境变量

前缀 `TS9STREAM_`，优先级**高于**配置文件。空字符串视为未设置（不会覆盖）。
敏感项一律用环境变量，不要落盘。

| 环境变量 | 覆盖的配置项 |
|---|---|
| `TS9STREAM_LISTEN_ADDR` | `listen.addr` |
| `TS9STREAM_TLS_CERT` | `listen.tls_cert` |
| `TS9STREAM_TLS_KEY` | `listen.tls_key` |
| `TS9STREAM_BASE_PATH` | `listen.base_path` |
| `TS9STREAM_LOG_LEVEL` | `log.level` |
| `TS9STREAM_LOG_FORMAT` | `log.format` |
| `TS9STREAM_TOKEN_SECRET` | `auth.token_secret` |
| `TS9STREAM_TOKEN_TTL` | `auth.token_ttl`（解析失败则忽略） |
| `TS9STREAM_TURN_STATIC_AUTH_SECRET` | `ice.turn_static_auth_secret` |
| `TS9STREAM_DEV_INSECURE` | `runtime.dev_insecure` |
| `TS9STREAM_MAX_BITRATE_KBPS` | `limits.max_bitrate_kbps` |
| `TS9STREAM_PUBLIC_IP` | `media.public_ip` |
| `TS9STREAM_QUERY_USER` | `servers[0].query_user` ⚠️ **仅第一个** |
| `TS9STREAM_QUERY_PASSWORD` | `servers[0].query_password` ⚠️ **仅第一个** |

最后两项是单虚拟服务器场景的便利项。**多虚拟服务器时它们只影响 `servers[0]`**，
其余条目的密码必须写在配置文件里（并 `chmod 600`）。

生成令牌密钥：

```bash
openssl rand -hex 32
```

更换 `token_secret` 会让所有已签发令牌立即失效，客户端会自动重新 `hello`，
用户侧表现为正在进行的共享中断一次。

### 8.6 ICE / TURN

- **SFU 模式**：服务端有公网地址，客户端一般只需 STUN 就能连通。配好
  `media.public_ip` 比配 TURN 重要得多。
- **P2P 模式**：双方都在对称 NAT 后面时**必须**有 TURN 才能连通。

推荐用 coturn 的 REST 短时凭据，而不是长期静态账号：

```yaml
ice:
  turn_urls:
    - "turn:turn.example.com:3478?transport=udp"
    - "turns:turn.example.com:5349?transport=tcp"
  turn_static_auth_secret: ""    # 用 TS9STREAM_TURN_STATIC_AUTH_SECRET 提供
  turn_credential_ttl: 10m
```

对应的 coturn 配置：

```
static-auth-secret=<与上面同一个值>
use-auth-secret
realm=turn.example.com
```

ts9-stream 会为每个客户端签发有时效的用户名/密码（`<过期时间戳>:<uid>` +
HMAC-SHA1），比把一组固定凭据下发给所有客户端安全得多。

配了 `turn_urls` 却既没有 `turn_static_auth_secret` 也没有 `turn_username`
会在启动时报错 —— 无凭据的 TURN 一定连不上，早失败好于晚失败。

### 8.7 访问控制

按 tsserver 的服务器组 ID 限制谁能用屏幕共享。组 ID 用 ServerQuery 的
`servergrouplist` 查询。

```yaml
access:
  allow_server_groups: [6, 7]   # 非空时: 必须至少属于其中一个组
  deny_server_groups: [8]       # 一律拒绝, 优先级高于白名单
```

两者都留空表示不限制（任何通过身份校验的客户端都能用）。典型用法是把「访客」
组放进 `deny_server_groups`，防止未验证用户占用带宽。不匹配时客户端收到
`NOT_ALLOWED`。

---

## 9. 运行

### 9.1 先用 `-check` 验证配置

在把服务交给 init 系统之前，先做一次静态校验 + ServerQuery 连通性测试：

```bash
./ts9-stream -config /etc/ts9-stream/config.yaml -check
```

`-check` 会加载配置、跑完整校验、连一次 ServerQuery 然后退出，**不会开始监听**。
退出码 0 表示一切就绪，1 表示有问题（错误信息打在 stderr）。

这是部署时最有用的一条命令：它把「配置写错」和「服务起不来」这两类问题在
进入 systemd 之前就区分开了。

其他命令行参数：

| 参数 | 说明 |
|---|---|
| `-config <path>` | 配置文件路径（YAML） |
| `-check` | 只校验配置与连通性后退出 |
| `-version` | 打印版本后退出 |

### 9.2 Linux / systemd

创建专用用户与目录：

```bash
useradd --system --no-create-home --shell /usr/sbin/nologin ts9-stream
install -d -o root -g ts9-stream -m 750 /etc/ts9-stream
install -o root -g root -m 755 ts9-stream /usr/local/bin/ts9-stream
```

密钥放进只有 root 可读的 EnvironmentFile：

```bash
cat > /etc/ts9-stream/env <<'EOF'
TS9STREAM_TOKEN_SECRET=<openssl rand -hex 32 的输出>
TS9STREAM_QUERY_PASSWORD=<queryloginadd 返回的密码>
EOF
chmod 600 /etc/ts9-stream/env
chown root:root /etc/ts9-stream/env
```

`/etc/systemd/system/ts9-stream.service`：

```ini
[Unit]
Description=ts9-stream (TeamSpeak9 screen share signaling + SFU)
Documentation=https://github.com/MoonCC233/TeamSpeak9
After=network-online.target
Wants=network-online.target
# 同机部署时让它跟在 tsserver 后面启动（服务名按实际情况改）
After=tsserver.service

[Service]
Type=simple
User=ts9-stream
Group=ts9-stream
EnvironmentFile=/etc/ts9-stream/env
ExecStart=/usr/local/bin/ts9-stream -config /etc/ts9-stream/config.yaml
Restart=on-failure
RestartSec=5s
# 优雅退出：先广播流结束与 bye。超时值要大于 runtime.shutdown_grace
KillSignal=SIGTERM
TimeoutStopSec=30s

# ── 安全加固 ──────────────────────────────────────────
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictNamespaces=true
LockPersonality=true
MemoryDenyWriteExecute=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
# 只读访问证书目录（用 Let's Encrypt 时按实际路径改）
ReadOnlyPaths=/etc/ts9-stream /etc/letsencrypt

# SFU 会为每路媒体开若干 socket，默认的 1024 可能不够
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

`ts9-stream` 监听 10099（>1024），**不需要 `CAP_NET_BIND_SERVICE`**，
也不需要任何 capability。若你把 `listen.addr` 改到了特权端口（<1024），
再加 `AmbientCapabilities=CAP_NET_BIND_SERVICE` 与
`CapabilityBoundingSet=CAP_NET_BIND_SERVICE`。

启用并检查：

```bash
systemctl daemon-reload
systemctl enable --now ts9-stream
systemctl status ts9-stream
journalctl -u ts9-stream -f
```

日志走 **stdout**，systemd 会自动收进 journal。若要给 Loki / ELK 采集，
把 `log.format` 改成 `json`。

### 9.3 Windows 服务

Windows 上有两种方式。

**方式一：`sc.exe`**（最简单，但对非 Windows-Service 程序的支持有限）

```powershell
sc.exe create ts9-stream `
  binPath= "C:\ts9-stream\ts9-stream.exe -config C:\ts9-stream\config.yaml" `
  start= auto `
  DisplayName= "TeamSpeak9 Stream Service"
sc.exe description ts9-stream "TSSP signaling + WebRTC SFU for TeamSpeak9 screen sharing"
sc.exe start ts9-stream
```

注意 `binPath=` 后面**必须有一个空格**（`sc.exe` 的参数格式如此）。

`ts9-stream` 不实现 Windows Service 控制协议，`sc.exe` 方式下服务管理器
可能报告「服务未及时响应启动请求」。若遇到，改用方式二。

**方式二：NSSM**（推荐，能正确处理停止信号与日志重定向）

```powershell
nssm install ts9-stream C:\ts9-stream\ts9-stream.exe
nssm set ts9-stream AppParameters "-config C:\ts9-stream\config.yaml"
nssm set ts9-stream AppDirectory C:\ts9-stream
nssm set ts9-stream AppStdout C:\ts9-stream\logs\out.log
nssm set ts9-stream AppStderr C:\ts9-stream\logs\err.log
nssm set ts9-stream AppRotateFiles 1
nssm set ts9-stream AppRotateBytes 10485760
nssm set ts9-stream AppEnvironmentExtra `
  TS9STREAM_TOKEN_SECRET=<secret> `
  TS9STREAM_QUERY_PASSWORD=<password>
nssm set ts9-stream Start SERVICE_AUTO_START
nssm start ts9-stream
```

防火墙规则：

```powershell
New-NetFirewallRule -DisplayName "ts9-stream TSSP" `
  -Direction Inbound -Protocol TCP -LocalPort 10099 -Action Allow
New-NetFirewallRule -DisplayName "ts9-stream SFU media" `
  -Direction Inbound -Protocol UDP -LocalPort 40000-42000 -Action Allow
```

日志走 stdout，所以 `err.log` 通常是空的 —— 这是正常的，不要以为服务没在写日志。

### 9.4 Docker（可选）

纯静态二进制，可以用最小镜像：

```dockerfile
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY server/ts9-stream/ ./
RUN CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w -X main.version=0.1.0" \
      -o /out/ts9-stream ./cmd/ts9-stream

FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/ts9-stream /ts9-stream
EXPOSE 10099/tcp 40000-42000/udp
ENTRYPOINT ["/ts9-stream", "-config", "/etc/ts9-stream/config.yaml"]
```

容器化 SFU 有两个坑：

1. **不要用默认的 bridge 网络 + 端口映射**跑 SFU。大范围 UDP 端口映射会让
   docker-proxy 开出成千上万个转发，性能极差。用 `--network host`，
   或者只映射精确配置的那段范围。
2. **`media.public_ip` 必须配**，容器内看到的永远是私有地址。

---

## 10. 健康检查与监控

| 端点 | 语义 | 何时用 |
|---|---|---|
| `GET /healthz` | **永远返回 200**，不查 ServerQuery | liveness probe、LB 存活探测 |
| `GET /readyz` | 查 ServerQuery，失败返回 503 | readiness probe、告警 |

```bash
$ curl -s https://stream.example.com:10099/healthz
{"status":"ok","version":"0.1.0","sessions":3,"streams":1}

$ curl -s https://stream.example.com:10099/readyz
{"status":"ready"}

# tsserver 挂了或 query 权限不对时
$ curl -s -o /dev/null -w '%{http_code}\n' https://stream.example.com:10099/readyz
503
```

两者的区别很关键：

- `/healthz` 只反映**进程本身**是否活着，并顺带汇报当前会话数与流数。
  ServerQuery 挂掉不会让它变红 —— 这是有意的，进程活着就不该被重启。
  拿它做 liveness 不会因为 tsserver 短暂重启而引发级联重启。
- `/readyz` 反映**能否正常提供鉴权服务**。它对每个虚拟服务器执行
  `clientinfo clid=0`；返回 `CLIENT_NOT_FOUND`（clid 0 当然不存在）也算通过 ——
  这说明连接与权限都是好的。用它做告警来源。

Kubernetes 探针示例：

```yaml
livenessProbe:
  httpGet: { path: /healthz, port: 10099, scheme: HTTPS }
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /readyz, port: 10099, scheme: HTTPS }
  periodSeconds: 15
  failureThreshold: 3
```

### 10.1 启动日志应该长什么样

正常启动（dev 模式下）：

```
level=INFO msg="ts9-stream 启动中" version=0.1.0 config=/etc/ts9-stream/config.yaml
level=INFO msg="开始监听" addr=:10099 endpoint="wss://<host>/tssp/v1" modes=sfu,p2p servers=1
level=INFO msg="ServerQuery 自检通过"
```

需要留意的告警：

| 日志 | 含义 | 处理 |
|---|---|---|
| `已启用 runtime.dev_insecure` | 明文 ws + 可能自动生成密钥 | 生产环境必须改回 `false` |
| `未配置 TLS 证书` | 正在裸奔 | 配 `tls_cert`/`tls_key` 或确认在反代后面 |
| `自动生成了临时令牌密钥` | 重启后所有令牌失效 | 显式配置 `token_secret` |
| `ServerQuery 自检未通过` | 鉴权不可用 | 看后面的 `err=` 字段，对照 [§11](#11-排障) |

注意 ServerQuery 自检是**异步**的，失败**不会阻止服务启动**。这是有意的设计：
允许 tsserver 比 ts9-stream 晚启动。所以「服务起来了」不等于「能用了」，
判断可用性要看 `/readyz`。

---

## 11. 排障

### 11.1 对照表

| 现象 | 可能原因 | 检查方法 |
|---|---|---|
| 客户端连不上，浏览器/日志报 TLS 错误 | 证书域名不匹配 / 自签未被信任 | `openssl s_client -connect host:10099` |
| WebSocket **连上又立刻断开** | 客户端没声明 `tssp.v1` 子协议 | 见 [§11.2](#112-websocket-连上就断) |
| HTTP 426 Upgrade Required | 用普通 GET 访问了 `/tssp/v1` | 正常行为，该路径只接受 WS 升级 |
| `hello` 返回 `UNKNOWN_SERVER` | `server_addr` 没配或写法不一致 | 对照配置里的 `server_addr` 列表 |
| `hello` 返回 `QUERY_UNAVAILABLE` | tsserver 未启动 / query 端口不通 / flood ban | `-check`；查 `query_ip_allowlist` |
| `hello` 返回 `CLIENT_NOT_FOUND` | clid 已变（重连过）| 客户端重读 clid 后重试；正常抖动 |
| `hello` 返回 `IDENTITY_MISMATCH` | uid/cid 与 tsserver 不一致 | 多为刚切频道，见 `query_cache_ttl` |
| `hello` 返回 `NOT_ALLOWED` | 不在 `allow_server_groups` 或在 deny 里 | `servergrouplist` 核对组 ID |
| 大量 `RATE_LIMITED` | 触发 `hello_fail_max` | NAT 后多用户？调高该值 |
| 所有人一起被限流 | 在反代后面但没配 `trusted_proxies` | 见 [§7.5](#75-x-forwarded-for-与可信代理) |
| 协商成功但**画面全黑** | `public_ip` 未配 / UDP 端口没放通 | 见 [§11.3](#113-有信令没画面) |
| 观看者卡在第一帧 | PLI 未生效 | 确认 `media.pli_interval` 不为 0 |
| 订阅报 `CODEC_NOT_SUPPORTED` | 双方无共同编码 | 确认 `video_codecs` 含 H264 与 VP8 |
| 订阅报 `NOT_SAME_CHANNEL` | 观看者不在发布者频道 | 正常行为，先进频道 |
| 服务启动即退出 | 配置校验失败 | 看 stderr 的「启动失败:」 |

### 11.2 WebSocket 连上就断

TSSP v1 要求客户端在 `Sec-WebSocket-Protocol` 请求头里声明 `tssp.v1`。

底层 WebSocket 库在客户端未声明子协议时**仍会完成 101 握手**（只是响应里不带
`Sec-Websocket-Protocol` 头），服务端随后在信令层发现协商结果不是 `tssp.v1`，
以 `1002 protocol error` 关闭连接。

所以症状是「连上又立刻断」，而不是 HTTP 层的明确拒绝。用 curl 可以复现：

```bash
# 正确：返回 101，且响应带 Sec-Websocket-Protocol: tssp.v1
curl -i -N --http1.1 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Sec-WebSocket-Protocol: tssp.v1" \
  http://127.0.0.1:10099/tssp/v1

# 错误：也返回 101，但响应没有 Sec-Websocket-Protocol，随后被关闭
#（去掉上面最后一个 -H 即可复现）
```

排查客户端实现时，先确认这个头有没有发出去。

### 11.3 有信令没画面

这是 SFU 部署最常见的问题，按顺序排查：

1. **`media.public_ip` 配了吗？** 在 NAT / 云主机后面不配这项，pion 只会
   收集到内网候选，外部客户端拿到后永远连不通。这是首要嫌疑。
2. **UDP 端口范围放通了吗？** 检查 `media.udp_port_min/max` 与防火墙、
   云安全组规则是否一致。安全组常常被漏掉。
3. **端口范围够大吗？** 每路连接占用若干端口，范围太小会导致新订阅者无端口可用。
4. **发布者真的在推流吗？** 看 `/healthz` 的 `streams` 计数是否 > 0。
5. 上述都对但仍不通时，暂时改用 `p2p` 模式对比：如果 P2P 通而 SFU 不通，
   问题一定在服务端网络配置；两者都不通则更可能是客户端侧的采集或编码问题。

### 11.4 收集诊断信息

```bash
# 1. 配置与连通性
ts9-stream -config /etc/ts9-stream/config.yaml -check

# 2. 版本
ts9-stream -version

# 3. 运行时状态
curl -s https://stream.example.com:10099/healthz
curl -s https://stream.example.com:10099/readyz

# 4. 详细日志（临时提到 debug，不用改配置文件）
TS9STREAM_LOG_LEVEL=debug ts9-stream -config /etc/ts9-stream/config.yaml
```

`debug` 级别会额外打印：会话建立/清理、WebSocket 升级失败、hello 超时、读写失败、
心跳失败、客户端质量上报，以及 SFU 侧的 PeerConnection 状态变化、轨道转发结束、
PLI 发送失败。它**不会**打印令牌、密码或 SDP 内容。

如果需要逐条比对信令消息与规范，客户端侧的日志比服务端更合适 —— 服务端目前
不逐条记录请求类型（这是有意的，避免高并发下日志量爆炸）。

---

## 12. 错误码

客户端收到的错误码及其含义见 [tssp-v1.md 第 7 节](../protocol/tssp-v1.md#7-错误码)，
共 21 个。与**部署配置**直接相关的是这几个：

| code | 服务端侧要检查什么 |
|---|---|
| `UNKNOWN_SERVER` | `servers[].server_addr` 是否包含客户端上报的地址 |
| `QUERY_UNAVAILABLE` | tsserver 是否在跑、query 凭据、`query_ip_allowlist` |
| `NOT_ALLOWED` | `access.allow_server_groups` / `deny_server_groups` |
| `RATE_LIMITED` | `limits.hello_fail_*` 是否对 NAT 场景过严 |
| `MODE_NOT_SUPPORTED` | `modes` 是否包含客户端请求的模式 |
| `CODEC_NOT_SUPPORTED` | `media.video_codecs` / `audio_codecs` |
| `TOO_MANY_STREAMS` | `limits.max_streams_per_channel` |
| `TOO_MANY_VIEWERS` | `limits.max_viewers_per_stream` |
| `ALREADY_PUBLISHING` | `limits.max_streams_per_client`（默认 1） |

其余错误码（`TOKEN_*`、`STREAM_NOT_FOUND`、`SIGNALING_FAILED` 等）是协议层或
客户端行为问题，与部署配置无关。

---

## 13. 已知限制

1. **与官方 TS6 客户端不互通。** 屏幕共享用的是本项目自定义的 TSSP v1 协议，
   不是官方私有 protobuf 信令。官方客户端看不到本项目共享的画面，反之亦然。
   这是「不逆向 tsserver」这一约束的直接后果，无法在不违反其许可证的前提下解决。

2. **证书不支持热重载。** 证书在启动时读取一次。续期后必须重启服务，
   重启会中断正在进行的共享。见 [§6.1](#61-lets-encrypt推荐)。

3. **ServerQuery SSH 不校验主机密钥。** 使用 `InsecureIgnoreHostKey`，
   因为 tsserver 的 query 主机密钥首次启动随机生成、无可信分发渠道。
   代价是 query 端点必须限制在可信网络内。

4. **`TS9STREAM_QUERY_USER` / `QUERY_PASSWORD` 只作用于 `servers[0]`。**
   多虚拟服务器部署时其余条目的凭据必须写在配置文件里。

5. **SFU 不转码。** 只重写 SSRC 与 payload type。因此发布者与订阅者必须
   协商出同一种视频编码，否则订阅失败（`CODEC_NOT_SUPPORTED`）。
   好处是 CPU 占用极低，坏处是编码能力差异大的客户端之间可能无法互通。

6. **`go test -race` 未在当前开发机上执行过。** 该项目 `CGO_ENABLED=0` 且开发机
   未安装 C 编译器，而 Go 的竞态检测器依赖 cgo。并发正确性目前由
   `go test -count=3` 的重复运行与设计审查保证。有 C 工具链的环境**建议补跑**：
   ```bash
   CGO_ENABLED=1 go test -race -count=1 ./...
   ```

7. **P2P 模式的媒体质量不由服务端控制。** `limits.max_bitrate_kbps` 在 P2P 下
   仅作为信令层的建议值下发，实际码率由两端协商，服务端无法强制。

---

## 14. 升级与回滚

服务是单个无状态二进制，升级就是替换文件 + 重启：

```bash
systemctl stop ts9-stream
install -o root -g root -m 755 ts9-stream.new /usr/local/bin/ts9-stream
ts9-stream -config /etc/ts9-stream/config.yaml -check   # 先验证新版本能读旧配置
systemctl start ts9-stream
curl -s https://stream.example.com:10099/readyz
```

会话状态全在内存里，重启会断开所有连接与正在进行的共享；客户端会收到 `bye`
并自动重连。**没有需要迁移的持久化数据**，回滚只需换回旧二进制。

滚动升级（多实例 + 负载均衡）需要注意：TSSP 会话与 SFU 媒体状态是**实例本地**的，
同一条流的发布者与订阅者必须落在同一个实例上。因此负载均衡必须做基于
`server_addr` 或频道的**会话粘性**，不能简单轮询。单实例部署不受此影响。
