package signaling

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/coder/websocket"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// ---------------------------------------------------------------------------
// dispatch / 帧级校验
// ---------------------------------------------------------------------------

func TestDispatchRequiresHelloFirst(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendReq(t, conn, tssp.TypeList, "l1", tssp.ListRequest{})
	expectErrCode(t, conn, tssp.ErrTokenInvalid)
}

func TestDispatchRejectsBinaryFrame(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendRaw(t, conn, websocket.MessageBinary, []byte{0x01, 0x02})
	e := expectErrCode(t, conn, tssp.ErrBadRequest)
	if e.Message == "" {
		t.Fatal("期望带说明的错误消息")
	}
}

func TestDispatchRejectsInvalidJSON(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendRaw(t, conn, websocket.MessageText, []byte("{not json"))
	expectErrCode(t, conn, tssp.ErrBadRequest)
}

func TestDispatchRejectsMissingType(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendRaw(t, conn, websocket.MessageText, []byte(`{"id":"x"}`))
	expectErrCode(t, conn, tssp.ErrBadRequest)
}

func TestDispatchRejectsUnknownType(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, "foo", "u1", map[string]any{})
	c.expectErr(t, tssp.ErrBadRequest)
}

// ---------------------------------------------------------------------------
// hello
// ---------------------------------------------------------------------------

func TestHelloSuccess(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendReq(t, conn, tssp.TypeHello, "h1", helloPayload("uidA=", 7, 12))
	resp := decodeData[tssp.HelloResponse](t, readUntil(t, conn, tssp.TypeOK))

	if resp.SessionID == "" {
		t.Error("session_id 为空")
	}
	if resp.SessionToken == "" {
		t.Error("session_token 为空")
	}
	if resp.ExpiresAt <= 0 {
		t.Error("expires_at 应为正的毫秒时间戳")
	}
	if resp.Nonce != "nonce-7" {
		t.Errorf("nonce 未回显，实际 %q", resp.Nonce)
	}
	if resp.Nickname != "Alice" {
		t.Errorf("昵称应来自 ServerQuery，实际 %q", resp.Nickname)
	}
	if resp.Server.DefaultMode != tssp.ModeSFU {
		t.Errorf("默认模式应为 sfu，实际 %q", resp.Server.DefaultMode)
	}
	if len(resp.Server.Modes) != 2 {
		t.Errorf("应同时开放 sfu 与 p2p，实际 %v", resp.Server.Modes)
	}
	if resp.Server.MaxViewersPerStream != env.cfg.Limits.MaxViewersPerStream {
		t.Errorf("观看人数上限未透传，实际 %d", resp.Server.MaxViewersPerStream)
	}
	if len(resp.Server.VideoCodecs) == 0 {
		t.Error("视频编解码列表为空")
	}
	if env.hub.SessionCount() != 1 {
		t.Errorf("期望 1 个会话，实际 %d", env.hub.SessionCount())
	}
}

func TestHelloDefaultModeFallsBackToP2P(t *testing.T) {
	env := newTestEnv(t, func(c *config.Config) {
		c.Modes = []config.Mode{config.ModeP2P}
	})

	conn := env.dial(t)
	sendReq(t, conn, tssp.TypeHello, "h2", helloPayload("uidB=", 8, 12))
	resp := decodeData[tssp.HelloResponse](t, readUntil(t, conn, tssp.TypeOK))
	if resp.Server.DefaultMode != tssp.ModeP2P {
		t.Errorf("仅开启 p2p 时默认模式应为 p2p，实际 %q", resp.Server.DefaultMode)
	}
}

func TestHelloRejectsWrongProtocol(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	p := helloPayload("uidA=", 7, 12)
	p["protocol"] = 2
	sendReq(t, conn, tssp.TypeHello, "h1", p)
	expectErrCode(t, conn, tssp.ErrUnsupportedProtocol)
}

func TestHelloRejectsIncompatibleCodec(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	p := helloPayload("uidA=", 7, 12)
	p["capabilities"] = map[string]any{"video_codecs": []string{"AV1"}}
	sendReq(t, conn, tssp.TypeHello, "h1", p)
	expectErrCode(t, conn, tssp.ErrCodecNotSupported)
}

func TestHelloRejectsUnknownServer(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	p := helloPayload("uidA=", 7, 12)
	p["server_addr"] = "10.9.9.9:9987"
	sendReq(t, conn, tssp.TypeHello, "h1", p)
	expectErrCode(t, conn, tssp.ErrUnknownServer)
}

