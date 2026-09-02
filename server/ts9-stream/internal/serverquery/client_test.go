package serverquery

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeConn 是可编程的 Conn 实现，用于测试 Client 的重连与缓存逻辑。
type fakeConn struct {
	mu        sync.Mutex
	commands  []string
	responses map[string]*Response
	errs      map[string]error
	// failAll 非 nil 时所有命令都返回该错误（模拟连接断开）。
	failAll error
	closed  bool
}

func newFakeConn() *fakeConn {
	return &fakeConn{
		responses: make(map[string]*Response),
		errs:      make(map[string]error),
	}
}

func (f *fakeConn) Exec(_ context.Context, cmd string) (*Response, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.commands = append(f.commands, cmd)
	if f.failAll != nil {
		return nil, f.failAll
	}
	if err, ok := f.errs[cmd]; ok {
		return nil, err
	}
	if resp, ok := f.responses[cmd]; ok {
		return resp, nil
	}
	return &Response{}, nil
}

func (f *fakeConn) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return nil
}

func (f *fakeConn) log() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.commands...)
}

func TestClientHandshakeRaw(t *testing.T) {
	fc := newFakeConn()
	c := NewClient(Options{
		Protocol:    "raw",
		Host:        "127.0.0.1",
		Port:        10011,
		User:        "ts9stream",
		Password:    "secret",
		VirtualPort: 9987,
		Timeout:     time.Second,
		Dial:        func(context.Context) (Conn, error) { return fc, nil },
	}, nil)
	defer func() { _ = c.Close() }()

	if _, err := c.WhoAmI(context.Background()); err != nil {
		t.Fatalf("WhoAmI 失败: %v", err)
	}

	log := fc.log()
	if len(log) != 3 {
		t.Fatalf("命令序列 = %v，期望 login/use/whoami 三条", log)
	}
	if !strings.HasPrefix(log[0], "login ") {
		t.Errorf("第一条应为 login，得到 %q", log[0])
	}
	if !strings.Contains(log[0], "client_login_name=ts9stream") {
		t.Errorf("login 缺少用户名: %q", log[0])
	}
	if log[1] != "use port=9987" {
		t.Errorf("第二条应为 use port=9987，得到 %q", log[1])
	}
	if log[2] != "whoami" {
		t.Errorf("第三条应为 whoami，得到 %q", log[2])
	}
}

func TestClientHandshakeSSHSkipsLogin(t *testing.T) {
	fc := newFakeConn()
	c := NewClient(Options{
		Protocol:    "ssh",
		Host:        "127.0.0.1",
		Port:        10022,
		User:        "ts9stream",
		Password:    "secret",
		VirtualPort: 9987,
		Dial:        func(context.Context) (Conn, error) { return fc, nil },
	}, nil)
	defer func() { _ = c.Close() }()

	if _, err := c.WhoAmI(context.Background()); err != nil {
		t.Fatalf("WhoAmI 失败: %v", err)
	}
	log := fc.log()
	for _, cmd := range log {
		if strings.HasPrefix(cmd, "login ") {
			t.Fatalf("SSH 传输不应再发 login，命令序列 = %v", log)
		}
	}
	if log[0] != "use port=9987" {
		t.Errorf("首条应为 use，得到 %q", log[0])
	}
}

func TestClientInfoParsing(t *testing.T) {
	fc := newFakeConn()
	fc.responses["clientinfo clid=7"] = &Response{
		Records: ParseRecords(`cid=12 client_unique_identifier=abcdefUID= client_nickname=Alice client_type=0 client_servergroups=6,8 client_channel_group_id=9 client_platform=Windows client_version=6.0.0`),
	}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	info, err := c.ClientInfo(context.Background(), 7)
	if err != nil {
		t.Fatalf("ClientInfo 失败: %v", err)
	}
	if info.CLID != 7 {
		t.Errorf("CLID = %d, 期望 7（clientinfo 响应不含 clid，应取请求参数）", info.CLID)
	}
	if info.UID != "abcdefUID=" {
		t.Errorf("UID = %q", info.UID)
	}
	if info.CID != 12 {
		t.Errorf("CID = %d, 期望 12", info.CID)
	}
	if info.Nickname != "Alice" {
		t.Errorf("Nickname = %q", info.Nickname)
	}
	if info.IsQueryClient() {
		t.Error("client_type=0 不应判定为 query 客户端")
	}
	if len(info.ServerGroups) != 2 {
		t.Errorf("ServerGroups = %v", info.ServerGroups)
	}
}

