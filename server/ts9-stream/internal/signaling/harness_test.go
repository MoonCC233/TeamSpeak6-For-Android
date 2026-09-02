package signaling

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/auth"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/serverquery"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/sfu"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// testServerAddr 是测试用虚拟服务器地址，必须与 newTestEnv 里的 server_addr 一致。
const testServerAddr = "127.0.0.1:9987"

// testIOTimeout 是单次 WebSocket 读写的上限，本机往返足够宽松。
const testIOTimeout = 5 * time.Second

// ---------------------------------------------------------------------------
// 假 ServerQuery
// ---------------------------------------------------------------------------

// fakeSQ 实现 auth.QueryClient，用内存表替代真实 ServerQuery 连接。
// 支持测试中途改动客户端所在频道，用于验证 renew 换频道的清理逻辑。
type fakeSQ struct {
	mu    sync.Mutex
	infos map[int]serverquery.ClientInfo
}

func (f *fakeSQ) ClientInfo(_ context.Context, clid int) (serverquery.ClientInfo, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	info, ok := f.infos[clid]
	if !ok {
		return serverquery.ClientInfo{}, &serverquery.QueryError{
			ID:      serverquery.ErrIDInvalidClientID,
			Msg:     "invalid clientID",
			Command: "clientinfo",
		}
	}
	return info, nil
}

func (f *fakeSQ) Close() error { return nil }

// setCID 修改某个客户端在假 ServerQuery 中的频道号。
func (f *fakeSQ) setCID(clid int, cid int64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	info := f.infos[clid]
	info.CID = cid
	f.infos[clid] = info
}

// testClients 是固定的在线客户端表：7/8/10 在频道 12，9 在频道 99。
func testClients() map[int]serverquery.ClientInfo {
	return map[int]serverquery.ClientInfo{
		7:  {CLID: 7, UID: "uidA=", CID: 12, Nickname: "Alice", Platform: "Windows", Version: "6.0.0"},
		8:  {CLID: 8, UID: "uidB=", CID: 12, Nickname: "Bob", Platform: "Android", Version: "6.0.0"},
		9:  {CLID: 9, UID: "uidC=", CID: 99, Nickname: "Carol", Platform: "Linux", Version: "6.0.0"},
		10: {CLID: 10, UID: "uidD=", CID: 12, Nickname: "Dave", Platform: "Windows", Version: "6.0.0"},
	}
}

func testUID(t *testing.T, clid int) string {
	t.Helper()
	info, ok := testClients()[clid]
	if !ok {
		t.Fatalf("测试客户端 %d 未定义", clid)
	}
	return info.UID
}

func discardLog() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// ---------------------------------------------------------------------------
// 测试环境
// ---------------------------------------------------------------------------

type testEnv struct {
	hub   *Hub
	srv   *httptest.Server
	cfg   *config.Config
	fq    *fakeSQ
	ipSeq uint32
}

// newTestEnv 按 cmd/ts9-stream 的顺序组装 Hub，并挂到 httptest 服务器上。
// tweak 可在 Validate 之前修改配置。
func newTestEnv(t *testing.T, tweak func(*config.Config)) *testEnv {
	t.Helper()

	cfg := config.Default()
	cfg.Runtime.DevInsecure = true
	cfg.Auth.TokenSecret = "unit-test-secret-0123456789abcdef"
	// httptest 服务器跑在回环上，测试用 X-Forwarded-For 区分伪客户端 IP，
	// 因此必须把回环声明为可信代理，否则该头会被忽略。
	cfg.Listen.TrustedProxies = []string{"127.0.0.1", "::1"}
	cfg.Servers = []config.VirtualSrv{{
		ServerAddr:    []string{testServerAddr},
		QueryPassword: "pw",
	}}
	if tweak != nil {
		tweak(&cfg)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("配置校验失败: %v", err)
	}

	fq := &fakeSQ{infos: testClients()}

	verifier := auth.NewVerifier(&cfg, discardLog())
	verifier.SetClientFactory(func(*config.VirtualSrv) auth.QueryClient { return fq })

	signer, err := auth.NewSigner(cfg.Auth.TokenSecret, cfg.Auth.TokenTTL)
	if err != nil {
		t.Fatalf("创建签名器失败: %v", err)
	}

	engine, err := sfu.New(sfu.Config{
		VideoCodecs: cfg.Media.VideoCodecs,
		AudioCodecs: cfg.Media.AudioCodecs,
		PLIInterval: cfg.Media.PLIInterval,
	}, discardLog())
	if err != nil {
		t.Fatalf("创建 SFU 引擎失败: %v", err)
	}

	hub := NewHub(Deps{
		Config:   &cfg,
		Log:      discardLog(),
		Signer:   signer,
		Verifier: verifier,
		Engine:   engine,
	})

	mux := http.NewServeMux()
	mux.Handle(cfg.Listen.BasePath, hub)
	srv := httptest.NewServer(mux)

	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		hub.Shutdown(ctx)
		cancel()
		srv.Close()
		engine.Close()
		verifier.Close()
	})

	return &testEnv{hub: hub, srv: srv, cfg: &cfg, fq: fq}
}

