package signaling

import (
	"context"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
	"github.com/pion/webrtc/v4"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/auth"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/sfu"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// dispatch 把一条请求分派到对应处理函数。
func (s *Session) dispatch(ctx context.Context, env *tssp.Envelope) {
	if env.Type != tssp.TypeHello && s.State() != stateAuthenticated {
		// 规范 §8.1：OPENED 状态下除 hello 外一律回 TOKEN_INVALID。
		s.replyError(env.ID, tssp.NewError(tssp.ErrTokenInvalid, "请先发送 hello 完成鉴权"))
		return
	}

	switch env.Type {
	case tssp.TypeHello:
		s.handleHello(ctx, env)
	case tssp.TypeSetup:
		s.handleSetup(env)
	case tssp.TypeUpdate:
		s.handleUpdate(env)
	case tssp.TypeStop:
		s.handleStop(env)
	case tssp.TypeList:
		s.handleList(env)
	case tssp.TypeSubscribe:
		s.handleSubscribe(env)
	case tssp.TypeUnsubscribe:
		s.handleUnsubscribe(env)
	case tssp.TypeRespondJoin:
		s.handleRespondJoin(env)
	case tssp.TypeSignaling:
		s.handleSignaling(env)
	case tssp.TypeRenew:
		s.handleRenew(ctx, env)
	case tssp.TypeStats:
		s.handleStats(env)
	default:
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "未知消息类型 "+env.Type))
	}
}

