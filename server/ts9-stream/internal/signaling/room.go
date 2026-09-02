package signaling

import (
	"sync"
	"time"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/sfu"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// roomKey 唯一标识一个频道：虚拟服务器哈希 + 频道 ID。
type roomKey struct {
	server string
	cid    int64
}

// subscription 是一个订阅者在某路流上的状态。
type subscription struct {
	session *Session
	state   string
	mode    string
}

// stream 是一路共享流在服务端的完整状态。
type stream struct {
	id            string
	room          roomKey
	mode          string
	streamType    string
	accessibility string
	name          string
	properties    map[string]string
	createdAt     time.Time

	publisher *Session
	pubCLID   int
	pubUID    string
	pubNick   string

	// sfuPub 仅在 SFU 模式下存在。
	sfuPub *sfu.Publisher

	mu   sync.RWMutex
	subs map[int]*subscription
}

func (st *stream) snapshot() tssp.Stream {
	st.mu.RLock()
	viewers := 0
	for _, sub := range st.subs {
		if sub.state == tssp.SubscribeStateReady {
			viewers++
		}
	}
	props := make(map[string]string, len(st.properties))
	for k, v := range st.properties {
		props[k] = v
	}
	name := st.name
	access := st.accessibility
	st.mu.RUnlock()

	return tssp.Stream{
		StreamID:      st.id,
		CID:           st.room.cid,
		Mode:          st.mode,
		StreamType:    st.streamType,
		Accessibility: access,
		Name:          name,
		Publisher: tssp.PeerRef{
			CLID:     st.pubCLID,
			UID:      st.pubUID,
			Nickname: st.pubNick,
		},
		Properties:  props,
		ViewerCount: viewers,
		CreatedAt:   st.createdAt.UnixMilli(),
	}
}

func (st *stream) setSubscription(clid int, sub *subscription) {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.subs[clid] = sub
}

func (st *stream) subscription(clid int) (*subscription, bool) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	sub, ok := st.subs[clid]
	return sub, ok
}

func (st *stream) removeSubscription(clid int) (*subscription, bool) {
	st.mu.Lock()
	defer st.mu.Unlock()
	sub, ok := st.subs[clid]
	if ok {
		delete(st.subs, clid)
	}
	return sub, ok
}

func (st *stream) subscriptions() []*subscription {
	st.mu.RLock()
	defer st.mu.RUnlock()
	out := make([]*subscription, 0, len(st.subs))
	for _, sub := range st.subs {
		out = append(out, sub)
	}
	return out
}

func (st *stream) readySubscriberCount() int {
	st.mu.RLock()
	defer st.mu.RUnlock()
	n := 0
	for _, sub := range st.subs {
		if sub.state == tssp.SubscribeStateReady {
			n++
		}
	}
	return n
}

// room 是一个频道内的会话集合与流集合。
type room struct {
	key      roomKey
	mu       sync.RWMutex
	sessions map[string]*Session
	streams  map[string]*stream
}

func newRoom(key roomKey) *room {
	return &room{
		key:      key,
		sessions: make(map[string]*Session),
		streams:  make(map[string]*stream),
	}
}

func (r *room) addSession(s *Session) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sessions[s.id] = s
}

func (r *room) removeSession(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.sessions, id)
}

func (r *room) sessionList() []*Session {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*Session, 0, len(r.sessions))
	for _, s := range r.sessions {
		out = append(out, s)
	}
	return out
}

func (r *room) addStream(st *stream) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.streams[st.id] = st
}

func (r *room) removeStream(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.streams, id)
}

func (r *room) streamList() []*stream {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*stream, 0, len(r.streams))
	for _, st := range r.streams {
		out = append(out, st)
	}
	return out
}

func (r *room) streamCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.streams)
}

func (r *room) isEmpty() bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.sessions) == 0 && len(r.streams) == 0
}

// broadcast 向房间内所有会话发送事件，可排除若干会话。
func (r *room) broadcast(msgType string, payload any, exclude ...string) {
	data, err := encode(msgType, "", payload)
	if err != nil {
		return
	}
	skip := make(map[string]struct{}, len(exclude))
	for _, id := range exclude {
		skip[id] = struct{}{}
	}
	for _, s := range r.sessionList() {
		if _, ok := skip[s.id]; ok {
			continue
		}
		s.enqueue(data)
	}
}