// nextIP 为每次拨号分配互不相同的伪客户端 IP，避免 hello 失败限流互相干扰。
func (e *testEnv) nextIP() string {
	n := atomic.AddUint32(&e.ipSeq, 1)
	return fmt.Sprintf("10.%d.%d.%d", (n>>16)&0xff, (n>>8)&0xff, n&0xff)
}

func (e *testEnv) wsURL() string {
	return strings.Replace(e.srv.URL, "http", "ws", 1) + e.cfg.Listen.BasePath
}

// dialIP 以指定的 X-Forwarded-For 建立 TSSP 连接。
func (e *testEnv) dialIP(t *testing.T, ip string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), testIOTimeout)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, e.wsURL(), &websocket.DialOptions{
		Subprotocols: []string{tssp.Subprotocol},
		HTTPHeader:   http.Header{"X-Forwarded-For": []string{ip}},
	})
	if err != nil {
		t.Fatalf("连接信令服务失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	return conn
}

func (e *testEnv) dial(t *testing.T) *websocket.Conn {
	t.Helper()
	return e.dialIP(t, e.nextIP())
}

// ---------------------------------------------------------------------------
// 低层收发
// ---------------------------------------------------------------------------

func sendRaw(t *testing.T, c *websocket.Conn, mt websocket.MessageType, payload []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), testIOTimeout)
	defer cancel()
	if err := c.Write(ctx, mt, payload); err != nil {
		t.Fatalf("发送消息失败: %v", err)
	}
}

// sendReq 手写信封，避免依赖服务端内部的 encode。
func sendReq(t *testing.T, c *websocket.Conn, msgType, id string, payload any) {
	t.Helper()
	env := map[string]any{"t": msgType}
	if id != "" {
		env["id"] = id
	}
	if payload != nil {
		raw, err := json.Marshal(payload)
		if err != nil {
			t.Fatalf("序列化 %s 负载失败: %v", msgType, err)
		}
		env["d"] = json.RawMessage(raw)
	}
	buf, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("序列化 %s 信封失败: %v", msgType, err)
	}
	sendRaw(t, c, websocket.MessageText, buf)
}

func readEnv(t *testing.T, c *websocket.Conn) *tssp.Envelope {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), testIOTimeout)
	defer cancel()
	mt, data, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("读取消息失败: %v", err)
	}
	if mt != websocket.MessageText {
		t.Fatalf("期望文本帧，实际 %v", mt)
	}
	var env tssp.Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		t.Fatalf("解析信封失败: %v (原始: %s)", err, string(data))
	}
	return &env
}

// readUntil 跳过与本次断言无关的广播事件，直到读到期望的消息类型。
func readUntil(t *testing.T, c *websocket.Conn, want string) *tssp.Envelope {
	t.Helper()
	for i := 0; i < 24; i++ {
		env := readEnv(t, c)
		if env.Type == want {
			return env
		}
		if env.Type == tssp.TypeError {
			e := decodeData[tssp.Error](t, env)
			t.Fatalf("等待 %s 时收到错误: code=%s message=%s", want, e.Code, e.Message)
		}
	}
	t.Fatalf("等待 %s 超过 24 条消息仍未收到", want)
	return nil
}