func (s *Session) handleHello(ctx context.Context, env *tssp.Envelope) {
	if s.State() == stateAuthenticated {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "该连接已完成鉴权"))
		return
	}

	var req tssp.HelloRequest
	if err := env.Decode(&req); err != nil {
		s.failHello(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	if req.Protocol != tssp.ProtocolVersion {
		s.failHello(env.ID, tssp.NewError(tssp.ErrUnsupportedProtocol,
			"服务端仅支持协议版本 1，收到 "+itoaSafe(req.Protocol)))
		return
	}
	if len(req.Capabilities.VideoCodecs) > 0 && !hasCommonCodec(req.Capabilities.VideoCodecs, s.hub.cfg.Media.VideoCodecs) {
		s.failHello(env.ID, tssp.NewError(tssp.ErrCodecNotSupported, "客户端与服务端没有共同的视频编解码"))
		return
	}

	verifyCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	ident, err := s.hub.verifier.Verify(verifyCtx, req.ServerAddr, req.UID, req.CLID, req.CID)
	cancel()
	if err != nil {
		s.failHello(env.ID, tssp.AsError(err))
		return
	}

	now := time.Now()
	token, exp, err := s.hub.signer.Sign(auth.Claims{
		SessionID:  s.id,
		UID:        ident.UID,
		CLID:       ident.CLID,
		CID:        ident.CID,
		ServerHash: ident.ServerHash,
	}, now)
	if err != nil {
		s.log().Error("签发令牌失败", "err", err)
		s.replyError(env.ID, tssp.NewError(tssp.ErrInternal, "签发令牌失败"))
		return
	}

	s.hub.limiter.Success(s.remoteAddr)
	s.setAuthenticated(ident, exp)
	s.hub.room(roomKey{server: ident.ServerHash, cid: ident.CID}).addSession(s)
	s.enrichLog("uid", ident.UID, "clid", ident.CLID, "cid", ident.CID)
	s.log().Info("会话鉴权通过", "nickname", ident.Nickname)

	s.replyOK(env.ID, tssp.HelloResponse{
		SessionID:    s.id,
		SessionToken: token,
		ExpiresAt:    exp.UnixMilli(),
		Nonce:        req.Nonce,
		Nickname:     ident.Nickname,
		Server:       s.hub.serverCaps(ident.UID, now),
	})
}

// failHello 回错并计入速率限制；超限后直接断开。
func (s *Session) failHello(id string, e *tssp.Error) {
	banned := s.hub.limiter.Fail(s.remoteAddr, time.Now())
	s.replyError(id, e)
	if banned {
		s.log().Warn("hello 失败次数超限，断开连接", "code", e.Code)
		s.closeWith(websocket.StatusPolicyViolation, tssp.ErrRateLimited)
	}
}

func (h *Hub) serverCaps(uid string, now time.Time) tssp.ServerCapabilities {
	defaultMode := tssp.ModeSFU
	if !h.cfg.ModeEnabled(config.ModeSFU) {
		defaultMode = tssp.ModeP2P
	}
	return tssp.ServerCapabilities{
		Modes:                h.cfg.ModeStrings(),
		DefaultMode:          defaultMode,
		VideoCodecs:          append([]string(nil), h.cfg.Media.VideoCodecs...),
		AudioCodecs:          append([]string(nil), h.cfg.Media.AudioCodecs...),
		MaxBitrateKbps:       h.cfg.Limits.MaxBitrateKbps,
		MaxStreamsPerChannel: h.cfg.Limits.MaxStreamsPerChannel,
		MaxViewersPerStream:  h.cfg.Limits.MaxViewersPerStream,
		ICEServers:           h.ice.Servers(uid, now),
	}
}

func (s *Session) handleSetup(env *tssp.Envelope) {
	var req tssp.SetupRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(req.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}

	mode := strings.ToLower(strings.TrimSpace(req.Mode))
	if mode == "" {
		mode = tssp.ModeSFU
	}
	if mode != tssp.ModeSFU && mode != tssp.ModeP2P {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "mode 只能是 sfu 或 p2p"))
		return
	}
	if !s.hub.cfg.ModeEnabled(config.Mode(mode)) {
		s.replyError(env.ID, tssp.NewError(tssp.ErrModeNotSupported, "服务端未开启 "+mode+" 模式"))
		return
	}

	streamType := strings.ToLower(strings.TrimSpace(req.StreamType))
	switch streamType {
	case "":
		streamType = tssp.StreamTypeScreen
	case tssp.StreamTypeScreen, tssp.StreamTypeWindow, tssp.StreamTypeCamera:
	default:
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "stream_type 非法"))
		return
	}

	access := strings.ToLower(strings.TrimSpace(req.Accessibility))
	switch access {
	case "":
		access = tssp.AccessibilityChannel
	case tssp.AccessibilityChannel, tssp.AccessibilityInviteOnly:
	default:
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "accessibility 非法"))
		return
	}

	if s.publishingStream() != "" {
		s.replyError(env.ID, tssp.NewError(tssp.ErrAlreadyPublishing, "该客户端已有活跃流，请先 stop"))
		return
	}

	key := roomKey{server: ident.ServerHash, cid: ident.CID}
	r := s.hub.room(key)
	if r.streamCount() >= s.hub.cfg.Limits.MaxStreamsPerChannel {
		s.replyError(env.ID, tssp.NewRetryError(tssp.ErrTooManyStreams, "频道内共享数量已达上限", 5000))
		return
	}

	st := &stream{
		id:            uuid.NewString(),
		room:          key,
		mode:          mode,
		streamType:    streamType,
		accessibility: access,
		name:          sanitizeName(req.Name),
		properties:    clampProperties(req.Properties, s.hub.cfg.Limits.MaxBitrateKbps),
		createdAt:     time.Now(),
		publisher:     s,
		pubCLID:       ident.CLID,
		pubUID:        ident.UID,
		pubNick:       ident.Nickname,
		subs:          make(map[int]*subscription),
	}

	if mode == tssp.ModeSFU {
		pub, err := s.hub.engine.AddPublisher(st.id, s.publisherSink(st.id))
		if err != nil {
			s.log().Error("创建 SFU 发布者失败", "err", err)
			s.replyError(env.ID, tssp.NewError(tssp.ErrInternal, "创建媒体会话失败"))
			return
		}
		st.sfuPub = pub
	}

	s.hub.registerStream(st)
	s.setPublishing(st.id)
	s.log().Info("开始共享", "stream_id", st.id, "mode", mode, "type", streamType, "accessibility", access)

	// 规范 §5.2：发布方向始终由发布客户端作为 offerer。
	s.replyOK(env.ID, tssp.SetupResponse{
		StreamID: st.id,
		Mode:     mode,
		Publish: tssp.PublishInstruction{
			Offerer:        tssp.RolePublisher,
			MaxBitrateKbps: s.hub.cfg.Limits.MaxBitrateKbps,
			VideoCodecs:    append([]string(nil), s.hub.cfg.Media.VideoCodecs...),
		},
	})

	r.broadcast(tssp.EventStreamAdded, tssp.StreamEvent{Stream: st.snapshot()}, s.id)
}

