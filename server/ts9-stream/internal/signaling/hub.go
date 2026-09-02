package signaling

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/auth"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/sfu"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// Hub 是信令服务的核心：管理会话、房间与流，并把 SFU 与鉴权串起来。
type Hub struct {
	cfg      *config.Config
	log      *slog.Logger
	signer   *auth.Signer
	verifier *auth.Verifier
	ice      *auth.ICEProvider
	limiter  *auth.RateLimiter
	engine   *sfu.Engine

	mu       sync.RWMutex
	sessions map[string]*Session
	rooms    map[roomKey]*room
	streams  map[string]*stream
	closed   bool

	wg     sync.WaitGroup
	cancel context.CancelFunc
}

// Deps 是构造 Hub 所需的依赖。
type Deps struct {
	Config   *config.Config
	Log      *slog.Logger
	Signer   *auth.Signer
	Verifier *auth.Verifier
	Engine   *sfu.Engine
}

// NewHub 创建信令中心并启动后台维护协程。
func NewHub(d Deps) *Hub {
	ctx, cancel := context.WithCancel(context.Background())
	h := &Hub{
		cfg:      d.Config,
		log:      d.Log,
		signer:   d.Signer,
		verifier: d.Verifier,
		ice:      auth.NewICEProvider(&d.Config.ICE),
		limiter: auth.NewRateLimiter(
			d.Config.Limits.HelloFailWindow,
			d.Config.Limits.HelloFailMax,
			d.Config.Limits.HelloBanTime,
		),
		engine:   d.Engine,
		sessions: make(map[string]*Session),
		rooms:    make(map[roomKey]*room),
		streams:  make(map[string]*stream),
		cancel:   cancel,
	}
	h.wg.Add(1)
	go func() {
		defer h.wg.Done()
		h.maintain(ctx)
	}()
	return h
}

// SessionCount 返回当前会话数。
func (h *Hub) SessionCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.sessions)
}

// StreamCount 返回当前活跃流数量。
func (h *Hub) StreamCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.streams)
}

// ServeHTTP 处理 TSSP 的 WebSocket 升级请求。
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.mu.RLock()
	closed := h.closed
	count := len(h.sessions)
	h.mu.RUnlock()

	if closed {
		http.Error(w, "服务正在关闭", http.StatusServiceUnavailable)
		return
	}
	if h.cfg.Limits.MaxSessions > 0 && count >= h.cfg.Limits.MaxSessions {
		http.Error(w, "会话数已达上限", http.StatusServiceUnavailable)
		return
	}

	remote := remoteAddr(r, &h.cfg.Listen)
	if ok, retry := h.limiter.Allow(remote, time.Now()); !ok {
		w.Header().Set("Retry-After", retryAfterSeconds(retry))
		http.Error(w, "请求过于频繁", http.StatusTooManyRequests)
		return
	}

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols: []string{tssp.Subprotocol},
		// 客户端不是浏览器，Origin 头不可靠；鉴权由 hello + ServerQuery 反向校验承担。
		InsecureSkipVerify: true,
		CompressionMode:    websocket.CompressionDisabled,
	})
	if err != nil {
		h.log.Debug("WebSocket 升级失败", "peer", remote, "err", err)
		return
	}
	if conn.Subprotocol() != tssp.Subprotocol {
		_ = conn.Close(websocket.StatusProtocolError, "expected subprotocol "+tssp.Subprotocol)
		return
	}

	s := newSession(h, uuid.NewString(), conn, remote)
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		_ = conn.Close(websocket.StatusGoingAway, "shutting down")
		return
	}
	h.sessions[s.id] = s
	h.mu.Unlock()

	s.log().Debug("会话已建立")
	s.run(r.Context())
}

// removeSession 清理会话相关的流、订阅与房间成员关系。
func (h *Hub) removeSession(s *Session) {
	h.mu.Lock()
	delete(h.sessions, s.id)
	h.mu.Unlock()

	// 先撤销订阅，再关闭自己发布的流，避免重复通知。
	for _, streamID := range s.subscriptionList() {
		h.detachSubscriber(s, streamID, tssp.ReasonDisconnected, false)
	}
	if streamID := s.publishingStream(); streamID != "" {
		h.teardownStream(streamID, tssp.ReasonDisconnected)
	}

	if r := h.existingRoom(s.Room()); r != nil {
		r.removeSession(s.id)
		h.gcRoom(r)
	}
	s.log().Debug("会话已清理")
}