func decodeData[T any](t *testing.T, env *tssp.Envelope) T {
	t.Helper()
	var v T
	if err := env.Decode(&v); err != nil {
		t.Fatalf("解析 %s 负载失败: %v", env.Type, err)
	}
	return v
}

func expectErrCode(t *testing.T, c *websocket.Conn, wantCode string) tssp.Error {
	t.Helper()
	env := readUntil(t, c, tssp.TypeError)
	e := decodeData[tssp.Error](t, env)
	if e.Code != wantCode {
		t.Fatalf("期望错误码 %s，实际 %s (%s)", wantCode, e.Code, e.Message)
	}
	return e
}

// ---------------------------------------------------------------------------
// 测试客户端
// ---------------------------------------------------------------------------

type testClient struct {
	conn  *websocket.Conn
	clid  int
	cid   int64
	uid   string
	token string
	sid   string
	nick  string
}

// helloPayload 构造一个默认合法的 hello 请求，便于各测试局部改字段。
func helloPayload(uid string, clid int, cid int64) map[string]any {
	return map[string]any{
		"protocol":    tssp.ProtocolVersion,
		"server_addr": testServerAddr,
		"uid":         uid,
		"clid":        clid,
		"cid":         cid,
		"nonce":       fmt.Sprintf("nonce-%d", clid),
		"client": map[string]any{
			"name":     "ts9-test",
			"version":  "0.0.1",
			"platform": "test",
		},
		"capabilities": map[string]any{
			"modes":            []string{tssp.ModeSFU, tssp.ModeP2P},
			"video_codecs":     []string{"H264", "VP8"},
			"audio_codecs":     []string{"opus"},
			"max_recv_streams": 4,
		},
	}
}

// connect 完成拨号 + hello，返回已鉴权的测试客户端。
func (e *testEnv) connect(t *testing.T, clid int, cid int64) *testClient {
	t.Helper()
	conn := e.dial(t)
	uid := testUID(t, clid)
	sendReq(t, conn, tssp.TypeHello, fmt.Sprintf("h-%d", clid), helloPayload(uid, clid, cid))
	resp := decodeData[tssp.HelloResponse](t, readUntil(t, conn, tssp.TypeOK))
	if resp.SessionToken == "" {
		t.Fatal("hello 未返回会话令牌")
	}
	return &testClient{
		conn:  conn,
		clid:  clid,
		cid:   cid,
		uid:   uid,
		token: resp.SessionToken,
		sid:   resp.SessionID,
		nick:  resp.Nickname,
	}
}

func (c *testClient) req(t *testing.T, msgType, id string, payload any) {
	t.Helper()
	sendReq(t, c.conn, msgType, id, payload)
}

func (c *testClient) await(t *testing.T, want string) *tssp.Envelope {
	t.Helper()
	return readUntil(t, c.conn, want)
}

func (c *testClient) awaitOK(t *testing.T) *tssp.Envelope {
	t.Helper()
	return readUntil(t, c.conn, tssp.TypeOK)
}

func (c *testClient) expectErr(t *testing.T, wantCode string) tssp.Error {
	t.Helper()
	return expectErrCode(t, c.conn, wantCode)
}

// setup 以指定模式开一路流，返回响应。
func (c *testClient) setup(t *testing.T, mode, accessibility, name string) tssp.SetupResponse {
	t.Helper()
	c.req(t, tssp.TypeSetup, "s-"+name, tssp.SetupRequest{
		Token:         c.token,
		Mode:          mode,
		StreamType:    tssp.StreamTypeScreen,
		Accessibility: accessibility,
		Name:          name,
	})
	return decodeData[tssp.SetupResponse](t, c.awaitOK(t))
}

// subscribe 订阅一路流，返回响应。
func (c *testClient) subscribe(t *testing.T, streamID string) tssp.SubscribeResponse {
	t.Helper()
	c.req(t, tssp.TypeSubscribe, "sub-"+streamID, tssp.SubscribeRequest{
		Token:    c.token,
		StreamID: streamID,
	})
	return decodeData[tssp.SubscribeResponse](t, c.awaitOK(t))
}

func i64p(v int64) *int64 { return &v }

// contextWithTimeout 是测试中反复使用的短超时上下文。
func contextWithTimeout() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), testIOTimeout)
}
