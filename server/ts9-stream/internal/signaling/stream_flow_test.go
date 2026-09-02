package signaling

import (
	"testing"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// ---------------------------------------------------------------------------
// subscribe / unsubscribe
// ---------------------------------------------------------------------------

func TestSubscribeChannelVisibleStream(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityChannel, "screen-a")
	b.await(t, tssp.EventStreamAdded)

	sub := b.subscribe(t, resp.StreamID)
	if sub.State != tssp.SubscribeStateReady {
		t.Fatalf("期望 ready，实际 %q", sub.State)
	}
	if sub.Mode != tssp.ModeP2P {
		t.Errorf("期望 p2p，实际 %q", sub.Mode)
	}
	if sub.Peer == nil {
		t.Fatal("P2P 模式应返回对端信息")
	}
	if sub.Peer.CLID != 7 || sub.Peer.UID != "uidA=" || sub.Peer.Nickname != "Alice" {
		t.Errorf("对端信息不正确: %+v", *sub.Peer)
	}

	joined := decodeData[tssp.PeerEvent](t, a.await(t, tssp.EventPeerJoined))
	if joined.CLID != 8 || joined.Nickname != "Bob" || joined.StreamID != resp.StreamID {
		t.Errorf("peer_joined 事件不正确: %+v", joined)
	}

	// 激活订阅后房间会广播 stream_updated，观看人数应为 1。
	ev := decodeData[tssp.StreamEvent](t, a.await(t, tssp.EventStreamUpdated))
	if ev.Stream.ViewerCount != 1 {
		t.Errorf("观看人数应为 1，实际 %d", ev.Stream.ViewerCount)
	}
}

func TestSubscribeRejectsUnknownStream(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeSubscribe, "sub1", tssp.SubscribeRequest{Token: c.token, StreamID: "ghost"})
	c.expectErr(t, tssp.ErrStreamNotFound)
}

func TestSubscribeRejectsOtherChannel(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	outsider := env.connect(t, 9, 99)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")

	outsider.req(t, tssp.TypeSubscribe, "sub1", tssp.SubscribeRequest{Token: outsider.token, StreamID: resp.StreamID})
	outsider.expectErr(t, tssp.ErrNotSameChannel)
}

func TestSubscribeRejectsOwnStream(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")

	a.req(t, tssp.TypeSubscribe, "sub1", tssp.SubscribeRequest{Token: a.token, StreamID: resp.StreamID})
	a.expectErr(t, tssp.ErrBadRequest)
}

func TestSubscribeRejectsDuplicate(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)

	b.req(t, tssp.TypeSubscribe, "sub2", tssp.SubscribeRequest{Token: b.token, StreamID: resp.StreamID})
	b.expectErr(t, tssp.ErrBadRequest)
}

func TestSubscribeRejectsWhenViewerLimitReached(t *testing.T) {
	env := newTestEnv(t, func(c *config.Config) {
		c.Limits.MaxViewersPerStream = 1
	})
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)
	d := env.connect(t, 10, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	d.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)

	d.req(t, tssp.TypeSubscribe, "sub-d", tssp.SubscribeRequest{Token: d.token, StreamID: resp.StreamID})
	e := d.expectErr(t, tssp.ErrTooManyViewers)
	if e.RetryAfterMS <= 0 {
		t.Errorf("期望带 retry_after_ms，实际 %d", e.RetryAfterMS)
	}
}

func TestUnsubscribeNotifiesPublisher(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)

	b.req(t, tssp.TypeUnsubscribe, "un1", tssp.UnsubscribeRequest{Token: b.token, StreamID: resp.StreamID})
	removed := decodeData[tssp.StreamRemovedEvent](t, b.awaitOK(t))
	if removed.Reason != tssp.ReasonUnsubscribed {
		t.Errorf("原因应为 unsubscribed，实际 %q", removed.Reason)
	}

	left := decodeData[tssp.PeerEvent](t, a.await(t, tssp.EventPeerLeft))
	if left.CLID != 8 || left.Reason != tssp.ReasonUnsubscribed {
		t.Errorf("peer_left 事件不正确: %+v", left)
	}

	// 退订后可以再次订阅。
	b.subscribe(t, resp.StreamID)
}

func TestUnsubscribeRejectsUnknownStream(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeUnsubscribe, "un1", tssp.UnsubscribeRequest{Token: c.token, StreamID: "ghost"})
	c.expectErr(t, tssp.ErrStreamNotFound)
}

func TestSubscriberDisconnectNotifiesPublisher(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)

	if err := b.conn.CloseNow(); err != nil {
		t.Fatalf("关闭订阅者连接失败: %v", err)
	}

	left := decodeData[tssp.PeerEvent](t, a.await(t, tssp.EventPeerLeft))
	if left.CLID != 8 || left.Reason != tssp.ReasonDisconnected {
		t.Errorf("peer_left 事件不正确: %+v", left)
	}
}

