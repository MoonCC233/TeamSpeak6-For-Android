package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// minimalServers 返回一份能通过校验的最小 servers 配置。
func minimalServers() []VirtualSrv {
	return []VirtualSrv{{
		ServerAddr:    []string{"127.0.0.1:9987"},
		QueryPassword: "pw",
	}}
}

func TestValidateRequiresTLSOutsideDevMode(t *testing.T) {
	c := Default()
	c.Servers = minimalServers()
	c.Auth.TokenSecret = "secret"

	err := c.Validate()
	if err == nil {
		t.Fatal("非开发模式缺少 TLS 应报错")
	}
	if !strings.Contains(err.Error(), "TLS") {
		t.Errorf("错误信息应提到 TLS: %v", err)
	}
}

func TestValidateRejectsHalfConfiguredTLS(t *testing.T) {
	c := Default()
	c.Servers = minimalServers()
	c.Auth.TokenSecret = "secret"
	c.Listen.TLSCert = "cert.pem"

	err := c.Validate()
	if err == nil || !strings.Contains(err.Error(), "同时配置") {
		t.Fatalf("只配证书不配私钥应报错，得到 %v", err)
	}
}

func TestValidateRequiresTokenSecret(t *testing.T) {
	c := Default()
	c.Servers = minimalServers()
	c.Listen.TLSCert = "cert.pem"
	c.Listen.TLSKey = "key.pem"

	if err := c.Validate(); err != ErrTokenSecretMissing {
		t.Fatalf("缺少 token_secret 应返回 ErrTokenSecretMissing，得到 %v", err)
	}
}

func TestValidateDevModeGeneratesSecret(t *testing.T) {
	c := Default()
	c.Servers = minimalServers()
	c.Runtime.DevInsecure = true

	if err := c.Validate(); err != nil {
		t.Fatalf("开发模式应通过校验: %v", err)
	}
	if !c.Auth.GeneratedSecret {
		t.Error("应标记 GeneratedSecret")
	}
	if len(c.Auth.TokenSecret) != 64 {
		t.Errorf("生成的密钥长度 = %d, 期望 64 个十六进制字符", len(c.Auth.TokenSecret))
	}

	// 每次生成都应不同，避免固定密钥被复用。
	c2 := Default()
	c2.Servers = minimalServers()
	c2.Runtime.DevInsecure = true
	if err := c2.Validate(); err != nil {
		t.Fatal(err)
	}
	if c2.Auth.TokenSecret == c.Auth.TokenSecret {
		t.Error("两次生成的临时密钥不应相同")
	}
}

func TestValidateKeepsExplicitSecret(t *testing.T) {
	c := Default()
	c.Servers = minimalServers()
	c.Runtime.DevInsecure = true
	c.Auth.TokenSecret = "explicit"

	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if c.Auth.GeneratedSecret {
		t.Error("显式配置的密钥不应标记为生成")
	}
	if c.Auth.TokenSecret != "explicit" {
		t.Errorf("密钥被改写为 %q", c.Auth.TokenSecret)
	}
}

func TestValidateRequiresServers(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true

	err := c.Validate()
	if err == nil || !strings.Contains(err.Error(), "servers") {
		t.Fatalf("servers 为空应报错，得到 %v", err)
	}
}

func TestValidateRequiresQueryPassword(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{{ServerAddr: []string{"127.0.0.1:9987"}}}

	err := c.Validate()
	if err == nil || !strings.Contains(err.Error(), "query_password") {
		t.Fatalf("缺少 query_password 应报错，得到 %v", err)
	}
}