func TestHelloRejectsOfflineClient(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendReq(t, conn, tssp.TypeHello, "h1", helloPayload("uidZ=", 4242, 12))
	expectErrCode(t, conn, tssp.ErrClientNotFound)
}

func TestHelloRejectsUIDMismatch(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	// clid 7 的真实 uid 是 uidA=，这里冒充 uidB=。
	sendReq(t, conn, tssp.TypeHello, "h1", helloPayload("uidB=", 7, 12))
	expectErrCode(t, conn, tssp.ErrIdentityMismatch)
}

func TestHelloRejectsCIDMismatch(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	// clid 7 实际在频道 12，这里声称在 99。
	sendReq(t, conn, tssp.TypeHello, "h1", helloPayload("uidA=", 7, 99))
	expectErrCode(t, conn, tssp.ErrIdentityMismatch)
}

func TestHelloTwiceRejected(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	sendReq(t, c.conn, tssp.TypeHello, "h2", helloPayload("uidA=", 7, 12))
	c.expectErr(t, tssp.ErrBadRequest)
}

func TestHelloFailuresTriggerRateLimit(t *testing.T) {
	env := newTestEnv(t, func(c *config.Config) {
		c.Limits.HelloFailMax = 2
	})

	ip := "10.55.0.1"
	for i := 0; i < 2; i++ {
		conn := env.dialIP(t, ip)
		sendReq(t, conn, tssp.TypeHello, "h", helloPayload("uidZ=", 4242, 12))
		expectErrCode(t, conn, tssp.ErrClientNotFound)
		_ = conn.CloseNow()
	}

	// 达到上限后该 IP 直接在 HTTP 层被拒。
	ctx, cancel := contextWithTimeout()
	defer cancel()
	_, resp, err := websocket.Dial(ctx, env.wsURL(), &websocket.DialOptions{
		Subprotocols: []string{tssp.Subprotocol},
		HTTPHeader:   http.Header{"X-Forwarded-For": []string{ip}},
	})
	if err == nil {
		t.Fatal("期望握手被限流拒绝")
	}
	if resp == nil {
		t.Fatal("期望拿到 HTTP 响应")
	}
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("期望 429，实际 %d", resp.StatusCode)
	}
	if resp.Header.Get("Retry-After") == "" {
		t.Error("429 响应缺少 Retry-After")
	}
}

func TestUntrustedForwardedForIsIgnored(t *testing.T) {
	// 不声明任何可信代理：X-Forwarded-For 必须被忽略，
	// 否则攻击者只要每次换一个伪造的 XFF 就能无限重试 hello。
	env := newTestEnv(t, func(c *config.Config) {
		c.Listen.TrustedProxies = nil
		c.Limits.HelloFailMax = 2
	})

	for i := 0; i < 2; i++ {
		conn := env.dialIP(t, fmt.Sprintf("10.77.0.%d", i+1))
		sendReq(t, conn, tssp.TypeHello, "h", helloPayload("uidZ=", 4242, 12))
		expectErrCode(t, conn, tssp.ErrClientNotFound)
		_ = conn.CloseNow()
	}

	// 换一个全新的伪造 XFF 也应被限流：真实来源仍是回环。
	ctx, cancel := contextWithTimeout()
	defer cancel()
	_, resp, err := websocket.Dial(ctx, env.wsURL(), &websocket.DialOptions{
		Subprotocols: []string{tssp.Subprotocol},
		HTTPHeader:   http.Header{"X-Forwarded-For": []string{"10.77.9.9"}},
	})
	if err == nil {
		t.Fatal("伪造 X-Forwarded-For 不应绕过限流")
	}
	if resp == nil || resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("期望 429，实际 %+v", resp)
	}
}

func TestServeHTTPRejectsMissingSubprotocol(t *testing.T) {
	env := newTestEnv(t, nil)

	ctx, cancel := contextWithTimeout()
	defer cancel()
	conn, _, err := websocket.Dial(ctx, env.wsURL(), &websocket.DialOptions{})
	if err != nil {
		// 服务端也可能直接在握手阶段拒绝，同样算通过。
		return
	}
	defer conn.CloseNow()
	if _, _, err := conn.Read(ctx); err == nil {
		t.Fatal("未协商子协议时应被服务端关闭")
	}
}

