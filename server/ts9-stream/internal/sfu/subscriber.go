package sfu

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"

	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
)

// Subscriber 是一路流的一个观看者在 SFU 侧的表示。
//
// 订阅方向由服务端作为 offerer（见规范 §5.9），因此创建后立即生成 offer。
type Subscriber struct {
	pub  *Publisher
	key  string
	log  *slog.Logger
	sink Sink

	pc *webrtc.PeerConnection

	mu      sync.Mutex
	senders map[string]*webrtc.RTPSender
	closed  bool
	// negotiating 防止在一次协商未完成时重复发 offer。
	negotiating        bool
	pendingRenegotiate bool
}

// AddSubscriber 为指定订阅者建立 PeerConnection 并发出首个 offer。
// key 需在同一路流内唯一，通常用订阅者的 clid。
func (p *Publisher) AddSubscriber(key string, sink Sink) (*Subscriber, error) {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, errors.New("发布者已关闭")
	}
	if _, dup := p.subs[key]; dup {
		p.mu.Unlock()
		return nil, fmt.Errorf("订阅者 %s 已存在", key)
	}
	p.mu.Unlock()

	pc, err := p.engine.api.NewPeerConnection(p.engine.rtcCfg)
	if err != nil {
		return nil, fmt.Errorf("创建订阅者 PeerConnection 失败: %w", err)
	}

	s := &Subscriber{
		pub:     p,
		key:     key,
		log:     p.log.With("subscriber", key, "role", "subscriber"),
		sink:    sink,
		pc:      pc,
		senders: make(map[string]*webrtc.RTPSender),
	}

	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c == nil {
			s.emit(SignalOut{Type: SignalEndOfCandidates})
			return
		}
		s.emit(SignalOut{Type: SignalCandidate, Data: marshalCandidate(c)})
	})
	pc.OnConnectionStateChange(func(st webrtc.PeerConnectionState) {
		s.log.Debug("订阅者连接状态变化", "state", st.String())
		if st == webrtc.PeerConnectionStateConnected {
			// 新订阅者接入，向发布者索取关键帧，避免长时间黑屏。
			p.RequestKeyFrame()
		}
		if sink.State != nil {
			sink.State(st)
		}
	})

	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		_ = pc.Close()
		return nil, errors.New("发布者已关闭")
	}
	p.subs[key] = s
	tracks := make([]*forwardTrack, 0, len(p.tracks))
	for _, ft := range p.tracks {
		tracks = append(tracks, ft)
	}
	p.mu.Unlock()

	for _, ft := range tracks {
		if err := s.attachLocked(ft, false); err != nil {
			s.Close()
			return nil, err
		}
	}

	if err := s.renegotiate(); err != nil {
		s.Close()
		return nil, err
	}
	return s, nil
}

// Subscriber 返回指定订阅者。
func (p *Publisher) Subscriber(key string) (*Subscriber, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	s, ok := p.subs[key]
	return s, ok
}

// RemoveSubscriber 关闭并移除订阅者。
func (p *Publisher) RemoveSubscriber(key string) {
	p.mu.Lock()
	s, ok := p.subs[key]
	if ok {
		delete(p.subs, key)
	}
	p.mu.Unlock()
	if ok {
		s.closeInternal()
	}
}

// SubscriberCount 返回当前订阅者数量。
func (p *Publisher) SubscriberCount() int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return len(p.subs)
}

func (s *Subscriber) emit(out SignalOut) {
	if s.sink.Signal != nil {
		s.sink.Signal(out)
	}
}

// attach 在发布者新增轨道时把轨道挂到订阅者并重新协商。
func (s *Subscriber) attach(ft *forwardTrack) error {
	if err := s.attachLocked(ft, true); err != nil {
		return err
	}
	return s.renegotiate()
}

func (s *Subscriber) attachLocked(ft *forwardTrack, skipIfClosed bool) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		if skipIfClosed {
			return nil
		}
		return errors.New("订阅者已关闭")
	}
	id := ft.local.ID()
	if _, dup := s.senders[id]; dup {
		s.mu.Unlock()
		return nil
	}
	s.mu.Unlock()

	sender, err := s.pc.AddTrack(ft.local)
	if err != nil {
		return fmt.Errorf("向订阅者添加轨道失败: %w", err)
	}

	s.mu.Lock()
	s.senders[id] = sender
	s.mu.Unlock()

	go s.drainRTCP(sender, ft)
	return nil
}

