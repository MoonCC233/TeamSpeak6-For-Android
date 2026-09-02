// Package config 负责 ts9-stream 的配置加载、环境变量覆盖与校验。
package config

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// EnvPrefix 是所有环境变量覆盖的前缀。
const EnvPrefix = "TS9STREAM_"

// QueryProtocol 表示 ServerQuery 的传输方式。
type QueryProtocol string

const (
	// QueryRaw 是明文 telnet 传输，默认端口 10011。
	// 注意：TeamSpeak 6 服务端已移除该端点，仅 TS3 服务端可用。
	QueryRaw QueryProtocol = "raw"
	// QuerySSH 是加密传输，默认端口 10022，也是本服务的默认值。
	// TS6 只提供 ssh / http / https 三种 query 端点。
	QuerySSH QueryProtocol = "ssh"
)

// Mode 是屏幕共享的媒体模式。
type Mode string

const (
	// ModeSFU 由服务端转发媒体。
	ModeSFU Mode = "sfu"
	// ModeP2P 仅由服务端中转信令，媒体点对点直连。
	ModeP2P Mode = "p2p"
)

// Config 是服务的完整配置。
type Config struct {
	Listen  Listen         `yaml:"listen"`
	Log     Log            `yaml:"log"`
	Auth    Auth           `yaml:"auth"`
	Servers []VirtualSrv   `yaml:"servers"`
	ICE     ICE            `yaml:"ice"`
	Limits  Limits         `yaml:"limits"`
	Modes   []Mode         `yaml:"modes"`
	Access  Access         `yaml:"access"`
	Media   Media          `yaml:"media"`
	Runtime RuntimeOptions `yaml:"runtime"`
}

// Listen 描述 HTTP/WebSocket 监听与 TLS 配置。
type Listen struct {
	Addr     string `yaml:"addr"`
	TLSCert  string `yaml:"tls_cert"`
	TLSKey   string `yaml:"tls_key"`
	BasePath string `yaml:"base_path"`
	// TrustedProxies 列出可信反向代理的 IP 或 CIDR。
	// 只有来自这些地址的请求，其 X-Forwarded-For 才会被用于识别客户端 IP；
	// 留空（默认）表示完全忽略该头，直接用 TCP 对端地址。
	// 这很重要：若无条件信任 XFF，任何直连客户端都能伪造它来绕过 hello 限流。
	TrustedProxies []string `yaml:"trusted_proxies"`

	// trustedNets 是 TrustedProxies 解析后的形式，由 Validate 填充。
	trustedNets []netip.Prefix `yaml:"-"`
}