func TestServeHTTPRejectsWhenSessionLimitReached(t *testing.T) {
	env := newTestEnv(t, func(c *config.Config) {
		c.Limits.MaxSessions = 1
	})
	_ = env.connect(t, 7, 12)

	ctx, cancel := contextWithTimeout()
	defer cancel()
	_, resp, err := websocket.Dial(ctx, env.wsURL(), &websocket.DialOptions{
		Subprotocols: []string{tssp.Subprotocol},
		HTTPHeader:   http.Header{"X-Forwarded-For": []string{"10.66.0.1"}},
	})
	if err == nil {
		t.Fatal("期望超出会话上限时握手失败")
	}
	if resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("期望 503，实际 %+v", resp)
	}
}

// ---------------------------------------------------------------------------
// setup
// ---------------------------------------------------------------------------

func TestSetupSFUReturnsPublisherOfferer(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	resp := c.setup(t, tssp.ModeSFU, "", "screen-1")
	if resp.StreamID == "" {
		t.Fatal("stream_id 为空")
	}
	if resp.Mode != tssp.ModeSFU {
		t.Errorf("模式应为 sfu，实际 %q", resp.Mode)
	}
	if resp.Publish.Offerer != tssp.RolePublisher {
		t.Errorf("SFU 发布方向应由客户端 offer，实际 %q", resp.Publish.Offerer)
	}
	if resp.Publish.MaxBitrateKbps != env.cfg.Limits.MaxBitrateKbps {
		t.Errorf("码率上限未透传，实际 %d", resp.Publish.MaxBitrateKbps)
	}
	if len(resp.Publish.VideoCodecs) == 0 {
		t.Error("发布指令缺少视频编解码列表")
	}
	if env.hub.StreamCount() != 1 {
		t.Errorf("期望 1 路流，实际 %d", env.hub.StreamCount())
	}
}

func TestSetupDefaultsModeToSFU(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	resp := c.setup(t, "", "", "screen-default")
	if resp.Mode != tssp.ModeSFU {
		t.Errorf("mode 缺省应为 sfu，实际 %q", resp.Mode)
	}
}

func TestSetupRejectsInvalidFields(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	cases := []struct {
		name string
		req  tssp.SetupRequest
	}{
		{"mode", tssp.SetupRequest{Token: c.token, Mode: "quic"}},
		{"stream_type", tssp.SetupRequest{Token: c.token, Mode: tssp.ModeP2P, StreamType: "hologram"}},
		{"accessibility", tssp.SetupRequest{Token: c.token, Mode: tssp.ModeP2P, Accessibility: "public"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c.req(t, tssp.TypeSetup, "s-"+tc.name, tc.req)
			c.expectErr(t, tssp.ErrBadRequest)
		})
	}
}

func TestSetupRejectsDisabledMode(t *testing.T) {
	env := newTestEnv(t, func(c *config.Config) {
		c.Modes = []config.Mode{config.ModeP2P}
	})
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeSetup, "s1", tssp.SetupRequest{Token: c.token, Mode: tssp.ModeSFU})
	c.expectErr(t, tssp.ErrModeNotSupported)
}

func TestSetupRejectsMissingToken(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeSetup, "s1", tssp.SetupRequest{Mode: tssp.ModeP2P})
	c.expectErr(t, tssp.ErrTokenInvalid)
}

func TestSetupRejectsForeignToken(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	// b 用 a 的令牌，签名有效但会话不匹配。
	b.req(t, tssp.TypeSetup, "s1", tssp.SetupRequest{Token: a.token, Mode: tssp.ModeP2P})
	b.expectErr(t, tssp.ErrTokenInvalid)
}

func TestSetupTwiceRejected(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.setup(t, tssp.ModeP2P, "", "screen-1")
	c.req(t, tssp.TypeSetup, "s2", tssp.SetupRequest{Token: c.token, Mode: tssp.ModeP2P})
	c.expectErr(t, tssp.ErrAlreadyPublishing)
}

func TestSetupRejectsWhenChannelFull(t *testing.T) {
	env := newTestEnv(t, func(c *config.Config) {
		c.Limits.MaxStreamsPerChannel = 1
	})
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.req(t, tssp.TypeSetup, "s-b", tssp.SetupRequest{Token: b.token, Mode: tssp.ModeP2P})
	e := b.expectErr(t, tssp.ErrTooManyStreams)
	if e.RetryAfterMS <= 0 {
		t.Errorf("期望带 retry_after_ms，实际 %d", e.RetryAfterMS)
	}
}