func TestValidateDerivesQueryDefaults(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()

	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	s := c.Servers[0]
	if s.QueryProtocol != QuerySSH {
		t.Errorf("query_protocol = %q, 期望 ssh", s.QueryProtocol)
	}
	if s.QueryHost != "127.0.0.1" {
		t.Errorf("query_host = %q, 应从 server_addr 推导", s.QueryHost)
	}
	if s.QueryPort != 10022 {
		t.Errorf("query_port = %d, ssh 默认应为 10022", s.QueryPort)
	}
	if s.QueryUser != "serveradmin" {
		t.Errorf("query_user = %q", s.QueryUser)
	}
	if s.VirtualPort != 9987 {
		t.Errorf("virtual_port = %d, 应从 server_addr 端口推导", s.VirtualPort)
	}
	if s.QueryTimeout != 5*time.Second {
		t.Errorf("query_timeout = %v", s.QueryTimeout)
	}
}

func TestValidateSSHDefaultPort(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{{
		ServerAddr:    []string{"127.0.0.1:9987"},
		QueryProtocol: QuerySSH,
		QueryPassword: "pw",
	}}
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if c.Servers[0].QueryPort != 10022 {
		t.Errorf("ssh 默认端口 = %d, 期望 10022", c.Servers[0].QueryPort)
	}
}

func TestValidateRawDefaultPort(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{{
		ServerAddr:    []string{"127.0.0.1:9987"},
		QueryProtocol: QueryRaw,
		QueryPassword: "pw",
	}}
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if c.Servers[0].QueryPort != 10011 {
		t.Errorf("raw 默认端口 = %d, 期望 10011", c.Servers[0].QueryPort)
	}
}

func TestValidateRejectsBadQueryProtocol(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{{
		ServerAddr:    []string{"127.0.0.1:9987"},
		QueryProtocol: "telnet",
		QueryPassword: "pw",
	}}
	if err := c.Validate(); err == nil {
		t.Fatal("非法 query_protocol 应报错")
	}
}

func TestValidateRejectsDuplicateServerAddr(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{
		{ServerAddr: []string{"127.0.0.1:9987"}, QueryPassword: "pw"},
		// 归一化后与上一条相同（省略端口 + 大小写差异）。
		{ServerAddr: []string{"127.0.0.1"}, QueryPassword: "pw", VirtualPort: 9987},
	}
	err := c.Validate()
	if err == nil || !strings.Contains(err.Error(), "重复") {
		t.Fatalf("重复地址应报错，得到 %v", err)
	}
}

func TestValidateNormalizesServerAddrInPlace(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{{
		ServerAddr:    []string{" TS.Example.COM "},
		QueryPassword: "pw",
	}}
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if got := c.Servers[0].ServerAddr[0]; got != "ts.example.com:9987" {
		t.Errorf("归一化结果 = %q", got)
	}
}

func TestValidateRejectsBadListenAddr(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Listen.Addr = "not-an-addr"
	if err := c.Validate(); err == nil {
		t.Fatal("非法监听地址应报错")
	}
}

func TestValidateRejectsBasePathWithoutSlash(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Listen.BasePath = "tssp/v1"
	if err := c.Validate(); err == nil {
		t.Fatal("base_path 必须以 / 开头")
	}
}

func TestValidateParsesTrustedProxies(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Listen.TrustedProxies = []string{"127.0.0.1", " 10.0.0.0/8 ", "", "::1"}
	if err := c.Validate(); err != nil {
		t.Fatalf("合法的 trusted_proxies 应通过: %v", err)
	}

	trusted := []string{"127.0.0.1:5555", "127.0.0.1", "10.9.8.7:1", "[::1]:443"}
	for _, addr := range trusted {
		if !c.Listen.TrustsProxy(addr) {
			t.Errorf("%q 应被视为可信代理", addr)
		}
	}
	untrusted := []string{"11.0.0.1:1", "203.0.113.5", "not-an-addr", ""}
	for _, addr := range untrusted {
		if c.Listen.TrustsProxy(addr) {
			t.Errorf("%q 不应被视为可信代理", addr)
		}
	}
}

func TestValidateRejectsBadTrustedProxy(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Listen.TrustedProxies = []string{"proxy.example.com"}
	if err := c.Validate(); err == nil {
		t.Fatal("域名形式的 trusted_proxies 应报错")
	}
}