func TestClientInfoFallbackChannelField(t *testing.T) {
	// 某些版本的 clientinfo 用 client_channel_id 表示所在频道。
	fc := newFakeConn()
	fc.responses["clientinfo clid=3"] = &Response{
		Records: ParseRecords(`client_unique_identifier=U client_channel_id=44 client_type=0`),
	}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	info, err := c.ClientInfo(context.Background(), 3)
	if err != nil {
		t.Fatalf("ClientInfo 失败: %v", err)
	}
	if info.CID != 44 {
		t.Errorf("CID = %d, 期望 44（应回退到 client_channel_id）", info.CID)
	}
}

func TestClientInfoQueryClientRejected(t *testing.T) {
	fc := newFakeConn()
	fc.responses["clientinfo clid=1"] = &Response{
		Records: ParseRecords(`client_unique_identifier=ServerQuery client_type=1 cid=1`),
	}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	info, err := c.ClientInfo(context.Background(), 1)
	if err != nil {
		t.Fatalf("ClientInfo 失败: %v", err)
	}
	if !info.IsQueryClient() {
		t.Error("client_type=1 应判定为 query 客户端")
	}
}

func TestClientInfoEmptyResponseIsNotFound(t *testing.T) {
	fc := newFakeConn()
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	_, err := c.ClientInfo(context.Background(), 999)
	if !IsClientNotFound(err) {
		t.Fatalf("空响应应映射为客户端不存在，得到 %v", err)
	}
}

func TestClientListParsing(t *testing.T) {
	fc := newFakeConn()
	cmd := BuildCommand("clientlist", nil, "-uid", "-groups", "-info")
	fc.responses[cmd] = &Response{
		Records: ParseRecords(`clid=1 cid=1 client_nickname=A client_type=0 client_unique_identifier=UA|clid=2 cid=5 client_nickname=B client_type=0 client_unique_identifier=UB`),
	}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	list, err := c.ClientList(context.Background())
	if err != nil {
		t.Fatalf("ClientList 失败: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("客户端数 = %d, 期望 2", len(list))
	}
	if list[0].CLID != 1 || list[1].CLID != 2 {
		t.Errorf("clid 解析错误: %+v", list)
	}
	if list[1].CID != 5 {
		t.Errorf("第二个客户端 cid = %d, 期望 5", list[1].CID)
	}
}

func TestClientCaching(t *testing.T) {
	fc := newFakeConn()
	fc.responses["clientinfo clid=7"] = &Response{
		Records: ParseRecords(`cid=1 client_unique_identifier=U client_type=0`),
	}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1, CacheTTL: time.Minute,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	for i := 0; i < 3; i++ {
		if _, err := c.ClientInfo(context.Background(), 7); err != nil {
			t.Fatalf("第 %d 次 ClientInfo 失败: %v", i, err)
		}
	}
	count := 0
	for _, cmd := range fc.log() {
		if cmd == "clientinfo clid=7" {
			count++
		}
	}
	if count != 1 {
		t.Errorf("clientinfo 实际发出 %d 次，缓存生效时应只有 1 次", count)
	}
}

func TestClientCacheExpires(t *testing.T) {
	fc := newFakeConn()
	fc.responses["clientinfo clid=7"] = &Response{
		Records: ParseRecords(`cid=1 client_unique_identifier=U client_type=0`),
	}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1, CacheTTL: 10 * time.Millisecond,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	if _, err := c.ClientInfo(context.Background(), 7); err != nil {
		t.Fatal(err)
	}
	time.Sleep(30 * time.Millisecond)
	if _, err := c.ClientInfo(context.Background(), 7); err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, cmd := range fc.log() {
		if cmd == "clientinfo clid=7" {
			count++
		}
	}
	if count != 2 {
		t.Errorf("缓存过期后应重新查询，实际发出 %d 次", count)
	}
}

func TestClientQueryErrorNotCachedAsTransport(t *testing.T) {
	// tsserver 返回的业务错误（如 512）不应触发重连，但可以被缓存。
	fc := newFakeConn()
	fc.errs["clientinfo clid=9"] = &QueryError{ID: ErrIDInvalidClientID, Msg: "invalid clientID"}
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1, CacheTTL: time.Minute,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	defer func() { _ = c.Close() }()

	for i := 0; i < 2; i++ {
		_, err := c.ClientInfo(context.Background(), 9)
		if !IsClientNotFound(err) {
			t.Fatalf("期望客户端不存在错误，得到 %v", err)
		}
	}
	count := 0
	for _, cmd := range fc.log() {
		if cmd == "clientinfo clid=9" {
			count++
		}
	}
	if count != 1 {
		t.Errorf("业务错误也应走缓存，实际发出 %d 次", count)
	}
}

func TestClientReconnectsOnTransportError(t *testing.T) {
	first := newFakeConn()
	first.failAll = errors.New("connection reset")
	second := newFakeConn()
	second.responses["clientinfo clid=7"] = &Response{
		Records: ParseRecords(`cid=1 client_unique_identifier=U client_type=0`),
	}

	var dialCount int
	var mu sync.Mutex
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) {
			mu.Lock()
			defer mu.Unlock()
			dialCount++
			if dialCount == 1 {
				return first, nil
			}
			return second, nil
		}}, nil)
	defer func() { _ = c.Close() }()

	info, err := c.ClientInfo(context.Background(), 7)
	if err != nil {
		t.Fatalf("重连后仍失败: %v", err)
	}
	if info.UID != "U" {
		t.Errorf("UID = %q", info.UID)
	}
	if !first.closed {
		t.Error("失败的连接应被关闭")
	}
	mu.Lock()
	got := dialCount
	mu.Unlock()
	if got != 2 {
		t.Errorf("拨号次数 = %d, 期望 2", got)
	}
}