func TestSetupBroadcastsToOthersButNotPublisher(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)
	outsider := env.connect(t, 9, 99)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")

	added := decodeData[tssp.StreamEvent](t, b.await(t, tssp.EventStreamAdded))
	if added.Stream.StreamID != resp.StreamID {
		t.Errorf("广播的 stream_id 不匹配: %q", added.Stream.StreamID)
	}
	if added.Stream.Publisher.CLID != 7 || added.Stream.Publisher.Nickname != "Alice" {
		t.Errorf("发布者信息不正确: %+v", added.Stream.Publisher)
	}
	if added.Stream.CID != 12 {
		t.Errorf("频道号应为 12，实际 %d", added.Stream.CID)
	}
	if added.Stream.ViewerCount != 0 {
		t.Errorf("初始观看人数应为 0，实际 %d", added.Stream.ViewerCount)
	}

	// 发布者自己不应收到 stream_added；用一次 list 往返确认队列里没有别的消息。
	a.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: a.token})
	if next := readEnv(t, a.conn); next.Type != tssp.TypeOK {
		t.Fatalf("发布者不应收到自己的 stream_added，实际收到 %q", next.Type)
	}

	// 其它频道的会话也不该收到。
	outsider.req(t, tssp.TypeList, "l2", tssp.ListRequest{Token: outsider.token})
	if next := readEnv(t, outsider.conn); next.Type != tssp.TypeOK {
		t.Fatalf("跨频道会话不应收到 stream_added，实际收到 %q", next.Type)
	}
}

// ---------------------------------------------------------------------------
// list
// ---------------------------------------------------------------------------

func TestListEmptyReturnsArray(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: c.token})
	env2 := c.awaitOK(t)

	// 必须是 JSON 数组而不是 null，客户端才能直接遍历。
	var raw struct {
		Streams json.RawMessage `json:"streams"`
	}
	if err := env2.Decode(&raw); err != nil {
		t.Fatalf("解析 list 响应失败: %v", err)
	}
	if string(raw.Streams) != "[]" {
		t.Errorf("空列表应序列化为 []，实际 %s", string(raw.Streams))
	}
}

func TestListReturnsChannelStreams(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")

	b.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: b.token, CID: i64p(12)})
	list := decodeData[tssp.ListResponse](t, b.await(t, tssp.TypeOK))
	if len(list.Streams) != 1 {
		t.Fatalf("期望 1 路流，实际 %d", len(list.Streams))
	}
	if list.Streams[0].StreamID != resp.StreamID {
		t.Errorf("stream_id 不匹配: %q", list.Streams[0].StreamID)
	}
	if list.Streams[0].Name != "screen-a" {
		t.Errorf("流名称不匹配: %q", list.Streams[0].Name)
	}
	if list.Streams[0].Accessibility != tssp.AccessibilityChannel {
		t.Errorf("accessibility 缺省应为 channel，实际 %q", list.Streams[0].Accessibility)
	}
}

func TestListRejectsOtherChannel(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: c.token, CID: i64p(99)})
	c.expectErr(t, tssp.ErrNotSameChannel)
}

// ---------------------------------------------------------------------------
// update / stop
// ---------------------------------------------------------------------------

func TestUpdateMergesPropertiesAndBroadcasts(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)

	a.req(t, tssp.TypeUpdate, "u1", tssp.UpdateRequest{
		Token:      a.token,
		StreamID:   resp.StreamID,
		Name:       "screen-a2",
		Properties: map[string]string{"fps": "30", "bitrate_kbps": "999999"},
	})
	updated := decodeData[tssp.StreamEvent](t, a.awaitOK(t))
	if updated.Stream.Name != "screen-a2" {
		t.Errorf("名称未更新: %q", updated.Stream.Name)
	}
	if updated.Stream.Properties["fps"] != "30" {
		t.Errorf("属性未写入: %+v", updated.Stream.Properties)
	}
	if got := updated.Stream.Properties["bitrate_kbps"]; got != itoaSafe(env.cfg.Limits.MaxBitrateKbps) {
		t.Errorf("码率应被夹到上限，实际 %q", got)
	}

	ev := decodeData[tssp.StreamEvent](t, b.await(t, tssp.EventStreamUpdated))
	if ev.Stream.Name != "screen-a2" {
		t.Errorf("广播未带新名称: %q", ev.Stream.Name)
	}

	// 第二次只改属性，名称保持不变，且旧属性仍在（合并语义）。
	a.req(t, tssp.TypeUpdate, "u2", tssp.UpdateRequest{
		Token:      a.token,
		StreamID:   resp.StreamID,
		Properties: map[string]string{"resolution": "1920x1080"},
	})
	merged := decodeData[tssp.StreamEvent](t, a.awaitOK(t))
	if merged.Stream.Name != "screen-a2" {
		t.Errorf("空名称不应清空原名，实际 %q", merged.Stream.Name)
	}
	if merged.Stream.Properties["fps"] != "30" || merged.Stream.Properties["resolution"] != "1920x1080" {
		t.Errorf("属性应合并，实际 %+v", merged.Stream.Properties)
	}
}