// detach 在发布轨道结束时移除对应 sender。
func (s *Subscriber) detach(trackID string) {
	s.mu.Lock()
	sender, ok := s.senders[trackID]
	if ok {
		delete(s.senders, trackID)
	}
	closed := s.closed
	s.mu.Unlock()
	if !ok || closed {
		return
	}
	if err := s.pc.RemoveTrack(sender); err != nil {
		s.log.Debug("移除订阅者轨道失败", "err", err)
		return
	}
	if err := s.renegotiate(); err != nil {
		s.log.Debug("移除轨道后重新协商失败", "err", err)
	}
}

// drainRTCP 读取订阅者上行的 RTCP。
//
// 必须持续读取，否则 pion 的拦截器无法处理 NACK 与接收报告。
// 订阅者的 PLI/FIR 需要转发给发布者，才能触发真正的关键帧。
func (s *Subscriber) drainRTCP(sender *webrtc.RTPSender, ft *forwardTrack) {
	for {
		pkts, _, err := sender.ReadRTCP()
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrClosedPipe) {
				s.log.Debug("读取订阅者 RTCP 结束", "err", err)
			}
			return
		}
		for _, pkt := range pkts {
			switch pkt.(type) {
			case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
				s.pub.requestKeyFrame(ft)
			}
		}
	}
}

// renegotiate 生成并发送新的 offer。
func (s *Subscriber) renegotiate() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	if s.negotiating {
		// 已有协商在途，等 answer 到达后再补一次。
		s.pendingRenegotiate = true
		s.mu.Unlock()
		return nil
	}
	s.negotiating = true
	s.mu.Unlock()

	offer, err := s.pc.CreateOffer(nil)
	if err != nil {
		s.clearNegotiating()
		return fmt.Errorf("生成订阅者 offer 失败: %w", err)
	}
	if err := s.pc.SetLocalDescription(offer); err != nil {
		s.clearNegotiating()
		return fmt.Errorf("应用订阅者 offer 失败: %w", err)
	}
	s.emit(SignalOut{Type: SignalOffer, Data: offer.SDP})
	return nil
}

func (s *Subscriber) clearNegotiating() {
	s.mu.Lock()
	s.negotiating = false
	again := s.pendingRenegotiate
	s.pendingRenegotiate = false
	s.mu.Unlock()
	if again {
		if err := s.renegotiate(); err != nil {
			s.log.Debug("补发订阅者 offer 失败", "err", err)
		}
	}
}

// HandleSignal 处理来自订阅客户端的 SDP/ICE。
func (s *Subscriber) HandleSignal(sigType, data string) error {
	switch sigType {
	case SignalAnswer:
		if err := s.pc.SetRemoteDescription(webrtc.SessionDescription{
			Type: webrtc.SDPTypeAnswer, SDP: data,
		}); err != nil {
			s.clearNegotiating()
			return fmt.Errorf("设置订阅者 answer 失败: %w", err)
		}
		s.clearNegotiating()
		return nil
	case SignalCandidate:
		init, err := parseCandidate(data)
		if err != nil {
			return err
		}
		return s.pc.AddICECandidate(init)
	case SignalEndOfCandidates:
		return nil
	case "restart":
		// 客户端网络切换后请求重新协商，由服务端重新发 offer。
		s.clearNegotiating()
		return s.renegotiate()
	case SignalOffer:
		return errors.New("SFU 订阅方向不接受 offer：服务端为 offerer")
	default:
		return fmt.Errorf("未知的 signaling_type %q", sigType)
	}
}

// Close 关闭订阅者并从发布者中移除。
func (s *Subscriber) Close() {
	s.pub.mu.Lock()
	if cur, ok := s.pub.subs[s.key]; ok && cur == s {
		delete(s.pub.subs, s.key)
	}
	s.pub.mu.Unlock()
	s.closeInternal()
}

func (s *Subscriber) closeInternal() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	s.senders = make(map[string]*webrtc.RTPSender)
	s.mu.Unlock()

	if err := s.pc.Close(); err != nil {
		s.log.Debug("关闭订阅者 PeerConnection", "err", err)
	}
}

// marshalCandidate 把候选序列化为 TSSP 的 candidate JSON 字符串。
func marshalCandidate(c *webrtc.ICECandidate) string {
	init := c.ToJSON()
	b, err := json.Marshal(init)
	if err != nil {
		return ""
	}
	return string(b)
}

// parseCandidate 解析 TSSP 的 candidate JSON 字符串。
func parseCandidate(data string) (webrtc.ICECandidateInit, error) {
	var init webrtc.ICECandidateInit
	if err := json.Unmarshal([]byte(data), &init); err != nil {
		return init, fmt.Errorf("解析 ICE candidate 失败: %w", err)
	}
	return init, nil
}