func TestTrustsProxyDefaultsToNoTrust(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	if err := c.Validate(); err != nil {
		t.Fatalf("默认配置应通过校验: %v", err)
	}
	if c.Listen.TrustsProxy("127.0.0.1:1234") {
		t.Fatal("未配置 trusted_proxies 时不应信任任何来源")
	}
}

func TestValidateResetsTrustedProxiesOnRevalidate(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Listen.TrustedProxies = []string{"10.0.0.1"}
	if err := c.Validate(); err != nil {
		t.Fatalf("首次校验失败: %v", err)
	}
	c.Listen.TrustedProxies = []string{"10.0.0.2"}
	if err := c.Validate(); err != nil {
		t.Fatalf("二次校验失败: %v", err)
	}
	if c.Listen.TrustsProxy("10.0.0.1") {
		t.Fatal("重新校验后旧条目应被清除")
	}
	if !c.Listen.TrustsProxy("10.0.0.2") {
		t.Fatal("重新校验后新条目应生效")
	}
}

func TestValidateRejectsBadMode(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Modes = []Mode{"quic"}
	if err := c.Validate(); err == nil {
		t.Fatal("非法 mode 应报错")
	}
}

func TestValidateRejectsBadVideoCodec(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Media.VideoCodecs = []string{"AV1"}
	if err := c.Validate(); err == nil {
		t.Fatal("不支持的编解码应报错")
	}
}

func TestValidateUppercasesVideoCodecs(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Media.VideoCodecs = []string{"h264", " vp8 "}
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if c.Media.VideoCodecs[0] != "H264" || c.Media.VideoCodecs[1] != "VP8" {
		t.Errorf("编解码未归一化: %v", c.Media.VideoCodecs)
	}
}

func TestValidateUDPPortRange(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Media.UDPPortMin = 40000
	if err := c.Validate(); err == nil {
		t.Fatal("只配 min 不配 max 应报错")
	}

	c.Media.UDPPortMax = 39000
	if err := c.Validate(); err == nil {
		t.Fatal("min > max 应报错")
	}

	c.Media.UDPPortMax = 42000
	if err := c.Validate(); err != nil {
		t.Fatalf("合法端口范围应通过: %v", err)
	}
}

func TestValidateRejectsBadPublicIP(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Media.PublicIP = "example.com"
	if err := c.Validate(); err == nil {
		t.Fatal("public_ip 必须是 IP 字面量")
	}
}

func TestValidateTURNRequiresCredentials(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.ICE.TURNURLs = []string{"turn:turn.example.com:3478"}
	if err := c.Validate(); err == nil {
		t.Fatal("配了 TURN 但无任何凭据应报错")
	}

	c.ICE.TURNStaticAuthSecret = "s"
	if err := c.Validate(); err != nil {
		t.Fatalf("提供短时凭据密钥后应通过: %v", err)
	}
}

func TestValidateClampsRenewLeeway(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Auth.TokenTTL = time.Minute
	c.Auth.RenewLeeway = 2 * time.Minute // 大于 TTL，非法
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if c.Auth.RenewLeeway != 12*time.Second {
		t.Errorf("renew_leeway = %v, 期望回落为 TTL/5", c.Auth.RenewLeeway)
	}
}

func TestValidateReadTimeoutExceedsPingInterval(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = minimalServers()
	c.Runtime.PingInterval = 30 * time.Second
	c.Runtime.ReadTimeout = 10 * time.Second
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	if c.Runtime.ReadTimeout <= c.Runtime.PingInterval {
		t.Errorf("read_timeout=%v 应大于 ping_interval=%v", c.Runtime.ReadTimeout, c.Runtime.PingInterval)
	}
}