// TrustsProxy 判断给定的对端地址是否属于可信代理。
// addr 可以带端口，也可以是裸 IP。
func (l *Listen) TrustsProxy(addr string) bool {
	if len(l.trustedNets) == 0 {
		return false
	}
	ip, ok := parsePeerIP(addr)
	if !ok {
		return false
	}
	for _, n := range l.trustedNets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// parsePeerIP 从 "host:port" 或裸 IP 中取出可比较的 netip.Addr。
func parsePeerIP(addr string) (netip.Addr, bool) {
	if ap, err := netip.ParseAddrPort(addr); err == nil {
		return ap.Addr().Unmap(), true
	}
	if host, _, err := net.SplitHostPort(addr); err == nil {
		addr = host
	}
	ip, err := netip.ParseAddr(addr)
	if err != nil {
		return netip.Addr{}, false
	}
	return ip.Unmap(), true
}

// Log 是日志配置。
type Log struct {
	Level  string `yaml:"level"`
	Format string `yaml:"format"`
}

// Auth 是鉴权与令牌配置。
type Auth struct {
	TokenSecret string        `yaml:"token_secret"`
	TokenTTL    time.Duration `yaml:"token_ttl"`
	// RenewLeeway 是提前发送 token_expiring 事件的时间。
	RenewLeeway time.Duration `yaml:"renew_leeway"`
	// QueryCacheTTL 是 ServerQuery 查询结果缓存时间，用于防止放大攻击。
	QueryCacheTTL time.Duration `yaml:"query_cache_ttl"`
	// GeneratedSecret 由 Validate 置位，表示密钥是开发模式下临时生成的。
	GeneratedSecret bool `yaml:"-"`
}

// VirtualSrv 把客户端上报的 server_addr 映射到一个可查询的虚拟服务器。
type VirtualSrv struct {
	// ServerAddr 是客户端连接 tsserver 时使用的地址（host:port），用于匹配。
	ServerAddr []string `yaml:"server_addr"`
	// VirtualPort 是虚拟服务器的语音端口，用于 ServerQuery 的 use port=N。
	VirtualPort int `yaml:"virtual_port"`

	QueryProtocol QueryProtocol `yaml:"query_protocol"`
	QueryHost     string        `yaml:"query_host"`
	QueryPort     int           `yaml:"query_port"`
	QueryUser     string        `yaml:"query_user"`
	QueryPassword string        `yaml:"query_password"`
	// QueryTimeout 是单条查询的超时。
	QueryTimeout time.Duration `yaml:"query_timeout"`
}

// ICEServer 是下发给客户端的一个 ICE 服务器条目。
type ICEServer struct {
	URLs       []string `yaml:"urls"`
	Username   string   `yaml:"username"`
	Credential string   `yaml:"credential"`
}

// ICE 描述 STUN/TURN 配置。TURN 支持 coturn 的 REST 风格短时凭据。
type ICE struct {
	STUNURLs []string `yaml:"stun_urls"`
	TURNURLs []string `yaml:"turn_urls"`
	// TURNStaticAuthSecret 启用短时凭据签发；为空则使用 TURNUsername/TURNPassword。
	TURNStaticAuthSecret string        `yaml:"turn_static_auth_secret"`
	TURNCredentialTTL    time.Duration `yaml:"turn_credential_ttl"`
	TURNUsername         string        `yaml:"turn_username"`
	TURNPassword         string        `yaml:"turn_password"`
	// Static 是直接原样下发的额外条目。
	Static []ICEServer `yaml:"static"`
}

// Limits 是各类资源上限与速率限制。
type Limits struct {
	MaxBitrateKbps       int           `yaml:"max_bitrate_kbps"`
	MaxStreamsPerChannel int           `yaml:"max_streams_per_channel"`
	MaxViewersPerStream  int           `yaml:"max_viewers_per_stream"`
	MaxStreamsPerClient  int           `yaml:"max_streams_per_client"`
	HelloTimeout         time.Duration `yaml:"hello_timeout"`
	// HelloFailWindow 与 HelloFailMax 构成按 IP 的失败速率限制。
	HelloFailWindow time.Duration `yaml:"hello_fail_window"`
	HelloFailMax    int           `yaml:"hello_fail_max"`
	HelloBanTime    time.Duration `yaml:"hello_ban_time"`
	// MaxMessageBytes 是单条 WebSocket 消息上限。
	MaxMessageBytes int64 `yaml:"max_message_bytes"`
	// MaxSessions 是并发会话上限，0 表示不限。
	MaxSessions int `yaml:"max_sessions"`
	// NegotiationTimeout 是媒体协商未完成的超时时间。
	NegotiationTimeout time.Duration `yaml:"negotiation_timeout"`
}

// Access 是可选的服务器组白/黑名单。
type Access struct {
	// AllowServerGroups 非空时，客户端必须至少属于其中一个组。
	AllowServerGroups []int `yaml:"allow_server_groups"`
	// DenyServerGroups 中的组一律拒绝，优先级高于白名单。
	DenyServerGroups []int `yaml:"deny_server_groups"`
}

// Media 描述编解码与心跳等媒体层参数。
type Media struct {
	VideoCodecs []string      `yaml:"video_codecs"`
	AudioCodecs []string      `yaml:"audio_codecs"`
	PLIInterval time.Duration `yaml:"pli_interval"`
	// UDPPortMin/UDPPortMax 限制 ICE 使用的本地 UDP 端口范围，便于配置防火墙。
	UDPPortMin uint16 `yaml:"udp_port_min"`
	UDPPortMax uint16 `yaml:"udp_port_max"`
	// PublicIP 在 NAT 后部署时用于 1:1 NAT 候选映射。
	PublicIP string `yaml:"public_ip"`
}

// RuntimeOptions 是运行期开关。
type RuntimeOptions struct {
	// DevInsecure 允许明文 ws:// 与自动生成令牌密钥，仅用于本地开发。
	DevInsecure bool `yaml:"dev_insecure"`
	// PingInterval 是服务端 WebSocket 心跳间隔。
	PingInterval time.Duration `yaml:"ping_interval"`
	// ReadTimeout 是无任何流量时判定连接失效的时间。
	ReadTimeout time.Duration `yaml:"read_timeout"`
	// ShutdownGrace 是优雅退出的最长等待时间。
	ShutdownGrace time.Duration `yaml:"shutdown_grace"`
}

// Default 返回带有全部默认值的配置。
func Default() Config {
	return Config{
		Listen: Listen{
			Addr:     ":10099",
			BasePath: "/tssp/v1",
		},
		Log: Log{Level: "info", Format: "text"},
		Auth: Auth{
			TokenTTL:      10 * time.Minute,
			RenewLeeway:   2 * time.Minute,
			QueryCacheTTL: 3 * time.Second,
		},
		ICE: ICE{
			TURNCredentialTTL: 10 * time.Minute,
		},
		Limits: Limits{
			MaxBitrateKbps:       4000,
			MaxStreamsPerChannel: 4,
			MaxViewersPerStream:  16,
			MaxStreamsPerClient:  1,
			HelloTimeout:         10 * time.Second,
			HelloFailWindow:      5 * time.Minute,
			HelloFailMax:         10,
			HelloBanTime:         5 * time.Minute,
			MaxMessageBytes:      256 * 1024,
			NegotiationTimeout:   30 * time.Second,
		},
		Modes: []Mode{ModeSFU, ModeP2P},
		Media: Media{
			VideoCodecs: []string{"H264", "VP8"},
			AudioCodecs: []string{"opus"},
			PLIInterval: 3 * time.Second,
		},
		Runtime: RuntimeOptions{
			PingInterval:  20 * time.Second,
			ReadTimeout:   60 * time.Second,
			ShutdownGrace: 10 * time.Second,
		},
	}
}

// Load 读取 YAML 配置文件（path 为空则只用默认值与环境变量），
// 依次应用默认值、文件、环境变量覆盖，最后做校验。
func Load(path string) (Config, error) {
	cfg := Default()

	if path != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			return Config{}, fmt.Errorf("读取配置文件 %s: %w", path, err)
		}
		if err := yaml.Unmarshal(raw, &cfg); err != nil {
			return Config{}, fmt.Errorf("解析配置文件 %s: %w", path, err)
		}
	}

	applyEnv(&cfg)

	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func applyEnv(cfg *Config) {
	if v, ok := lookupEnv("LISTEN_ADDR"); ok {
		cfg.Listen.Addr = v
	}
	if v, ok := lookupEnv("TLS_CERT"); ok {
		cfg.Listen.TLSCert = v
	}
	if v, ok := lookupEnv("TLS_KEY"); ok {
		cfg.Listen.TLSKey = v
	}
	if v, ok := lookupEnv("BASE_PATH"); ok {
		cfg.Listen.BasePath = v
	}
	if v, ok := lookupEnv("LOG_LEVEL"); ok {
		cfg.Log.Level = v
	}
	if v, ok := lookupEnv("LOG_FORMAT"); ok {
		cfg.Log.Format = v
	}
	if v, ok := lookupEnv("TOKEN_SECRET"); ok {
		cfg.Auth.TokenSecret = v
	}
	if v, ok := lookupEnv("TOKEN_TTL"); ok {
		if d, err := time.ParseDuration(v); err == nil {
			cfg.Auth.TokenTTL = d
		}
	}
	if v, ok := lookupEnv("TURN_STATIC_AUTH_SECRET"); ok {
		cfg.ICE.TURNStaticAuthSecret = v
	}
	if v, ok := lookupEnv("DEV_INSECURE"); ok {
		if b, err := strconv.ParseBool(v); err == nil {
			cfg.Runtime.DevInsecure = b
		}
	}
	if v, ok := lookupEnv("MAX_BITRATE_KBPS"); ok {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.Limits.MaxBitrateKbps = n
		}
	}
	if v, ok := lookupEnv("PUBLIC_IP"); ok {
		cfg.Media.PublicIP = v
	}
	// 单服务器场景下的便捷覆盖，避免为一个虚拟服务器写完整 YAML。
	if v, ok := lookupEnv("QUERY_PASSWORD"); ok && len(cfg.Servers) > 0 {
		cfg.Servers[0].QueryPassword = v
	}
	if v, ok := lookupEnv("QUERY_USER"); ok && len(cfg.Servers) > 0 {
		cfg.Servers[0].QueryUser = v
	}
}