// publisherSink 构造 SFU 回调，把服务端信令发回发布客户端。
func (s *Session) publisherSink(streamID string) sfu.Sink {
	return sfu.Sink{
		Signal: func(out sfu.SignalOut) {
			s.send(tssp.TypeSignaling, "", tssp.SignalingMessage{
				StreamID:      streamID,
				Role:          tssp.RolePublisher,
				SignalingType: out.Type,
				SignalingData: out.Data,
			})
		},
		State: func(state webrtc.PeerConnectionState) {
			if state == webrtc.PeerConnectionStateFailed {
				s.log().Warn("发布者媒体连接失败", "stream_id", streamID)
				s.hub.teardownStream(streamID, tssp.ReasonFailed)
			}
		},
	}
}

func (s *Session) handleUpdate(env *tssp.Envelope) {
	var req tssp.UpdateRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(req.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}
	st, ok := s.hub.lookupStream(req.StreamID)
	if !ok {
		s.replyError(env.ID, tssp.NewError(tssp.ErrStreamNotFound, "流不存在"))
		return
	}
	if st.pubCLID != ident.CLID || st.publisher != s {
		s.replyError(env.ID, tssp.NewError(tssp.ErrNotStreamOwner, "只有发布者可以更新流"))
		return
	}

	st.mu.Lock()
	if req.Name != "" {
		st.name = sanitizeName(req.Name)
	}
	if len(req.Properties) > 0 {
		if st.properties == nil {
			st.properties = make(map[string]string, len(req.Properties))
		}
		for k, v := range clampProperties(req.Properties, s.hub.cfg.Limits.MaxBitrateKbps) {
			st.properties[k] = v
		}
	}
	st.mu.Unlock()

	snap := st.snapshot()
	s.replyOK(env.ID, tssp.StreamEvent{Stream: snap})
	if r := s.hub.existingRoom(st.room); r != nil {
		r.broadcast(tssp.EventStreamUpdated, tssp.StreamEvent{Stream: snap}, s.id)
	}
}

func (s *Session) handleStop(env *tssp.Envelope) {
	var req tssp.StopRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(req.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}
	st, ok := s.hub.lookupStream(req.StreamID)
	if !ok {
		s.replyError(env.ID, tssp.NewError(tssp.ErrStreamNotFound, "流不存在"))
		return
	}
	if st.pubCLID != ident.CLID || st.publisher != s {
		s.replyError(env.ID, tssp.NewError(tssp.ErrNotStreamOwner, "只有发布者可以停止流"))
		return
	}

	s.replyOK(env.ID, tssp.StreamRemovedEvent{StreamID: st.id, Reason: tssp.ReasonStopped})
	s.hub.teardownStream(st.id, tssp.ReasonStopped)
}

func (s *Session) handleList(env *tssp.Envelope) {
	var req tssp.ListRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(req.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}

	// 只允许查询自己所在频道，避免枚举整台服务器的共享情况。
	if req.CID != nil && *req.CID != ident.CID {
		s.replyError(env.ID, tssp.NewError(tssp.ErrNotSameChannel, "只能查询当前所在频道"))
		return
	}

	out := make([]tssp.Stream, 0, 4)
	if r := s.hub.existingRoom(roomKey{server: ident.ServerHash, cid: ident.CID}); r != nil {
		for _, st := range r.streamList() {
			out = append(out, st.snapshot())
		}
	}
	s.replyOK(env.ID, tssp.ListResponse{Streams: out})
}