func TestNormalizeServerAddr(t *testing.T) {
	cases := map[string]string{
		"127.0.0.1:9987":      "127.0.0.1:9987",
		"127.0.0.1":           "127.0.0.1:9987",
		" TS.Example.com  ":   "ts.example.com:9987",
		"ts.example.com:9988": "ts.example.com:9988",
		"[2001:db8::1]:9987":  "[2001:db8::1]:9987",
		"[2001:db8::1]":       "[2001:db8::1]:9987",
	}
	for in, want := range cases {
		got, err := NormalizeServerAddr(in)
		if err != nil {
			t.Errorf("NormalizeServerAddr(%q) 报错: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("NormalizeServerAddr(%q) = %q, 期望 %q", in, got, want)
		}
	}
}

func TestNormalizeServerAddrErrors(t *testing.T) {
	bad := []string{
		"",
		"   ",
		":9987",
		"host:0",
		"host:70000",
		"host:abc",
		// 裸 IPv6 缺方括号，无法区分主机与端口。
		"2001:db8::1",
	}
	for _, in := range bad {
		if got, err := NormalizeServerAddr(in); err == nil {
			t.Errorf("NormalizeServerAddr(%q) 应报错，得到 %q", in, got)
		}
	}
}

func TestFindServer(t *testing.T) {
	c := Default()
	c.Runtime.DevInsecure = true
	c.Servers = []VirtualSrv{{
		ServerAddr:    []string{"127.0.0.1:9987", "ts.example.com:9987"},
		QueryPassword: "pw",
	}}
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}

	for _, in := range []string{"127.0.0.1:9987", "127.0.0.1", "TS.example.com:9987", "ts.example.com"} {
		if _, ok := c.FindServer(in); !ok {
			t.Errorf("FindServer(%q) 应命中", in)
		}
	}
	for _, in := range []string{"10.0.0.1:9987", "127.0.0.1:9988", "", "bogus:x"} {
		if _, ok := c.FindServer(in); ok {
			t.Errorf("FindServer(%q) 不应命中", in)
		}
	}
}

func TestModeHelpers(t *testing.T) {
	c := Default()
	if !c.ModeEnabled(ModeSFU) || !c.ModeEnabled(ModeP2P) {
		t.Error("默认应同时启用 sfu 与 p2p")
	}
	c.Modes = []Mode{ModeP2P}
	if c.ModeEnabled(ModeSFU) {
		t.Error("未启用的模式应返回 false")
	}
	if got := c.ModeStrings(); len(got) != 1 || got[0] != "p2p" {
		t.Errorf("ModeStrings = %v", got)
	}
}

func TestTLSEnabled(t *testing.T) {
	c := Default()
	if c.TLSEnabled() {
		t.Error("默认未配置证书")
	}
	c.Listen.TLSCert = "c"
	if c.TLSEnabled() {
		t.Error("只有证书不算启用 TLS")
	}
	c.Listen.TLSKey = "k"
	if !c.TLSEnabled() {
		t.Error("证书与私钥齐备时应为启用")
	}
}

func TestLoadFromFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := `listen:
  addr: "127.0.0.1:10199"
  base_path: /custom
log:
  level: debug
  format: json
auth:
  token_secret: "file-secret"
  token_ttl: 15m
servers:
  - server_addr: ["127.0.0.1:9987"]
    query_password: "filepw"
    query_protocol: ssh
modes: ["p2p"]
media:
  video_codecs: ["vp8"]
runtime:
  dev_insecure: true
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 失败: %v", err)
	}
	if cfg.Listen.Addr != "127.0.0.1:10199" || cfg.Listen.BasePath != "/custom" {
		t.Errorf("监听配置未生效: %+v", cfg.Listen)
	}
	if cfg.Auth.TokenSecret != "file-secret" || cfg.Auth.TokenTTL != 15*time.Minute {
		t.Errorf("鉴权配置未生效: %+v", cfg.Auth)
	}
	if len(cfg.Servers) != 1 || cfg.Servers[0].QueryPort != 10022 {
		t.Errorf("服务器配置未生效: %+v", cfg.Servers)
	}
	if len(cfg.Modes) != 1 || cfg.Modes[0] != ModeP2P {
		t.Errorf("modes = %v", cfg.Modes)
	}
	if cfg.Media.VideoCodecs[0] != "VP8" {
		t.Errorf("video_codecs = %v", cfg.Media.VideoCodecs)
	}
	// 文件未指定的项应保留默认值。
	if cfg.Limits.MaxStreamsPerChannel != 4 {
		t.Errorf("未指定项应用默认值，得到 %d", cfg.Limits.MaxStreamsPerChannel)
	}
}

func TestLoadMissingFile(t *testing.T) {
	if _, err := Load(filepath.Join(t.TempDir(), "nope.yaml")); err == nil {
		t.Fatal("不存在的配置文件应报错")
	}
}

func TestLoadBadYAML(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.yaml")
	if err := os.WriteFile(path, []byte("listen: [oops"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("非法 YAML 应报错")
	}
}

func TestLoadEnvOverrides(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := `auth:
  token_secret: "file-secret"
