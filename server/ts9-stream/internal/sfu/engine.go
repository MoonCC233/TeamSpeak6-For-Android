// Package sfu 用 pion/webrtc 实现「一发多收」的媒体转发。
//
// SFU 不做转码：把发布者的 RTP 按原样（仅重写 SSRC 与 payload type）写给每个订阅者。
// 规范见 docs/protocol/tssp-v1.md §9。
package sfu

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
)

// Config 是 SFU 的运行参数。
type Config struct {
	ICEServers  []webrtc.ICEServer
	UDPPortMin  uint16
	UDPPortMax  uint16
	PublicIP    string
	VideoCodecs []string
	AudioCodecs []string
	// PLIInterval 是周期性向发布者请求关键帧的间隔，0 表示只在订阅者接入时请求。
	PLIInterval time.Duration
}

// Engine 管理所有发布者的 PeerConnection 与轨道转发。
type Engine struct {
	api    *webrtc.API
	rtcCfg webrtc.Configuration
	log    *slog.Logger
	cfg    Config

	mu         sync.RWMutex
	publishers map[string]*Publisher
}

// New 构造 SFU 引擎。
func New(cfg Config, log *slog.Logger) (*Engine, error) {
	me := &webrtc.MediaEngine{}
	if err := registerCodecs(me, cfg.VideoCodecs, cfg.AudioCodecs); err != nil {
		return nil, err
	}
	ir := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(me, ir); err != nil {
		return nil, fmt.Errorf("注册默认拦截器失败: %w", err)
	}

	se := webrtc.SettingEngine{}
	if cfg.UDPPortMin != 0 && cfg.UDPPortMax != 0 {
		if err := se.SetEphemeralUDPPortRange(cfg.UDPPortMin, cfg.UDPPortMax); err != nil {
			return nil, fmt.Errorf("设置 UDP 端口范围失败: %w", err)
		}
	}
	if cfg.PublicIP != "" {
		se.SetNAT1To1IPs([]string{cfg.PublicIP}, webrtc.ICECandidateTypeHost)
	}

	return &Engine{
		api: webrtc.NewAPI(
			webrtc.WithMediaEngine(me),
			webrtc.WithInterceptorRegistry(ir),
			webrtc.WithSettingEngine(se),
		),
		rtcCfg:     webrtc.Configuration{ICEServers: cfg.ICEServers},
		log:        log,
		cfg:        cfg,
		publishers: make(map[string]*Publisher),
	}, nil
}

// registerCodecs 只注册配置允许的编解码，确保 SFU 转发时两端 payload 一致。
func registerCodecs(me *webrtc.MediaEngine, video, audio []string) error {
	videoFB := []webrtc.RTCPFeedback{
		{Type: webrtc.TypeRTCPFBNACK},
		{Type: webrtc.TypeRTCPFBNACK, Parameter: "pli"},
		{Type: webrtc.TypeRTCPFBGoogREMB},
		{Type: webrtc.TypeRTCPFBTransportCC},
	}

	registered := 0
	for _, name := range video {
		switch strings.ToUpper(strings.TrimSpace(name)) {
		case "H264":
			// Baseline profile，packetization-mode=1：Android MediaCodec 与 Windows 硬编都支持。
			for _, c := range []webrtc.RTPCodecParameters{
				{
					RTPCodecCapability: webrtc.RTPCodecCapability{
						MimeType:     webrtc.MimeTypeH264,
						ClockRate:    90000,
						SDPFmtpLine:  "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
						RTCPFeedback: videoFB,
					},
					PayloadType: 102,
				},
				{
					RTPCodecCapability: webrtc.RTPCodecCapability{
						MimeType:     webrtc.MimeTypeH264,
						ClockRate:    90000,
						SDPFmtpLine:  "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f",
						RTCPFeedback: videoFB,
					},
					PayloadType: 108,
				},
			} {
				if err := me.RegisterCodec(c, webrtc.RTPCodecTypeVideo); err != nil {
					return fmt.Errorf("注册 H264 编解码失败: %w", err)
				}
			}
			registered++
		case "VP8":
			if err := me.RegisterCodec(webrtc.RTPCodecParameters{
				RTPCodecCapability: webrtc.RTPCodecCapability{
					MimeType:     webrtc.MimeTypeVP8,
					ClockRate:    90000,
					RTCPFeedback: videoFB,
				},
				PayloadType: 96,
			}, webrtc.RTPCodecTypeVideo); err != nil {
				return fmt.Errorf("注册 VP8 编解码失败: %w", err)
			}
			registered++
		default:
			return fmt.Errorf("不支持的视频编解码 %q", name)
		}
	}
	if registered == 0 {
		return errors.New("至少需要启用一种视频编解码")
	}

	for _, name := range audio {
		switch strings.ToLower(strings.TrimSpace(name)) {
		case "opus":
			if err := me.RegisterCodec(webrtc.RTPCodecParameters{
				RTPCodecCapability: webrtc.RTPCodecCapability{
					MimeType:     webrtc.MimeTypeOpus,
					ClockRate:    48000,
					Channels:     2,
					SDPFmtpLine:  "minptime=10;useinbandfec=1",
					RTCPFeedback: []webrtc.RTCPFeedback{{Type: webrtc.TypeRTCPFBTransportCC}},
				},
				PayloadType: 111,
			}, webrtc.RTPCodecTypeAudio); err != nil {
				return fmt.Errorf("注册 Opus 编解码失败: %w", err)
			}
		default:
			return fmt.Errorf("不支持的音频编解码 %q", name)
		}
	}
	return nil
}

