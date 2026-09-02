package auth

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/serverquery"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// fakeQuery 是可编程的 QueryClient，用于替代真实 ServerQuery。
type fakeQuery struct {
	infos map[int]serverquery.ClientInfo
	err   error
	calls int
}

func (f *fakeQuery) ClientInfo(_ context.Context, clid int) (serverquery.ClientInfo, error) {
	f.calls++
	if f.err != nil {
		return serverquery.ClientInfo{}, f.err
	}
	info, ok := f.infos[clid]
	if !ok {
		return serverquery.ClientInfo{}, &serverquery.QueryError{
			ID: serverquery.ErrIDInvalidClientID, Msg: "invalid clientID", Command: "clientinfo",
		}
	}
	return info, nil
}

func (f *fakeQuery) Close() error { return nil }

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// testConfig 构造一份最小可用配置，含一个虚拟服务器。
func testConfig(t *testing.T) *config.Config {
	t.Helper()
	cfg := config.Default()
	cfg.Runtime.DevInsecure = true
	cfg.Servers = []config.VirtualSrv{{
		ServerAddr:    []string{"127.0.0.1:9987", "ts.example.com:9987"},
		QueryPassword: "pw",
	}}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("配置校验失败: %v", err)
	}
	return &cfg
}

// newTestVerifier 返回注入了 fake ServerQuery 的校验器。
func newTestVerifier(t *testing.T, cfg *config.Config, fq *fakeQuery) *Verifier {
	t.Helper()
	v := NewVerifier(cfg, discardLogger())
	v.SetClientFactory(func(*config.VirtualSrv) QueryClient { return fq })
	return v
}

func onlineClient() serverquery.ClientInfo {
	return serverquery.ClientInfo{
		CLID:         7,
		UID:          "abcUID=",
		CID:          12,
		Nickname:     "Alice",
		ClientType:   0,
		ServerGroups: []int{6, 8},
	}
}

func TestVerifySuccess(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	ident, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	if err != nil {
		t.Fatalf("Verify 失败: %v", err)
	}
	if ident.ServerAddr != "127.0.0.1:9987" {
		t.Errorf("ServerAddr = %q", ident.ServerAddr)
	}
	if ident.ServerHash != HashServerAddr("127.0.0.1:9987") {
		t.Error("ServerHash 与归一化地址的哈希不一致")
	}
	if ident.CLID != 7 || ident.CID != 12 || ident.UID != "abcUID=" {
		t.Errorf("身份不一致: %+v", ident)
	}
	if ident.Nickname != "Alice" {
		t.Errorf("Nickname = %q", ident.Nickname)
	}
	if ident.VirtualSrv == nil {
		t.Error("应带回虚拟服务器配置")
	}
}

func TestVerifyAcceptsAliasAndNormalizesAddr(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	// 别名 + 大写主机名 + 省略默认端口，都应命中同一条配置。
	ident, err := v.Verify(context.Background(), "TS.example.com", "abcUID=", 7, 12)
	if err != nil {
		t.Fatalf("Verify 失败: %v", err)
	}
	if ident.ServerAddr != "ts.example.com:9987" {
		t.Errorf("ServerAddr = %q, 期望归一化为小写并补端口", ident.ServerAddr)
	}
}

func TestVerifyBadRequests(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	cases := []struct {
		name string
		addr string
		uid  string
		clid int
		cid  int64
	}{
		{"uid 为空", "127.0.0.1:9987", "", 7, 12},
		{"clid 为 0", "127.0.0.1:9987", "u", 0, 12},
		{"clid 为负", "127.0.0.1:9987", "u", -1, 12},
		{"cid 为负", "127.0.0.1:9987", "u", 7, -1},
		{"地址为空", "", "u", 7, 12},
		{"地址非法", "not a host:port:x", "u", 7, 12},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := v.Verify(context.Background(), c.addr, c.uid, c.clid, c.cid)
			wantCode(t, err, tssp.ErrBadRequest)
		})
	}
}