// ---------------------------------------------------------------------------
// invite_only 审批
// ---------------------------------------------------------------------------

func TestInviteOnlySubscribePending(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityInviteOnly, "screen-a")
	b.await(t, tssp.EventStreamAdded)

	sub := b.subscribe(t, resp.StreamID)
	if sub.State != tssp.SubscribeStatePending {
		t.Fatalf("invite_only 应先 pending，实际 %q", sub.State)
	}
	if sub.Peer != nil {
		t.Error("pending 阶段不应下发对端信息")
	}

	jr := decodeData[tssp.JoinRequestEvent](t, a.await(t, tssp.EventJoinRequest))
	if jr.CLID != 8 || jr.UID != "uidB=" || jr.Nickname != "Bob" || jr.StreamID != resp.StreamID {
		t.Errorf("join_request 事件不正确: %+v", jr)
	}
}

func TestInviteOnlyAccept(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityInviteOnly, "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventJoinRequest)

	a.req(t, tssp.TypeRespondJoin, "rj1", tssp.RespondJoinRequest{
		Token:    a.token,
		StreamID: resp.StreamID,
		CLID:     8,
		Accept:   true,
	})
	a.awaitOK(t)

	ready := decodeData[tssp.SubscribeReadyEvent](t, b.await(t, tssp.EventSubscribeReady))
	if ready.StreamID != resp.StreamID {
		t.Errorf("subscribe_ready 的 stream_id 不匹配: %q", ready.StreamID)
	}
	if ready.Mode != tssp.ModeP2P {
		t.Errorf("模式应为 p2p，实际 %q", ready.Mode)
	}
	if ready.Peer == nil || ready.Peer.CLID != 7 {
		t.Errorf("subscribe_ready 缺少发布者信息: %+v", ready.Peer)
	}

	// 审批通过后 P2P 信令应可正常互发。
	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      resp.StreamID,
		PeerCLID:      8,
		SignalingType: tssp.SignalingOffer,
		SignalingData: "v=0",
	})
	a.awaitOK(t)
	relayed := decodeData[tssp.SignalingMessage](t, b.await(t, tssp.TypeSignaling))
	if relayed.SignalingData != "v=0" || relayed.Role != tssp.RolePublisher {
		t.Errorf("转发的信令不正确: %+v", relayed)
	}
}

func TestInviteOnlyReject(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityInviteOnly, "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventJoinRequest)

	a.req(t, tssp.TypeRespondJoin, "rj1", tssp.RespondJoinRequest{
		Token:    a.token,
		StreamID: resp.StreamID,
		CLID:     8,
		Accept:   false,
		Reason:   "现在不方便",
	})
	a.awaitOK(t)

	rejected := decodeData[tssp.JoinRejectedEvent](t, b.await(t, tssp.EventJoinRejected))
	if rejected.StreamID != resp.StreamID {
		t.Errorf("join_rejected 的 stream_id 不匹配: %q", rejected.StreamID)
	}
	if rejected.Reason != "现在不方便" {
		t.Errorf("拒绝理由未透传，实际 %q", rejected.Reason)
	}

	// 被拒后订阅记录已清空，可以重新申请。
	sub := b.subscribe(t, resp.StreamID)
	if sub.State != tssp.SubscribeStatePending {
		t.Errorf("重新申请应回到 pending，实际 %q", sub.State)
	}
}

func TestRespondJoinRejectsNonOwner(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)
	d := env.connect(t, 10, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityInviteOnly, "screen-a")
	b.await(t, tssp.EventStreamAdded)
	d.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventJoinRequest)

	d.req(t, tssp.TypeRespondJoin, "rj1", tssp.RespondJoinRequest{
		Token:    d.token,
		StreamID: resp.StreamID,
		CLID:     8,
		Accept:   true,
	})
	d.expectErr(t, tssp.ErrNotStreamOwner)
}

func TestRespondJoinRejectsWithoutPendingRequest(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityInviteOnly, "screen-a")

	a.req(t, tssp.TypeRespondJoin, "rj1", tssp.RespondJoinRequest{
		Token:    a.token,
		StreamID: resp.StreamID,
		CLID:     8,
		Accept:   true,
	})
	a.expectErr(t, tssp.ErrBadRequest)
}

func TestRespondJoinRejectsUnknownStream(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)

	a.req(t, tssp.TypeRespondJoin, "rj1", tssp.RespondJoinRequest{
		Token:    a.token,
		StreamID: "ghost",
		CLID:     8,
	})
	a.expectErr(t, tssp.ErrStreamNotFound)
}

// ---------------------------------------------------------------------------
// P2P 信令中转
// ---------------------------------------------------------------------------