// Sink 是 SFU 回调宿主（信令层）的钩子。
type Sink struct {
	// Signal 把服务端产生的 SDP/ICE 发给对应客户端。
	Signal func(msg SignalOut)
	// State 报告 PeerConnection 状态变化。
	State func(state webrtc.PeerConnectionState)
}

// SignalOut 是 SFU 需要发出的一条信令。
type SignalOut struct {
	Type string
	Data string
}

// 信令子类型常量，与 TSSP 的 signaling_type 对应。
const (
	SignalOffer           = "offer"
	SignalAnswer          = "answer"
	SignalCandidate       = "candidate"
	SignalEndOfCandidates = "end_of_candidates"
)

// Publisher 是一路发布流在 SFU 侧的表示。
type Publisher struct {
	engine   *Engine
	streamID string
	log      *slog.Logger
	sink     Sink

	pc *webrtc.PeerConnection

	mu     sync.RWMutex
	tracks map[string]*forwardTrack
	subs   map[string]*Subscriber
	closed bool
}

type forwardTrack struct {
	local *webrtc.TrackLocalStaticRTP
	ssrc  webrtc.SSRC
	kind  webrtc.RTPCodecType
	// pliStop 停止周期性关键帧请求。
	pliStop chan struct{}
}

// AddPublisher 为某路流创建发布侧 PeerConnection。
// 本协议规定 SFU 发布方向由客户端作为 offerer，因此这里只准备接收。
func (e *Engine) AddPublisher(streamID string, sink Sink) (*Publisher, error) {
	e.mu.Lock()
	if _, exists := e.publishers[streamID]; exists {
		e.mu.Unlock()
		return nil, fmt.Errorf("流 %s 已存在发布者", streamID)
	}
	e.mu.Unlock()

	pc, err := e.api.NewPeerConnection(e.rtcCfg)
	if err != nil {
		return nil, fmt.Errorf("创建发布者 PeerConnection 失败: %w", err)
	}

	p := &Publisher{
		engine:   e,
		streamID: streamID,
		log:      e.log.With("stream_id", streamID, "role", "publisher"),
		sink:     sink,
		pc:       pc,
		tracks:   make(map[string]*forwardTrack),
		subs:     make(map[string]*Subscriber),
	}

	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c == nil {
			p.emit(SignalOut{Type: SignalEndOfCandidates})
			return
		}
		p.emit(SignalOut{Type: SignalCandidate, Data: marshalCandidate(c)})
	})
	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		p.log.Debug("发布者连接状态变化", "state", s.String())
		if sink.State != nil {
			sink.State(s)
		}
	})
	pc.OnTrack(p.onTrack)

	e.mu.Lock()
	e.publishers[streamID] = p
	e.mu.Unlock()
	return p, nil
}

// Publisher 返回指定流的发布者。
func (e *Engine) Publisher(streamID string) (*Publisher, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	p, ok := e.publishers[streamID]
	return p, ok
}

// Close 关闭全部资源。
func (e *Engine) Close() {
	e.mu.Lock()
	pubs := make([]*Publisher, 0, len(e.publishers))
	for _, p := range e.publishers {
		pubs = append(pubs, p)
	}
	e.publishers = make(map[string]*Publisher)
	e.mu.Unlock()
	for _, p := range pubs {
		p.closeInternal()
	}
}

func (p *Publisher) emit(out SignalOut) {
	if p.sink.Signal != nil {
		p.sink.Signal(out)
	}
}

// StreamID 返回流标识。
func (p *Publisher) StreamID() string { return p.streamID }

// HandleSignal 处理来自发布客户端的 SDP/ICE。
func (p *Publisher) HandleSignal(sigType, data string) error {
	switch sigType {
	case SignalOffer, "restart":
		return p.handleOffer(data)
	case SignalCandidate:
		init, err := parseCandidate(data)
		if err != nil {
			return err
		}
		return p.pc.AddICECandidate(init)
	case SignalEndOfCandidates:
		return nil
	case SignalAnswer:
		return errors.New("SFU 发布方向不接受 answer：发布者应作为 offerer")
	default:
		return fmt.Errorf("未知的 signaling_type %q", sigType)
	}
}