func (s *Session) handleSubscribe(env *tssp.Envelope) {
	var req tssp.SubscribeRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(req.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}
	st, ok := s.hub.lookupStream(req.StreamID)
	if !ok {
		s.replyError(env.ID, tssp.NewError(tssp.ErrStreamNotFound, "流不存在"))
		return
	}
	if st.room.server != ident.ServerHash || st.room.cid != ident.CID {
		s.replyError(env.ID, tssp.NewError(tssp.ErrNotSameChannel, "需先进入发布者所在频道"))
		return
	}
	if st.pubCLID == ident.CLID {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "不能订阅自己发布的流"))
		return
	}
	if _, dup := st.subscription(ident.CLID); dup {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "已订阅该流"))
		return
	}
	if st.readySubscriberCount() >= s.hub.cfg.Limits.MaxViewersPerStream {
		s.replyError(env.ID, tssp.NewRetryError(tssp.ErrTooManyViewers, "观看人数已达上限", 5000))
		return
	}

	if st.accessibility == tssp.AccessibilityInviteOnly {
		st.setSubscription(ident.CLID, &subscription{
			session: s,
			state:   tssp.SubscribeStatePending,
			mode:    st.mode,
		})
		s.addSubscription(st.id)
		s.replyOK(env.ID, tssp.SubscribeResponse{StreamID: st.id, State: tssp.SubscribeStatePending})
		st.publisher.send(tssp.EventJoinRequest, "", tssp.JoinRequestEvent{
			StreamID: st.id,
			CLID:     ident.CLID,
			UID:      ident.UID,
			Nickname: ident.Nickname,
		})
		return
	}

	resp, err := s.hub.activateSubscription(st, s, ident)
	if err != nil {
		s.replyError(env.ID, tssp.AsError(err))
		return
	}
	s.replyOK(env.ID, resp)
}

// activateSubscription 让订阅进入 ready 状态，并按模式启动协商。
func (h *Hub) activateSubscription(st *stream, sub *Session, ident *auth.Identity) (tssp.SubscribeResponse, error) {
	st.setSubscription(ident.CLID, &subscription{
		session: sub,
		state:   tssp.SubscribeStateReady,
		mode:    st.mode,
	})
	sub.addSubscription(st.id)

	resp := tssp.SubscribeResponse{
		StreamID: st.id,
		State:    tssp.SubscribeStateReady,
		Mode:     st.mode,
	}

	switch st.mode {
	case tssp.ModeSFU:
		if st.sfuPub == nil {
			st.removeSubscription(ident.CLID)
			sub.removeSubscription(st.id)
			return resp, tssp.NewError(tssp.ErrInternal, "发布者媒体会话缺失")
		}
		// 规范 §5.9：SFU 订阅方向由服务端作为 offerer，AddSubscriber 内部会立即发出 offer。
		if _, err := st.sfuPub.AddSubscriber(subKey(ident.CLID), sub.subscriberSink(st.id)); err != nil {
			st.removeSubscription(ident.CLID)
			sub.removeSubscription(st.id)
			h.log.Error("创建 SFU 订阅者失败", "stream_id", st.id, "clid", ident.CLID, "err", err)
			return resp, tssp.NewError(tssp.ErrInternal, "创建媒体会话失败")
		}
	case tssp.ModeP2P:
		resp.Peer = &tssp.PeerRef{
			CLID:     st.pubCLID,
			UID:      st.pubUID,
			Nickname: st.pubNick,
		}
		// 规范 §5.6：P2P 由发布者作为 offerer，这里通知发布者有新观众。
		st.publisher.send(tssp.EventPeerJoined, "", tssp.PeerEvent{
			StreamID: st.id,
			CLID:     ident.CLID,
			UID:      ident.UID,
			Nickname: ident.Nickname,
		})
	}

	if r := h.existingRoom(st.room); r != nil {
		r.broadcast(tssp.EventStreamUpdated, tssp.StreamEvent{Stream: st.snapshot()})
	}
	return resp, nil
}