func TestClientBackoffAfterDialFailure(t *testing.T) {
	dialErr := errors.New("connection refused")
	var dialCount int
	var mu sync.Mutex
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) {
			mu.Lock()
			dialCount++
			mu.Unlock()
			return nil, dialErr
		}}, nil)
	defer func() { _ = c.Close() }()

	// 第一次失败会记录退避；紧接着的调用应直接被退避拦下，不再拨号。
	if _, err := c.ClientInfo(context.Background(), 1); err == nil {
		t.Fatal("首次应失败")
	}
	if _, err := c.ClientInfo(context.Background(), 1); err == nil {
		t.Fatal("退避期内应继续失败")
	}
	mu.Lock()
	got := dialCount
	mu.Unlock()
	// exec 内部会在传输错误后重试一次，因此首个调用最多拨号 2 次；
	// 关键是第二个调用不应再新增拨号。
	if got > 2 {
		t.Errorf("退避未生效，拨号次数 = %d", got)
	}
}

func TestClientRetryFailureArmsBackoff(t *testing.T) {
	// 重试也失败说明服务端确实不可用，此时必须进入退避，
	// 否则每次调用都会拨号两次，把 tsserver 的 query 端口打满。
	var dialCount int
	var mu sync.Mutex
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) {
			mu.Lock()
			defer mu.Unlock()
			dialCount++
			fc := newFakeConn()
			fc.failAll = errors.New("connection reset")
			return fc, nil
		}}, nil)
	defer func() { _ = c.Close() }()

	if _, err := c.ClientInfo(context.Background(), 1); err == nil {
		t.Fatal("应失败")
	}
	mu.Lock()
	afterFirst := dialCount
	mu.Unlock()
	if afterFirst != 2 {
		t.Fatalf("首个调用应拨号 2 次（原始 + 重试），实际 %d", afterFirst)
	}

	if _, err := c.ClientInfo(context.Background(), 1); err == nil {
		t.Fatal("退避期内应继续失败")
	}
	mu.Lock()
	afterSecond := dialCount
	mu.Unlock()
	if afterSecond != afterFirst {
		t.Errorf("退避期内不应再拨号，拨号次数由 %d 增至 %d", afterFirst, afterSecond)
	}
}

func TestClientAddr(t *testing.T) {
	c := NewClient(Options{Host: "127.0.0.1", Port: 10022}, nil)
	if got, want := c.Addr(), "127.0.0.1:10022"; got != want {
		t.Errorf("Addr = %q, 期望 %q", got, want)
	}
}

func TestClientCloseIdempotent(t *testing.T) {
	fc := newFakeConn()
	c := NewClient(Options{Protocol: "ssh", Host: "h", Port: 1,
		Dial: func(context.Context) (Conn, error) { return fc, nil }}, nil)
	if _, err := c.WhoAmI(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatalf("首次 Close 失败: %v", err)
	}
	if err := c.Close(); err != nil {
		t.Fatalf("重复 Close 应无害: %v", err)
	}
}