// room 返回房间，不存在时创建。
func (h *Hub) room(key roomKey) *room {
	h.mu.Lock()
	defer h.mu.Unlock()
	r, ok := h.rooms[key]
	if !ok {
		r = newRoom(key)
		h.rooms[key] = r
	}
	return r
}

// existingRoom 返回已存在的房间，不创建。
func (h *Hub) existingRoom(key roomKey) *room {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.rooms[key]
}

func (h *Hub) gcRoom(r *room) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cur, ok := h.rooms[r.key]; ok && cur == r && cur.isEmpty() {
		delete(h.rooms, r.key)
	}
}

func (h *Hub) registerStream(st *stream) {
	h.mu.Lock()
	h.streams[st.id] = st
	h.mu.Unlock()
	h.room(st.room).addStream(st)
}

func (h *Hub) lookupStream(id string) (*stream, bool) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	st, ok := h.streams[id]
	return st, ok
}

func (h *Hub) unregisterStream(st *stream) {
	h.mu.Lock()
	delete(h.streams, st.id)
	h.mu.Unlock()
	if r := h.existingRoom(st.room); r != nil {
		r.removeStream(st.id)
		h.gcRoom(r)
	}
}

// maintain 周期性检查令牌过期，发出 token_expiring 或断开已过期会话。
func (h *Hub) maintain(ctx context.Context) {
	interval := h.cfg.Auth.RenewLeeway / 4
	if interval < 5*time.Second {
		interval = 5 * time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			h.mu.RLock()
			sessions := make([]*Session, 0, len(h.sessions))
			for _, s := range h.sessions {
				sessions = append(sessions, s)
			}
			h.mu.RUnlock()

			for _, s := range sessions {
				if s.tokenDeadPassed(now) {
					s.Bye(tssp.ErrTokenExpired, "令牌已过期且未续签")
					continue
				}
				s.maybeWarnExpiry(now, h.cfg.Auth.RenewLeeway)
			}
		}
	}
}

// Shutdown 通知所有会话关闭并等待后台协程退出。
func (h *Hub) Shutdown(ctx context.Context) {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return
	}
	h.closed = true
	sessions := make([]*Session, 0, len(h.sessions))
	for _, s := range h.sessions {
		sessions = append(sessions, s)
	}
	streams := make([]*stream, 0, len(h.streams))
	for _, st := range h.streams {
		streams = append(streams, st)
	}
	h.mu.Unlock()

	for _, st := range streams {
		if r := h.existingRoom(st.room); r != nil {
			r.broadcast(tssp.EventStreamRemoved, tssp.StreamRemovedEvent{
				StreamID: st.id,
				Reason:   tssp.ReasonServerShutdown,
			})
		}
	}
	for _, s := range sessions {
		s.Bye("SERVER_SHUTDOWN", "服务正在关闭")
	}

	h.cancel()

	waited := make(chan struct{})
	go func() {
		h.wg.Wait()
		close(waited)
	}()
	select {
	case <-waited:
	case <-ctx.Done():
	}

	// 等待会话自行退出，超时后强制关闭底层连接。
	deadline := time.Now().Add(2 * time.Second)
	for {
		if h.SessionCount() == 0 || time.Now().After(deadline) || ctx.Err() != nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	h.mu.RLock()
	remaining := make([]*Session, 0, len(h.sessions))
	for _, s := range h.sessions {
		remaining = append(remaining, s)
	}
	h.mu.RUnlock()
	for _, s := range remaining {
		_ = s.conn.CloseNow()
	}
}

// remoteAddr 解析客户端 IP，用于限流与日志。
//
// X-Forwarded-For 只有在请求来自 listen.trusted_proxies 中的地址时才被采信；
// 否则任何直连客户端都能通过伪造该头绕过 hello 失败限流。
func remoteAddr(r *http.Request, trusted *config.Listen) string {
	peer := peerHost(r.RemoteAddr)
	if trusted == nil || !trusted.TrustsProxy(r.RemoteAddr) {
		return peer
	}
	xff := r.Header.Get("X-Forwarded-For")
	if xff == "" {
		return peer
	}
	// 取最右侧一段：它是可信代理直接观察到的对端，左侧各段都可被客户端伪造。
	if i := strings.LastIndexByte(xff, ','); i >= 0 {
		xff = xff[i+1:]
	}
	if v := strings.TrimSpace(xff); v != "" {
		return v
	}
	return peer
}

func peerHost(addr string) string {
	if host, _, err := net.SplitHostPort(addr); err == nil {
		return host
	}
	return addr
}

func retryAfterSeconds(d time.Duration) string {
	secs := int(d.Seconds())
	if secs < 1 {
		secs = 1
	}
	return strconv.Itoa(secs)
}