servers:
  - server_addr: ["127.0.0.1:9987"]
    query_password: "filepw"
runtime:
  dev_insecure: true
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	t.Setenv(EnvPrefix+"LISTEN_ADDR", "0.0.0.0:11000")
	t.Setenv(EnvPrefix+"TOKEN_SECRET", "env-secret")
	t.Setenv(EnvPrefix+"TOKEN_TTL", "20m")
	t.Setenv(EnvPrefix+"QUERY_PASSWORD", "envpw")
	t.Setenv(EnvPrefix+"QUERY_USER", "ts9stream")
	t.Setenv(EnvPrefix+"MAX_BITRATE_KBPS", "2500")
	t.Setenv(EnvPrefix+"LOG_LEVEL", "warn")

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load 失败: %v", err)
	}
	if cfg.Listen.Addr != "0.0.0.0:11000" {
		t.Errorf("addr = %q", cfg.Listen.Addr)
	}
	if cfg.Auth.TokenSecret != "env-secret" {
		t.Errorf("token_secret = %q", cfg.Auth.TokenSecret)
	}
	if cfg.Auth.TokenTTL != 20*time.Minute {
		t.Errorf("token_ttl = %v", cfg.Auth.TokenTTL)
	}
	if cfg.Servers[0].QueryPassword != "envpw" || cfg.Servers[0].QueryUser != "ts9stream" {
		t.Errorf("query 凭据未被环境变量覆盖: %+v", cfg.Servers[0])
	}
	if cfg.Limits.MaxBitrateKbps != 2500 {
		t.Errorf("max_bitrate_kbps = %d", cfg.Limits.MaxBitrateKbps)
	}
	if cfg.Log.Level != "warn" {
		t.Errorf("log level = %q", cfg.Log.Level)
	}
}

func TestLoadEnvIgnoresBlank(t *testing.T) {
	t.Setenv(EnvPrefix+"LISTEN_ADDR", "   ")
	cfg := Default()
	applyEnv(&cfg)
	if cfg.Listen.Addr != ":10099" {
		t.Errorf("空白环境变量不应覆盖默认值，得到 %q", cfg.Listen.Addr)
	}
}

func TestLoadEnvIgnoresBadDuration(t *testing.T) {
	t.Setenv(EnvPrefix+"TOKEN_TTL", "not-a-duration")
	cfg := Default()
	applyEnv(&cfg)
	if cfg.Auth.TokenTTL != 10*time.Minute {
		t.Errorf("非法时长应被忽略，得到 %v", cfg.Auth.TokenTTL)
	}
}

func TestLoadWithoutFileUsesEnvOnly(t *testing.T) {
	t.Setenv(EnvPrefix+"DEV_INSECURE", "true")
	// 没有 servers，校验必然失败——这里确认失败原因正确。
	_, err := Load("")
	if err == nil || !strings.Contains(err.Error(), "servers") {
		t.Fatalf("期望 servers 校验错误，得到 %v", err)
	}
}