func (p *Publisher) handleOffer(sdp string) error {
	if err := p.pc.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer, SDP: sdp,
	}); err != nil {
		return fmt.Errorf("设置发布者 offer 失败: %w", err)
	}
	answer, err := p.pc.CreateAnswer(nil)
	if err != nil {
		return fmt.Errorf("生成发布者 answer 失败: %w", err)
	}
	if err := p.pc.SetLocalDescription(answer); err != nil {
		return fmt.Errorf("应用发布者 answer 失败: %w", err)
	}
	p.emit(SignalOut{Type: SignalAnswer, Data: answer.SDP})
	return nil
}

func (p *Publisher) onTrack(remote *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	codec := remote.Codec()
	local, err := webrtc.NewTrackLocalStaticRTP(codec.RTPCodecCapability, remote.ID(), remote.StreamID())
	if err != nil {
		p.log.Error("创建转发轨道失败", "err", err)
		return
	}
	ft := &forwardTrack{
		local:   local,
		ssrc:    remote.SSRC(),
		kind:    remote.Kind(),
		pliStop: make(chan struct{}),
	}

	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.tracks[remote.ID()] = ft
	subs := make([]*Subscriber, 0, len(p.subs))
	for _, s := range p.subs {
		subs = append(subs, s)
	}
	p.mu.Unlock()

	p.log.Info("收到发布轨道", "kind", remote.Kind().String(), "codec", codec.MimeType, "ssrc", uint32(remote.SSRC()))

	if ft.kind == webrtc.RTPCodecTypeVideo && p.engine.cfg.PLIInterval > 0 {
		go p.pliLoop(ft)
	}
	// 已在等待的订阅者补上这条轨道。
	for _, s := range subs {
		if err := s.attach(ft); err != nil {
			s.log.Warn("向订阅者添加轨道失败", "err", err)
		}
	}

	go p.forward(remote, ft)
}

// forward 把远端 RTP 原样写入本地转发轨道。
func (p *Publisher) forward(remote *webrtc.TrackRemote, ft *forwardTrack) {
	defer func() {
		close(ft.pliStop)
		p.mu.Lock()
		delete(p.tracks, remote.ID())
		subs := make([]*Subscriber, 0, len(p.subs))
		for _, s := range p.subs {
			subs = append(subs, s)
		}
		p.mu.Unlock()
		for _, s := range subs {
			s.detach(remote.ID())
		}
		p.log.Debug("轨道转发结束", "track", remote.ID())
	}()

	for {
		pkt, _, err := remote.ReadRTP()
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, webrtc.ErrConnectionClosed) {
				p.log.Debug("读取发布 RTP 结束", "err", err)
			}
			return
		}
		if err := ft.local.WriteRTP(pkt); err != nil {
			if errors.Is(err, io.ErrClosedPipe) {
				// 没有绑定的订阅者时属正常情况，继续读取以驱动拦截器。
				continue
			}
			p.log.Debug("转发 RTP 失败", "err", err)
		}
	}
}

// pliLoop 周期性请求关键帧，保证中途接入的订阅者能尽快出画。
func (p *Publisher) pliLoop(ft *forwardTrack) {
	t := time.NewTicker(p.engine.cfg.PLIInterval)
	defer t.Stop()
	for {
		select {
		case <-ft.pliStop:
			return
		case <-t.C:
			p.mu.RLock()
			hasSubs := len(p.subs) > 0
			p.mu.RUnlock()
			if !hasSubs {
				continue
			}
			p.requestKeyFrame(ft)
		}
	}
}

func (p *Publisher) requestKeyFrame(ft *forwardTrack) {
	if ft.kind != webrtc.RTPCodecTypeVideo {
		return
	}
	if err := p.pc.WriteRTCP([]rtcp.Packet{
		&rtcp.PictureLossIndication{MediaSSRC: uint32(ft.ssrc)},
	}); err != nil {
		p.log.Debug("发送 PLI 失败", "err", err)
	}
}

// RequestKeyFrame 对所有视频轨道请求一次关键帧。
func (p *Publisher) RequestKeyFrame() {
	p.mu.RLock()
	tracks := make([]*forwardTrack, 0, len(p.tracks))
	for _, ft := range p.tracks {
		tracks = append(tracks, ft)
	}
	p.mu.RUnlock()
	for _, ft := range tracks {
		p.requestKeyFrame(ft)
	}
}

// Close 关闭发布者及其所有订阅者，并从引擎中注销。
func (p *Publisher) Close() {
	p.engine.mu.Lock()
	if cur, ok := p.engine.publishers[p.streamID]; ok && cur == p {
		delete(p.engine.publishers, p.streamID)
	}
	p.engine.mu.Unlock()
	p.closeInternal()
}

func (p *Publisher) closeInternal() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	subs := make([]*Subscriber, 0, len(p.subs))
	for _, s := range p.subs {
		subs = append(subs, s)
	}
	p.subs = make(map[string]*Subscriber)
	p.mu.Unlock()

	for _, s := range subs {
		s.Close()
	}
	if err := p.pc.Close(); err != nil {
		p.log.Debug("关闭发布者 PeerConnection", "err", err)
	}
}