// subscriberSink 构造 SFU 回调，把服务端信令发回订阅客户端。
func (s *Session) subscriberSink(streamID string) sfu.Sink {
	return sfu.Sink{
		Signal: func(out sfu.SignalOut) {
			s.send(tssp.TypeSignaling, "", tssp.SignalingMessage{
				StreamID:      streamID,
				Role:          tssp.RoleSubscriber,
				SignalingType: out.Type,
				SignalingData: out.Data,
			})
		},
		State: func(state webrtc.PeerConnectionState) {
			if state == webrtc.PeerConnectionStateFailed {
				s.log().Warn("订阅者媒体连接失败", "stream_id", streamID)
				s.hub.detachSubscriber(s, streamID, tssp.ReasonFailed, true)
			}
		},
	}
}

func (s *Session) handleUnsubscribe(env *tssp.Envelope) {
	var req tssp.UnsubscribeRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	if _, aerr := s.authorize(req.Token); aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}
	if _, ok := s.hub.lookupStream(req.StreamID); !ok {
		s.replyError(env.ID, tssp.NewError(tssp.ErrStreamNotFound, "流不存在"))
		return
	}
	s.hub.detachSubscriber(s, req.StreamID, tssp.ReasonUnsubscribed, false)
	s.replyOK(env.ID, tssp.StreamRemovedEvent{StreamID: req.StreamID, Reason: tssp.ReasonUnsubscribed})
}

func (s *Session) handleRespondJoin(env *tssp.Envelope) {
	var req tssp.RespondJoinRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(req.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}
	st, ok := s.hub.lookupStream(req.StreamID)
	if !ok {
		s.replyError(env.ID, tssp.NewError(tssp.ErrStreamNotFound, "流不存在"))
		return
	}
	if st.pubCLID != ident.CLID || st.publisher != s {
		s.replyError(env.ID, tssp.NewError(tssp.ErrNotStreamOwner, "只有发布者可以审批观看请求"))
		return
	}
	sub, ok := st.subscription(req.CLID)
	if !ok || sub.state != tssp.SubscribeStatePending {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, "没有待审批的观看请求"))
		return
	}

	if !req.Accept {
		st.removeSubscription(req.CLID)
		sub.session.removeSubscription(st.id)
		sub.session.send(tssp.EventJoinRejected, "", tssp.JoinRejectedEvent{
			StreamID: st.id,
			Reason:   sanitizeName(req.Reason),
		})
		s.replyOK(env.ID, nil)
		return
	}

	subIdent := sub.session.Identity()
	if subIdent == nil {
		st.removeSubscription(req.CLID)
		s.replyError(env.ID, tssp.NewError(tssp.ErrClientNotFound, "该观看者已离线"))
		return
	}
	resp, err := s.hub.activateSubscription(st, sub.session, subIdent)
	if err != nil {
		s.replyError(env.ID, tssp.AsError(err))
		return
	}
	s.replyOK(env.ID, nil)
	sub.session.send(tssp.EventSubscribeReady, "", tssp.SubscribeReadyEvent{
		StreamID: resp.StreamID,
		Mode:     resp.Mode,
		Peer:     resp.Peer,
	})
}

func (s *Session) handleSignaling(env *tssp.Envelope) {
	var msg tssp.SignalingMessage
	if err := env.Decode(&msg); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	ident, aerr := s.authorize(msg.Token)
	if aerr != nil {
		s.replyError(env.ID, aerr)
		return
	}
	st, ok := s.hub.lookupStream(msg.StreamID)
	if !ok {
		s.replyError(env.ID, tssp.NewError(tssp.ErrStreamNotFound, "流不存在"))
		return
	}

	isPublisher := st.pubCLID == ident.CLID && st.publisher == s
	sub, isSubscriber := st.subscription(ident.CLID)
	if !isPublisher && (!isSubscriber || sub.state != tssp.SubscribeStateReady) {
		s.replyError(env.ID, tssp.NewError(tssp.ErrNotAllowed, "未参与该流的媒体会话"))
		return
	}

	if st.mode == tssp.ModeP2P {
		s.relayP2P(env.ID, st, ident, isPublisher, &msg)
		return
	}

	// SFU 模式：信令的对端就是服务端自身。
	if st.sfuPub == nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrInternal, "发布者媒体会话缺失"))
		return
	}
	var err error
	if isPublisher {
		err = st.sfuPub.HandleSignal(msg.SignalingType, msg.SignalingData)
	} else {
		sfuSub, ok := st.sfuPub.Subscriber(subKey(ident.CLID))
		if !ok {
			s.replyError(env.ID, tssp.NewError(tssp.ErrSignalingFailed, "订阅媒体会话不存在"))
			return
		}
		err = sfuSub.HandleSignal(msg.SignalingType, msg.SignalingData)
	}
	if err != nil {
		s.log().Warn("处理信令失败", "stream_id", st.id, "type", msg.SignalingType, "err", err)
		s.replyError(env.ID, tssp.NewError(tssp.ErrSignalingFailed, err.Error()))
		return
	}
	s.replyOK(env.ID, nil)
}