func TestVerifyUnknownServer(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "10.0.0.99:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrUnknownServer)
	if fq.calls != 0 {
		t.Error("未知服务器不应触发 ServerQuery 查询")
	}
}

func TestVerifyClientNotFound(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrClientNotFound)
}

func TestVerifyUIDMismatch(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "otherUID=", 7, 12)
	wantCode(t, err, tssp.ErrIdentityMismatch)
}

func TestVerifyCIDMismatch(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 999)
	wantCode(t, err, tssp.ErrIdentityMismatch)
}

func TestVerifyRejectsQueryClient(t *testing.T) {
	cfg := testConfig(t)
	info := onlineClient()
	info.ClientType = 1
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: info}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrNotAllowed)
}

func TestVerifyPermissionDenied(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{err: &serverquery.QueryError{
		ID: serverquery.ErrIDInsufficientPermissions, Msg: "insufficient client permissions",
	}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrQueryUnavailable)
}

func TestVerifyTransportErrorIsRetryable(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{err: errors.New("connection refused")}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrQueryUnavailable)
	var te *tssp.Error
	if errors.As(err, &te) && te.RetryAfterMS <= 0 {
		t.Error("连接类错误应带 retry_after_ms")
	}
}

func TestVerifyDenyServerGroup(t *testing.T) {
	cfg := testConfig(t)
	cfg.Access.DenyServerGroups = []int{8}
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrNotAllowed)
}

func TestVerifyAllowServerGroup(t *testing.T) {
	cfg := testConfig(t)
	cfg.Access.AllowServerGroups = []int{6}
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	if _, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12); err != nil {
		t.Fatalf("命中白名单应通过: %v", err)
	}

	cfg.Access.AllowServerGroups = []int{99}
	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrNotAllowed)
}

func TestVerifyDenyBeatsAllow(t *testing.T) {
	cfg := testConfig(t)
	cfg.Access.AllowServerGroups = []int{6}
	cfg.Access.DenyServerGroups = []int{6}
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	v := newTestVerifier(t, cfg, fq)

	_, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12)
	wantCode(t, err, tssp.ErrNotAllowed)
}

func TestHealthCheckTreatsNotFoundAsHealthy(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{}}
	v := newTestVerifier(t, cfg, fq)

	if err := v.HealthCheck(context.Background()); err != nil {
		t.Fatalf("CLIENT_NOT_FOUND 说明链路可用，不应视为失败: %v", err)
	}
}

func TestHealthCheckReportsTransportError(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{err: errors.New("dial tcp: connection refused")}
	v := newTestVerifier(t, cfg, fq)

	if err := v.HealthCheck(context.Background()); err == nil {
		t.Fatal("连接失败应上报")
	}
}

func TestHealthCheckWithoutServers(t *testing.T) {
	cfg := config.Default()
	v := NewVerifier(&cfg, discardLogger())
	if err := v.HealthCheck(context.Background()); !errors.Is(err, ErrNoQueryClient) {
		t.Fatalf("无服务器配置应返回 ErrNoQueryClient，得到 %v", err)
	}
}

func TestVerifierReusesClientPerServer(t *testing.T) {
	cfg := testConfig(t)
	fq := &fakeQuery{infos: map[int]serverquery.ClientInfo{7: onlineClient()}}
	var built int
	v := NewVerifier(cfg, discardLogger())
	v.SetClientFactory(func(*config.VirtualSrv) QueryClient {
		built++
		return fq
	})

	for i := 0; i < 3; i++ {
		if _, err := v.Verify(context.Background(), "127.0.0.1:9987", "abcUID=", 7, 12); err != nil {
			t.Fatal(err)
		}
	}
	if built != 1 {
		t.Errorf("同一虚拟服务器只应构造 1 个客户端，实际 %d", built)
	}
	v.Close()
}