func lookupEnv(suffix string) (string, bool) {
	v, ok := os.LookupEnv(EnvPrefix + suffix)
	if !ok {
		return "", false
	}
	v = strings.TrimSpace(v)
	if v == "" {
		return "", false
	}
	return v, true
}

// ErrTokenSecretMissing 表示生产模式下缺少令牌密钥。
var ErrTokenSecretMissing = errors.New("缺少 token_secret：请设置 " + EnvPrefix + "TOKEN_SECRET 或在配置中填写 auth.token_secret（或开启 runtime.dev_insecure 仅用于开发）")

// Validate 校验配置并补齐可推导的默认值。
// 在 dev_insecure 模式下会为缺失的令牌密钥生成随机值，调用方应据此打印警告。
func (c *Config) Validate() error {
	if c.Listen.Addr == "" {
		c.Listen.Addr = ":10099"
	}
	if _, _, err := net.SplitHostPort(c.Listen.Addr); err != nil {
		return fmt.Errorf("listen.addr %q 非法: %w", c.Listen.Addr, err)
	}
	if c.Listen.BasePath == "" {
		c.Listen.BasePath = "/tssp/v1"
	}
	if !strings.HasPrefix(c.Listen.BasePath, "/") {
		return fmt.Errorf("listen.base_path 必须以 / 开头，当前为 %q", c.Listen.BasePath)
	}

	c.Listen.trustedNets = nil
	for i, entry := range c.Listen.TrustedProxies {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if pfx, err := netip.ParsePrefix(entry); err == nil {
			c.Listen.trustedNets = append(c.Listen.trustedNets, pfx.Masked())
			continue
		}
		ip, err := netip.ParseAddr(entry)
		if err != nil {
			return fmt.Errorf("listen.trusted_proxies[%d] %q 既不是 IP 也不是 CIDR", i, entry)
		}
		ip = ip.Unmap()
		c.Listen.trustedNets = append(c.Listen.trustedNets, netip.PrefixFrom(ip, ip.BitLen()))
	}

	tlsConfigured := c.Listen.TLSCert != "" && c.Listen.TLSKey != ""
	if !tlsConfigured {
		if (c.Listen.TLSCert == "") != (c.Listen.TLSKey == "") {
			return errors.New("listen.tls_cert 与 listen.tls_key 必须同时配置")
		}
		if !c.Runtime.DevInsecure {
			return errors.New("未配置 TLS：请提供 listen.tls_cert 与 listen.tls_key，或开启 runtime.dev_insecure 仅用于开发")
		}
	}

	if c.Auth.TokenSecret == "" {
		if !c.Runtime.DevInsecure {
			return ErrTokenSecretMissing
		}
		buf := make([]byte, 32)
		if _, err := rand.Read(buf); err != nil {
			return fmt.Errorf("生成临时 token_secret 失败: %w", err)
		}
		c.Auth.TokenSecret = hex.EncodeToString(buf)
		c.Auth.GeneratedSecret = true
	}
	if c.Auth.TokenTTL <= 0 {
		c.Auth.TokenTTL = 10 * time.Minute
	}
	if c.Auth.RenewLeeway <= 0 || c.Auth.RenewLeeway >= c.Auth.TokenTTL {
		c.Auth.RenewLeeway = c.Auth.TokenTTL / 5
	}
	if c.Auth.QueryCacheTTL < 0 {
		c.Auth.QueryCacheTTL = 3 * time.Second
	}

	if len(c.Modes) == 0 {
		c.Modes = []Mode{ModeSFU, ModeP2P}
	}
	for _, m := range c.Modes {
		if m != ModeSFU && m != ModeP2P {
			return fmt.Errorf("modes 含非法值 %q，仅支持 sfu 与 p2p", m)
		}
	}

	if len(c.Servers) == 0 {
		return errors.New("servers 不能为空：至少配置一个虚拟服务器映射")
	}
	seen := make(map[string]struct{})
	for i := range c.Servers {
		s := &c.Servers[i]
		if len(s.ServerAddr) == 0 {
			return fmt.Errorf("servers[%d].server_addr 不能为空", i)
		}
		for j, addr := range s.ServerAddr {
			norm, err := NormalizeServerAddr(addr)
			if err != nil {
				return fmt.Errorf("servers[%d].server_addr[%d] %q 非法: %w", i, j, addr, err)
			}
			if _, dup := seen[norm]; dup {
				return fmt.Errorf("servers[%d].server_addr[%d] %q 与其他条目重复", i, j, addr)
			}
			seen[norm] = struct{}{}
			s.ServerAddr[j] = norm
		}
		if s.QueryProtocol == "" {
			// TS6 已移除明文 raw 端点，因此默认走 ssh。
			s.QueryProtocol = QuerySSH
		}
		if s.QueryProtocol != QueryRaw && s.QueryProtocol != QuerySSH {
			return fmt.Errorf("servers[%d].query_protocol %q 非法，仅支持 raw 与 ssh", i, s.QueryProtocol)
		}
		if s.QueryHost == "" {
			host, _, err := net.SplitHostPort(s.ServerAddr[0])
			if err != nil {
				return fmt.Errorf("servers[%d] 未配置 query_host 且无法从 server_addr 推导: %w", i, err)
			}
			s.QueryHost = host
		}
		if s.QueryPort == 0 {
			if s.QueryProtocol == QueryRaw {
				s.QueryPort = 10011
			} else {
				s.QueryPort = 10022
			}
		}
		if s.QueryPort < 1 || s.QueryPort > 65535 {
			return fmt.Errorf("servers[%d].query_port %d 越界", i, s.QueryPort)
		}
		if s.QueryUser == "" {
			s.QueryUser = "serveradmin"
		}
		if s.QueryPassword == "" {
			return fmt.Errorf("servers[%d].query_password 不能为空（可用环境变量 %sQUERY_PASSWORD 提供）", i, EnvPrefix)
		}
		if s.VirtualPort == 0 {
			_, portStr, err := net.SplitHostPort(s.ServerAddr[0])
			if err != nil {
				return fmt.Errorf("servers[%d] 未配置 virtual_port 且无法从 server_addr 推导: %w", i, err)
			}
			p, err := strconv.Atoi(portStr)
			if err != nil {
				return fmt.Errorf("servers[%d] server_addr 端口 %q 非法: %w", i, portStr, err)
			}
			s.VirtualPort = p
		}
		if s.VirtualPort < 1 || s.VirtualPort > 65535 {
			return fmt.Errorf("servers[%d].virtual_port %d 越界", i, s.VirtualPort)
		}
		if s.QueryTimeout <= 0 {
			s.QueryTimeout = 5 * time.Second
		}
	}

	if c.ICE.TURNCredentialTTL <= 0 {
		c.ICE.TURNCredentialTTL = 10 * time.Minute
	}
	if len(c.ICE.TURNURLs) > 0 && c.ICE.TURNStaticAuthSecret == "" && c.ICE.TURNUsername == "" {
		return errors.New("配置了 ice.turn_urls 但既无 turn_static_auth_secret 也无 turn_username")
	}

	if c.Limits.MaxBitrateKbps <= 0 {
		c.Limits.MaxBitrateKbps = 4000
	}
	if c.Limits.MaxStreamsPerChannel <= 0 {
		c.Limits.MaxStreamsPerChannel = 4
	}
	if c.Limits.MaxViewersPerStream <= 0 {
		c.Limits.MaxViewersPerStream = 16
	}
	if c.Limits.MaxStreamsPerClient <= 0 {
		c.Limits.MaxStreamsPerClient = 1
	}
	if c.Limits.HelloTimeout <= 0 {
		c.Limits.HelloTimeout = 10 * time.Second
	}
	if c.Limits.HelloFailWindow <= 0 {
		c.Limits.HelloFailWindow = 5 * time.Minute
	}
	if c.Limits.HelloFailMax <= 0 {
		c.Limits.HelloFailMax = 10
	}
	if c.Limits.HelloBanTime <= 0 {
		c.Limits.HelloBanTime = 5 * time.Minute
	}
	if c.Limits.MaxMessageBytes <= 0 {
		c.Limits.MaxMessageBytes = 256 * 1024
	}
	if c.Limits.NegotiationTimeout <= 0 {
		c.Limits.NegotiationTimeout = 30 * time.Second
	}

	if len(c.Media.VideoCodecs) == 0 {
		c.Media.VideoCodecs = []string{"H264", "VP8"}
	}
	for i, codec := range c.Media.VideoCodecs {
		up := strings.ToUpper(strings.TrimSpace(codec))
		if up != "H264" && up != "VP8" {
			return fmt.Errorf("media.video_codecs[%d] %q 不支持，仅支持 H264 与 VP8", i, codec)
		}
		c.Media.VideoCodecs[i] = up
	}
	if len(c.Media.AudioCodecs) == 0 {
		c.Media.AudioCodecs = []string{"opus"}
	}
	if c.Media.PLIInterval <= 0 {
		c.Media.PLIInterval = 3 * time.Second
	}
	if (c.Media.UDPPortMin == 0) != (c.Media.UDPPortMax == 0) {
		return errors.New("media.udp_port_min 与 media.udp_port_max 必须同时配置")
	}
	if c.Media.UDPPortMin != 0 && c.Media.UDPPortMin > c.Media.UDPPortMax {
		return fmt.Errorf("media.udp_port_min %d 大于 media.udp_port_max %d", c.Media.UDPPortMin, c.Media.UDPPortMax)
	}
	if c.Media.PublicIP != "" && net.ParseIP(c.Media.PublicIP) == nil {
		return fmt.Errorf("media.public_ip %q 不是合法 IP", c.Media.PublicIP)
	}

	if c.Runtime.PingInterval <= 0 {
		c.Runtime.PingInterval = 20 * time.Second
	}
	if c.Runtime.ReadTimeout <= c.Runtime.PingInterval {
		c.Runtime.ReadTimeout = 3 * c.Runtime.PingInterval
	}
	if c.Runtime.ShutdownGrace <= 0 {
		c.Runtime.ShutdownGrace = 10 * time.Second
	}

	return nil
}