// relayP2P 在发布者与订阅者之间转发 SDP/ICE，服务端不参与媒体。
func (s *Session) relayP2P(reqID string, st *stream, ident *auth.Identity, isPublisher bool, msg *tssp.SignalingMessage) {
	var target *Session
	if isPublisher {
		if msg.PeerCLID <= 0 {
			s.replyError(reqID, tssp.NewError(tssp.ErrBadRequest, "P2P 模式下 peer_clid 必填"))
			return
		}
		sub, ok := st.subscription(msg.PeerCLID)
		if !ok || sub.state != tssp.SubscribeStateReady {
			s.replyError(reqID, tssp.NewError(tssp.ErrSignalingFailed, "对端不在该流的观看者列表中"))
			return
		}
		target = sub.session
	} else {
		if msg.PeerCLID != 0 && msg.PeerCLID != st.pubCLID {
			s.replyError(reqID, tssp.NewError(tssp.ErrBadRequest, "订阅者只能与发布者协商"))
			return
		}
		target = st.publisher
	}

	role := tssp.RoleSubscriber
	if isPublisher {
		role = tssp.RolePublisher
	}
	target.send(tssp.TypeSignaling, "", tssp.SignalingMessage{
		StreamID:      st.id,
		PeerCLID:      ident.CLID,
		Role:          role,
		SignalingType: msg.SignalingType,
		SignalingData: msg.SignalingData,
	})
	s.replyOK(reqID, nil)
}

func (s *Session) handleRenew(ctx context.Context, env *tssp.Envelope) {
	var req tssp.RenewRequest
	if err := env.Decode(&req); err != nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrBadRequest, err.Error()))
		return
	}
	old := s.Identity()
	if old == nil {
		s.replyError(env.ID, tssp.NewError(tssp.ErrTokenInvalid, "尚未完成 hello 鉴权"))
		return
	}
	// 续签允许令牌已过期（这正是续签的用途），但必须签名有效且属于本连接。
	if claims, err := s.hub.signer.Verify(req.Token, time.Now()); err != nil {
		te := tssp.AsError(err)
		if te.Code != tssp.ErrTokenExpired {
			s.replyError(env.ID, te)
			return
		}
	} else if claims.SessionID != s.id {
		s.replyError(env.ID, tssp.NewError(tssp.ErrTokenInvalid, "令牌与当前连接不匹配"))
		return
	}

	clid := req.CLID
	if clid == 0 {
		clid = old.CLID
	}
	verifyCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	ident, err := s.hub.verifier.Verify(verifyCtx, old.ServerAddr, old.UID, clid, req.CID)
	cancel()
	if err != nil {
		s.replyError(env.ID, tssp.AsError(err))
		return
	}

	now := time.Now()
	token, exp, err := s.hub.signer.Sign(auth.Claims{
		SessionID:  s.id,
		UID:        ident.UID,
		CLID:       ident.CLID,
		CID:        ident.CID,
		ServerHash: ident.ServerHash,
	}, now)
	if err != nil {
		s.log().Error("续签失败", "err", err)
		s.replyError(env.ID, tssp.NewError(tssp.ErrInternal, "续签失败"))
		return
	}

	channelChanged := ident.CID != old.CID
	if channelChanged {
		s.hub.moveSession(s, old, ident)
	}
	s.updateIdentity(ident, exp)

	s.replyOK(env.ID, tssp.RenewResponse{
		SessionToken: token,
		ExpiresAt:    exp.UnixMilli(),
		ICEServers:   s.hub.ice.Servers(ident.UID, now),
	})
	if channelChanged {
		s.log().Info("客户端换频道，已清理跨频道会话", "old_cid", old.CID, "new_cid", ident.CID)
	}
}