func TestP2PSignalingRoundTrip(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)
	a.await(t, tssp.EventStreamUpdated)

	// 发布者 → 订阅者
	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      resp.StreamID,
		PeerCLID:      8,
		SignalingType: tssp.SignalingOffer,
		SignalingData: "offer-sdp",
	})
	a.awaitOK(t)
	toB := decodeData[tssp.SignalingMessage](t, b.await(t, tssp.TypeSignaling))
	if toB.Role != tssp.RolePublisher {
		t.Errorf("角色应为 publisher，实际 %q", toB.Role)
	}
	if toB.PeerCLID != 7 {
		t.Errorf("peer_clid 应为发送方 7，实际 %d", toB.PeerCLID)
	}
	if toB.SignalingType != tssp.SignalingOffer || toB.SignalingData != "offer-sdp" {
		t.Errorf("信令内容被篡改: %+v", toB)
	}

	// 订阅者 → 发布者（peer_clid 可省略）
	b.req(t, tssp.TypeSignaling, "sig2", tssp.SignalingMessage{
		Token:         b.token,
		StreamID:      resp.StreamID,
		SignalingType: tssp.SignalingAnswer,
		SignalingData: "answer-sdp",
	})
	b.awaitOK(t)
	toA := decodeData[tssp.SignalingMessage](t, a.await(t, tssp.TypeSignaling))
	if toA.Role != tssp.RoleSubscriber {
		t.Errorf("角色应为 subscriber，实际 %q", toA.Role)
	}
	if toA.PeerCLID != 8 {
		t.Errorf("peer_clid 应为发送方 8，实际 %d", toA.PeerCLID)
	}
	if toA.SignalingData != "answer-sdp" {
		t.Errorf("信令内容被篡改: %+v", toA)
	}
}

func TestP2PPublisherRequiresPeerCLID(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)

	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      resp.StreamID,
		SignalingType: tssp.SignalingOffer,
		SignalingData: "offer-sdp",
	})
	a.expectErr(t, tssp.ErrBadRequest)
}

func TestP2PPublisherRejectsUnknownPeer(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)

	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      resp.StreamID,
		PeerCLID:      4242,
		SignalingType: tssp.SignalingCandidate,
		SignalingData: "cand",
	})
	a.expectErr(t, tssp.ErrSignalingFailed)
}

func TestP2PSubscriberCannotTargetOtherPeer(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)

	b.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         b.token,
		StreamID:      resp.StreamID,
		PeerCLID:      99,
		SignalingType: tssp.SignalingCandidate,
		SignalingData: "cand",
	})
	b.expectErr(t, tssp.ErrBadRequest)
}

func TestSignalingRejectsNonParticipant(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	d := env.connect(t, 10, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	d.await(t, tssp.EventStreamAdded)

	d.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         d.token,
		StreamID:      resp.StreamID,
		SignalingType: tssp.SignalingCandidate,
		SignalingData: "cand",
	})
	d.expectErr(t, tssp.ErrNotAllowed)
}

func TestSignalingRejectsPendingSubscriber(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, tssp.AccessibilityInviteOnly, "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)

	b.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         b.token,
		StreamID:      resp.StreamID,
		SignalingType: tssp.SignalingAnswer,
		SignalingData: "answer",
	})
	b.expectErr(t, tssp.ErrNotAllowed)
}

func TestSignalingRejectsUnknownStream(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)

	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      "ghost",
		SignalingType: tssp.SignalingCandidate,
	})
	a.expectErr(t, tssp.ErrStreamNotFound)
}

// ---------------------------------------------------------------------------
// SFU 信令（不建立真实 ICE，仅验证会话路由与错误分支）
// ---------------------------------------------------------------------------

func TestSFUPublisherRejectsAnswer(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)

	resp := a.setup(t, tssp.ModeSFU, "", "screen-a")

	// SFU 发布方向由客户端 offer，服务端不接受来自发布者的 answer。
	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      resp.StreamID,
		SignalingType: tssp.SignalingAnswer,
		SignalingData: "v=0",
	})
	a.expectErr(t, tssp.ErrSignalingFailed)
}

func TestSFUPublisherRejectsMalformedOffer(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)

	resp := a.setup(t, tssp.ModeSFU, "", "screen-a")

	a.req(t, tssp.TypeSignaling, "sig1", tssp.SignalingMessage{
		Token:         a.token,
		StreamID:      resp.StreamID,
		SignalingType: tssp.SignalingOffer,
		SignalingData: "definitely-not-sdp",
	})
	a.expectErr(t, tssp.ErrSignalingFailed)
}

// ---------------------------------------------------------------------------
// renew
// ---------------------------------------------------------------------------