func TestUpdateRejectsNonOwner(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")

	b.req(t, tssp.TypeUpdate, "u1", tssp.UpdateRequest{Token: b.token, StreamID: resp.StreamID, Name: "hijack"})
	b.expectErr(t, tssp.ErrNotStreamOwner)
}

func TestUpdateRejectsUnknownStream(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeUpdate, "u1", tssp.UpdateRequest{Token: c.token, StreamID: "no-such-stream"})
	c.expectErr(t, tssp.ErrStreamNotFound)
}

func TestStopRejectsNonOwner(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")

	b.req(t, tssp.TypeStop, "x1", tssp.StopRequest{Token: b.token, StreamID: resp.StreamID})
	b.expectErr(t, tssp.ErrNotStreamOwner)
}

func TestStopRemovesStreamAndNotifiesViewers(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)

	sub := b.subscribe(t, resp.StreamID)
	if sub.State != tssp.SubscribeStateReady {
		t.Fatalf("channel 可见性应直接 ready，实际 %q", sub.State)
	}
	a.await(t, tssp.EventPeerJoined)

	a.req(t, tssp.TypeStop, "x1", tssp.StopRequest{Token: a.token, StreamID: resp.StreamID})
	removed := decodeData[tssp.StreamRemovedEvent](t, a.awaitOK(t))
	if removed.Reason != tssp.ReasonStopped {
		t.Errorf("停止原因应为 stopped，实际 %q", removed.Reason)
	}

	fromStream := decodeData[tssp.RemovedFromStreamEvent](t, b.await(t, tssp.EventRemovedFromStream))
	if fromStream.StreamID != resp.StreamID || fromStream.Reason != tssp.ReasonStopped {
		t.Errorf("观看者收到的移除事件不正确: %+v", fromStream)
	}
	roomEv := decodeData[tssp.StreamRemovedEvent](t, b.await(t, tssp.EventStreamRemoved))
	if roomEv.StreamID != resp.StreamID {
		t.Errorf("房间广播的 stream_id 不匹配: %q", roomEv.StreamID)
	}

	if env.hub.StreamCount() != 0 {
		t.Errorf("流应已清理，实际剩余 %d", env.hub.StreamCount())
	}

	// 停止后可以再开一路，说明 publishing 标记已复位。
	a.setup(t, tssp.ModeP2P, "", "screen-a3")
}

func TestDisconnectPublisherTearsDownStream(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)

	if err := a.conn.Close(websocket.StatusNormalClosure, "bye"); err != nil {
		t.Fatalf("关闭发布者连接失败: %v", err)
	}

	ev := decodeData[tssp.RemovedFromStreamEvent](t, b.await(t, tssp.EventRemovedFromStream))
	if ev.Reason != tssp.ReasonDisconnected {
		t.Errorf("原因应为 disconnected，实际 %q", ev.Reason)
	}
	b.await(t, tssp.EventStreamRemoved)

	if env.hub.StreamCount() != 0 {
		t.Errorf("发布者掉线后流应清理，实际 %d", env.hub.StreamCount())
	}
}

// ---------------------------------------------------------------------------
// stats
// ---------------------------------------------------------------------------

func TestStatsHasNoResponse(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeStats, "st1", tssp.StatsReport{
		Token:       c.token,
		StreamID:    "whatever",
		Role:        tssp.RolePublisher,
		BitrateKbps: 1200,
		FPS:         30,
	})
	// stats 不回包：下一条应当是紧随其后的 list 响应。
	c.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: c.token})
	got := readEnv(t, c.conn)
	if got.Type != tssp.TypeOK {
		t.Fatalf("stats 不应产生响应，实际收到 %q", got.Type)
	}
	if got.ID != "l1" {
		t.Fatalf("期望 list 的响应 id=l1，实际 %q", got.ID)
	}
}

// ---------------------------------------------------------------------------
// Shutdown
// ---------------------------------------------------------------------------

func TestShutdownSendsBye(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	ctx, cancel := contextWithTimeout()
	defer cancel()
	go env.hub.Shutdown(ctx)

	ev := decodeData[tssp.ByeEvent](t, c.await(t, tssp.EventBye))
	if ev.Code == "" {
		t.Error("bye 事件缺少错误码")
	}
}