// moveSession 处理换频道：清理旧频道的发布与订阅，再加入新房间。
func (h *Hub) moveSession(s *Session, old, next *auth.Identity) {
	for _, streamID := range s.subscriptionList() {
		h.detachSubscriber(s, streamID, tssp.ReasonChannelChanged, true)
	}
	if streamID := s.publishingStream(); streamID != "" {
		h.teardownStream(streamID, tssp.ReasonChannelChanged)
	}
	if r := h.existingRoom(roomKey{server: old.ServerHash, cid: old.CID}); r != nil {
		r.removeSession(s.id)
		h.gcRoom(r)
	}
	h.room(roomKey{server: next.ServerHash, cid: next.CID}).addSession(s)
}

func (s *Session) handleStats(env *tssp.Envelope) {
	var req tssp.StatsReport
	if err := env.Decode(&req); err != nil {
		// stats 无响应，解析失败只记日志。
		s.log().Debug("解析 stats 失败", "err", err)
		return
	}
	if _, aerr := s.authorize(req.Token); aerr != nil {
		return
	}
	s.log().Debug("客户端质量上报",
		"stream_id", req.StreamID,
		"role", req.Role,
		"bitrate_kbps", req.BitrateKbps,
		"fps", req.FPS,
		"packet_loss", req.PacketLoss,
		"rtt_ms", req.RTTMS,
	)
}

// teardownStream 关闭一路流：清理媒体会话、通知所有参与者、广播 stream_removed。
func (h *Hub) teardownStream(streamID, reason string) {
	st, ok := h.lookupStream(streamID)
	if !ok {
		return
	}
	h.unregisterStream(st)

	if st.sfuPub != nil {
		st.sfuPub.Close()
	}
	for _, sub := range st.subscriptions() {
		st.removeSubscription(sub.session.CLID())
		sub.session.removeSubscription(st.id)
		sub.session.send(tssp.EventRemovedFromStream, "", tssp.RemovedFromStreamEvent{
			StreamID: st.id,
			Reason:   reason,
		})
	}
	if st.publisher != nil && st.publisher.publishingStream() == st.id {
		st.publisher.setPublishing("")
	}

	if r := h.existingRoom(st.room); r != nil {
		r.broadcast(tssp.EventStreamRemoved, tssp.StreamRemovedEvent{
			StreamID: st.id,
			Reason:   reason,
		})
	}
	h.log.Info("流已关闭", "stream_id", st.id, "reason", reason)
}

// detachSubscriber 移除一个订阅者。notify 为 true 时向该订阅者发送 removed_from_stream。
func (h *Hub) detachSubscriber(s *Session, streamID, reason string, notify bool) {
	s.removeSubscription(streamID)
	st, ok := h.lookupStream(streamID)
	if !ok {
		return
	}
	clid := s.CLID()
	sub, existed := st.removeSubscription(clid)
	if !existed {
		return
	}

	if st.sfuPub != nil {
		st.sfuPub.RemoveSubscriber(subKey(clid))
	}
	if st.mode == tssp.ModeP2P && sub.state == tssp.SubscribeStateReady && st.publisher != nil {
		st.publisher.send(tssp.EventPeerLeft, "", tssp.PeerEvent{
			StreamID: st.id,
			CLID:     clid,
			Reason:   reason,
		})
	}
	if notify {
		s.send(tssp.EventRemovedFromStream, "", tssp.RemovedFromStreamEvent{
			StreamID: st.id,
			Reason:   reason,
		})
	}
	if r := h.existingRoom(st.room); r != nil {
		r.broadcast(tssp.EventStreamUpdated, tssp.StreamEvent{Stream: st.snapshot()})
	}
}