func TestRenewIssuesNewToken(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: c.token, CLID: c.clid, CID: c.cid})
	resp := decodeData[tssp.RenewResponse](t, c.awaitOK(t))
	if resp.SessionToken == "" {
		t.Fatal("续签未返回新令牌")
	}
	if resp.ExpiresAt <= 0 {
		t.Error("expires_at 应为正的毫秒时间戳")
	}

	// 旧令牌依旧属于同一会话，因此仍可用；新令牌也必须可用。
	c.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: resp.SessionToken})
	c.awaitOK(t)
}

func TestRenewRejectsWithoutHello(t *testing.T) {
	env := newTestEnv(t, nil)
	conn := env.dial(t)

	sendReq(t, conn, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: "v1.x.y"})
	// dispatch 在鉴权前就会挡下非 hello 消息。
	expectErrCode(t, conn, tssp.ErrTokenInvalid)
}

func TestRenewRejectsForeignToken(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	b.req(t, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: a.token, CLID: b.clid, CID: b.cid})
	b.expectErr(t, tssp.ErrTokenInvalid)
}

func TestRenewRejectsGarbageToken(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	c.req(t, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: "not-a-token", CLID: c.clid, CID: c.cid})
	c.expectErr(t, tssp.ErrTokenInvalid)
}

func TestRenewRejectsStaleChannel(t *testing.T) {
	env := newTestEnv(t, nil)
	c := env.connect(t, 7, 12)

	// 客户端谎称换到了 99 频道，但 ServerQuery 仍显示在 12。
	c.req(t, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: c.token, CLID: c.clid, CID: 99})
	c.expectErr(t, tssp.ErrIdentityMismatch)
}

func TestRenewChannelChangeCleansUpStreams(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	// b 先订阅 a 的流，随后 b 换频道，订阅与广播都应被清理。
	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)
	b.subscribe(t, resp.StreamID)
	a.await(t, tssp.EventPeerJoined)
	a.await(t, tssp.EventStreamUpdated)

	env.fq.setCID(8, 77)
	b.req(t, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: b.token, CLID: 8, CID: 77})

	removed := decodeData[tssp.RemovedFromStreamEvent](t, b.await(t, tssp.EventRemovedFromStream))
	if removed.StreamID != resp.StreamID || removed.Reason != tssp.ReasonChannelChanged {
		t.Errorf("换频道时应收到 channel_changed 移除事件: %+v", removed)
	}
	newTok := decodeData[tssp.RenewResponse](t, b.await(t, tssp.TypeOK))
	if newTok.SessionToken == "" {
		t.Fatal("续签未返回新令牌")
	}

	left := decodeData[tssp.PeerEvent](t, a.await(t, tssp.EventPeerLeft))
	if left.CLID != 8 || left.Reason != tssp.ReasonChannelChanged {
		t.Errorf("发布者应收到 channel_changed 的 peer_left: %+v", left)
	}

	// b 已在 77 频道，用新令牌查询自己的频道应看不到旧流。
	b.req(t, tssp.TypeList, "l1", tssp.ListRequest{Token: newTok.SessionToken})
	list := decodeData[tssp.ListResponse](t, b.await(t, tssp.TypeOK))
	if len(list.Streams) != 0 {
		t.Errorf("新频道内不应有流，实际 %d", len(list.Streams))
	}

	// 旧频道的流仍归 a 所有。
	if env.hub.StreamCount() != 1 {
		t.Errorf("发布者的流应保留，实际 %d", env.hub.StreamCount())
	}

	// 用新令牌再订阅旧流会因不同频道被拒。
	b.req(t, tssp.TypeSubscribe, "sub2", tssp.SubscribeRequest{Token: newTok.SessionToken, StreamID: resp.StreamID})
	b.expectErr(t, tssp.ErrNotSameChannel)
}

func TestRenewChannelChangeTearsDownOwnStream(t *testing.T) {
	env := newTestEnv(t, nil)
	a := env.connect(t, 7, 12)
	b := env.connect(t, 8, 12)

	resp := a.setup(t, tssp.ModeP2P, "", "screen-a")
	b.await(t, tssp.EventStreamAdded)

	env.fq.setCID(7, 55)
	a.req(t, tssp.TypeRenew, "r1", tssp.RenewRequest{Token: a.token, CLID: 7, CID: 55})
	a.await(t, tssp.TypeOK)

	ev := decodeData[tssp.StreamRemovedEvent](t, b.await(t, tssp.EventStreamRemoved))
	if ev.StreamID != resp.StreamID || ev.Reason != tssp.ReasonChannelChanged {
		t.Errorf("原频道应收到 channel_changed 的 stream_removed: %+v", ev)
	}
	if env.hub.StreamCount() != 0 {
		t.Errorf("换频道后自己的流应被拆除，实际 %d", env.hub.StreamCount())
	}

	// 在新频道可以重新开流。
	a.setup(t, tssp.ModeP2P, "", "screen-a2")
}