// TLSEnabled 表示是否配置了证书。
func (c *Config) TLSEnabled() bool {
	return c.Listen.TLSCert != "" && c.Listen.TLSKey != ""
}

// ModeEnabled 判断某模式是否启用。
func (c *Config) ModeEnabled(m Mode) bool {
	for _, v := range c.Modes {
		if v == m {
			return true
		}
	}
	return false
}

// ModeStrings 返回启用模式的字符串形式，用于 hello 响应。
func (c *Config) ModeStrings() []string {
	out := make([]string, 0, len(c.Modes))
	for _, m := range c.Modes {
		out = append(out, string(m))
	}
	return out
}

// FindServer 按客户端上报的 server_addr 查找虚拟服务器映射。
func (c *Config) FindServer(serverAddr string) (*VirtualSrv, bool) {
	norm, err := NormalizeServerAddr(serverAddr)
	if err != nil {
		return nil, false
	}
	for i := range c.Servers {
		for _, addr := range c.Servers[i].ServerAddr {
			if addr == norm {
				return &c.Servers[i], true
			}
		}
	}
	return nil, false
}

// NormalizeServerAddr 归一化 host:port：补默认端口 9987、去空白、host 转小写。
func NormalizeServerAddr(addr string) (string, error) {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return "", errors.New("地址为空")
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		// 没有端口时补默认语音端口。
		host = addr
		port = "9987"
		switch {
		case strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]"):
			// 带方括号的裸 IPv6 字面量：先剥掉括号，最后由 JoinHostPort 统一加回，
			// 否则会拼出 [[::1]]:9987 这样的非法地址。
			host = host[1 : len(host)-1]
			if net.ParseIP(host) == nil {
				return "", fmt.Errorf("IPv6 字面量 %q 非法", addr)
			}
		case strings.ContainsAny(host, "[]"):
			return "", fmt.Errorf("方括号不匹配: %q", addr)
		case strings.Contains(host, ":"):
			return "", fmt.Errorf("疑似 IPv6 地址缺少方括号: %q", addr)
		}
	}
	host = strings.ToLower(strings.TrimSpace(host))
	if host == "" {
		return "", errors.New("主机名为空")
	}
	// IP 字面量统一成规范写法，避免 IPv6 的多种等价拼写导致 FindServer 匹配不上。
	if ip := net.ParseIP(host); ip != nil {
		host = ip.String()
	}
	p, err := strconv.Atoi(strings.TrimSpace(port))
	if err != nil || p < 1 || p > 65535 {
		return "", fmt.Errorf("端口 %q 非法", port)
	}
	return net.JoinHostPort(host, strconv.Itoa(p)), nil
}
