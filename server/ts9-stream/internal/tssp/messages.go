// Package tssp 定义 TSSP v1 的报文结构、消息类型与错误码。
// 规范见 docs/protocol/tssp-v1.md。
package tssp

import (
	"encoding/json"
	"errors"
	"fmt"
)

// ProtocolVersion 是本实现支持的协议版本。
const ProtocolVersion = 1

// Subprotocol 是 WebSocket 子协议名。
const Subprotocol = "tssp.v1"

// 请求与响应消息类型。
const (
	TypeHello       = "hello"
	TypeSetup       = "setup"
	TypeUpdate      = "update"
	TypeStop        = "stop"
	TypeList        = "list"
	TypeSubscribe   = "subscribe"
	TypeUnsubscribe = "unsubscribe"
	TypeRespondJoin = "respond_join"
	TypeSignaling   = "signaling"
	TypeRenew       = "renew"
	TypeStats       = "stats"
	TypeOK          = "ok"
	TypeError       = "error"
)

// 服务端事件类型。
const (
	EventStreamAdded       = "stream_added"
	EventStreamUpdated     = "stream_updated"
	EventStreamRemoved     = "stream_removed"
	EventSubscribeReady    = "subscribe_ready"
	EventJoinRequest       = "join_request"
	EventJoinRejected      = "join_rejected"
	EventPeerJoined        = "peer_joined"
	EventPeerLeft          = "peer_left"
	EventRemovedFromStream = "removed_from_stream"
	EventTokenExpiring     = "token_expiring"
	EventStatsRequest      = "stats_request"
	EventBye               = "bye"
)

// 媒体模式。
const (
	ModeSFU = "sfu"
	ModeP2P = "p2p"
)

// 流类型，对齐官方 StreamType 语义。
const (
	StreamTypeScreen = "screen"
	StreamTypeWindow = "window"
	StreamTypeCamera = "camera"
)

// 可见性。
const (
	AccessibilityChannel    = "channel"
	AccessibilityInviteOnly = "invite_only"
)

// 信令子类型。
const (
	SignalingOffer           = "offer"
	SignalingAnswer          = "answer"
	SignalingCandidate       = "candidate"
	SignalingEndOfCandidates = "end_of_candidates"
	SignalingRestart         = "restart"
)

// 角色。
const (
	RolePublisher  = "publisher"
	RoleSubscriber = "subscriber"
)

// 订阅状态。
const (
	SubscribeStatePending = "pending"
	SubscribeStateReady   = "ready"
)

// 流移除原因。
const (
	ReasonStopped        = "stopped"
	ReasonDisconnected   = "disconnected"
	ReasonChannelChanged = "channel_changed"
	ReasonRemoved        = "removed"
	ReasonServerShutdown = "server_shutdown"
	ReasonFailed         = "failed"
	ReasonUnsubscribed   = "unsubscribed"
	ReasonRejected       = "rejected"
)

// 错误码，见规范 §7。
const (
	ErrBadRequest          = "BAD_REQUEST"
	ErrUnsupportedProtocol = "UNSUPPORTED_PROTOCOL"
	ErrUnknownServer       = "UNKNOWN_SERVER"
	ErrQueryUnavailable    = "QUERY_UNAVAILABLE"
	ErrClientNotFound      = "CLIENT_NOT_FOUND"
	ErrIdentityMismatch    = "IDENTITY_MISMATCH"
	ErrNotAllowed          = "NOT_ALLOWED"
	ErrRateLimited         = "RATE_LIMITED"
	ErrTokenInvalid        = "TOKEN_INVALID"
	ErrTokenExpired        = "TOKEN_EXPIRED"
	ErrModeNotSupported    = "MODE_NOT_SUPPORTED"
	ErrCodecNotSupported   = "CODEC_NOT_SUPPORTED"
	ErrStreamNotFound      = "STREAM_NOT_FOUND"
	ErrNotStreamOwner      = "NOT_STREAM_OWNER"
	ErrNotSameChannel      = "NOT_SAME_CHANNEL"
	ErrAlreadyPublishing   = "ALREADY_PUBLISHING"
	ErrTooManyStreams      = "TOO_MANY_STREAMS"
	ErrTooManyViewers      = "TOO_MANY_VIEWERS"
	ErrJoinRejected        = "JOIN_REJECTED"
	ErrSignalingFailed     = "SIGNALING_FAILED"
	ErrInternal            = "INTERNAL"
)

// Envelope 是所有 TSSP 消息的外层结构。
type Envelope struct {
	Type string          `json:"t"`
	ID   string          `json:"id,omitempty"`
	TS   int64           `json:"ts,omitempty"`
	Data json.RawMessage `json:"d,omitempty"`
}

// Decode 把负载解析到目标结构。负载为空时视为空对象。
func (e *Envelope) Decode(v any) error {
	if len(e.Data) == 0 {
		return nil
	}
	if err := json.Unmarshal(e.Data, v); err != nil {
		return fmt.Errorf("解析 %s 负载: %w", e.Type, err)
	}
	return nil
}

// Error 是 TSSP 错误负载，同时实现 error 接口便于内部传递。
type Error struct {
	Code         string `json:"code"`
	Message      string `json:"message,omitempty"`
	RetryAfterMS int64  `json:"retry_after_ms,omitempty"`
}

func (e *Error) Error() string {
	if e.Message == "" {
		return e.Code
	}
	return e.Code + ": " + e.Message
}

// NewError 构造一个 TSSP 错误。
func NewError(code, message string) *Error {
	return &Error{Code: code, Message: message}
}

// NewRetryError 构造一个带退避提示的 TSSP 错误。
func NewRetryError(code, message string, retryAfterMS int64) *Error {
	return &Error{Code: code, Message: message, RetryAfterMS: retryAfterMS}
}

// AsError 从任意 error 中提取 TSSP 错误，非 TSSP 错误统一映射为 INTERNAL。
func AsError(err error) *Error {
	var te *Error
	if errors.As(err, &te) {
		return te
	}
	return NewError(ErrInternal, "内部错误")
}

// ClientCapabilities 是客户端在 hello 中声明的能力。
type ClientCapabilities struct {
	Modes          []string `json:"modes,omitempty"`
	VideoCodecs    []string `json:"video_codecs,omitempty"`
	AudioCodecs    []string `json:"audio_codecs,omitempty"`
	MaxRecvStreams int      `json:"max_recv_streams,omitempty"`
}

// ClientInfo 是客户端自报的软件信息，仅用于日志与诊断。
type ClientInfo struct {
	Name     string `json:"name,omitempty"`
	Version  string `json:"version,omitempty"`
	Platform string `json:"platform,omitempty"`
}

// HelloRequest 是鉴权请求负载。
type HelloRequest struct {
	Protocol     int                `json:"protocol"`
	ServerAddr   string             `json:"server_addr"`
	UID          string             `json:"uid"`
	CLID         int                `json:"clid"`
	CID          int64              `json:"cid"`
	Nonce        string             `json:"nonce,omitempty"`
	Client       ClientInfo         `json:"client"`
	Capabilities ClientCapabilities `json:"capabilities"`
}

// ICEServer 是下发给客户端的 ICE 服务器条目。
type ICEServer struct {
	URLs          []string `json:"urls"`
	Username      string   `json:"username,omitempty"`
	Credential    string   `json:"credential,omitempty"`
	CredentialTTL int64    `json:"credential_ttl,omitempty"`
}

// ServerCapabilities 是服务端在 hello 响应中下发的能力与限制。
type ServerCapabilities struct {
	Modes                []string    `json:"modes"`
	DefaultMode          string      `json:"default_mode"`
	VideoCodecs          []string    `json:"video_codecs"`
	AudioCodecs          []string    `json:"audio_codecs,omitempty"`
	MaxBitrateKbps       int         `json:"max_bitrate_kbps"`
	MaxStreamsPerChannel int         `json:"max_streams_per_channel"`
	MaxViewersPerStream  int         `json:"max_viewers_per_stream,omitempty"`
	ICEServers           []ICEServer `json:"ice_servers,omitempty"`
}

// HelloResponse 是鉴权成功响应。
type HelloResponse struct {
	SessionID    string             `json:"session_id"`
	SessionToken string             `json:"session_token"`
	ExpiresAt    int64              `json:"expires_at"`
	Nonce        string             `json:"nonce,omitempty"`
	Nickname     string             `json:"nickname,omitempty"`
	Server       ServerCapabilities `json:"server"`
}

// SetupRequest 是开始共享的请求负载。
type SetupRequest struct {
	Token         string            `json:"token"`
	Mode          string            `json:"mode"`
	StreamType    string            `json:"stream_type"`
	Accessibility string            `json:"accessibility"`
	Name          string            `json:"name,omitempty"`
	Properties    map[string]string `json:"properties,omitempty"`
}

// PublishInstruction 告知客户端如何开始发布协商。
type PublishInstruction struct {
	// Offerer 指明由哪一方发起 offer，取值 publisher 或 server。
	Offerer string `json:"offerer"`
	// MaxBitrateKbps 是服务端强制的码率上限。
	MaxBitrateKbps int `json:"max_bitrate_kbps,omitempty"`
	// VideoCodecs 是本次协商允许的编解码交集。
	VideoCodecs []string `json:"video_codecs,omitempty"`
}

// SetupResponse 是开始共享的响应。
type SetupResponse struct {
	StreamID string             `json:"stream_id"`
	Mode     string             `json:"mode"`
	Publish  PublishInstruction `json:"publish"`
}

// UpdateRequest 是更新共享参数的请求负载。
type UpdateRequest struct {
	Token      string            `json:"token"`
	StreamID   string            `json:"stream_id"`
	Name       string            `json:"name,omitempty"`
	Properties map[string]string `json:"properties,omitempty"`
}

// StopRequest 是停止共享的请求负载。
type StopRequest struct {
	Token    string `json:"token"`
	StreamID string `json:"stream_id"`
}

// ListRequest 是列出可见流的请求负载。
type ListRequest struct {
	Token string `json:"token"`
	CID   *int64 `json:"cid,omitempty"`
}

// ListResponse 是可见流列表。
type ListResponse struct {
	Streams []Stream `json:"streams"`
}

// SubscribeRequest 是观看请求负载。
type SubscribeRequest struct {
	Token      string `json:"token"`
	StreamID   string `json:"stream_id"`
	PreferMode string `json:"prefer_mode,omitempty"`
}

// PeerRef 标识一个对端客户端。
type PeerRef struct {
	CLID     int    `json:"clid"`
	UID      string `json:"uid"`
	Nickname string `json:"nickname,omitempty"`
}

// SubscribeResponse 是观看请求的响应。
type SubscribeResponse struct {
	StreamID string   `json:"stream_id"`
	State    string   `json:"state"`
	Mode     string   `json:"mode,omitempty"`
	Peer     *PeerRef `json:"peer,omitempty"`
}

// UnsubscribeRequest 是取消观看的请求负载。
type UnsubscribeRequest struct {
	Token    string `json:"token"`
	StreamID string `json:"stream_id"`
}

// RespondJoinRequest 是发布者审批观看请求的负载。
type RespondJoinRequest struct {
	Token    string `json:"token"`
	StreamID string `json:"stream_id"`
	CLID     int    `json:"clid"`
	Accept   bool   `json:"accept"`
	Reason   string `json:"reason,omitempty"`
}

// SignalingMessage 是 SDP/ICE 中转负载，双向使用。
type SignalingMessage struct {
	Token         string `json:"token,omitempty"`
	StreamID      string `json:"stream_id"`
	PeerCLID      int    `json:"peer_clid,omitempty"`
	Role          string `json:"role,omitempty"`
	SignalingType string `json:"signaling_type"`
	SignalingData string `json:"signaling_data,omitempty"`
}

// RenewRequest 是续签或频道变更的请求负载。
type RenewRequest struct {
	Token string `json:"token"`
	CLID  int    `json:"clid"`
	CID   int64  `json:"cid"`
}

// RenewResponse 是续签响应。
type RenewResponse struct {
	SessionToken string      `json:"session_token"`
	ExpiresAt    int64       `json:"expires_at"`
	ICEServers   []ICEServer `json:"ice_servers,omitempty"`
}

// StatsReport 是客户端上报的质量数据。
type StatsReport struct {
	Token         string  `json:"token"`
	StreamID      string  `json:"stream_id"`
	Role          string  `json:"role,omitempty"`
	BitrateKbps   float64 `json:"bitrate_kbps,omitempty"`
	FPS           float64 `json:"fps,omitempty"`
	PacketLoss    float64 `json:"packet_loss,omitempty"`
	RTTMS         float64 `json:"rtt_ms,omitempty"`
	JitterMS      float64 `json:"jitter_ms,omitempty"`
	FramesDropped int     `json:"frames_dropped,omitempty"`
}

// Stream 是一路共享流的公开描述，见规范 §6.1。
type Stream struct {
	StreamID      string            `json:"stream_id"`
	CID           int64             `json:"cid"`
	Mode          string            `json:"mode"`
	StreamType    string            `json:"stream_type"`
	Accessibility string            `json:"accessibility"`
	Name          string            `json:"name,omitempty"`
	Publisher     PeerRef           `json:"publisher"`
	Properties    map[string]string `json:"properties,omitempty"`
	ViewerCount   int               `json:"viewer_count"`
	CreatedAt     int64             `json:"created_at"`
}

// StreamEvent 是 stream_added / stream_updated 的负载。
type StreamEvent struct {
	Stream Stream `json:"stream"`
}

// StreamRemovedEvent 是 stream_removed 的负载。
type StreamRemovedEvent struct {
	StreamID string `json:"stream_id"`
	Reason   string `json:"reason"`
}

// SubscribeReadyEvent 是 invite_only 获批后的通知。
type SubscribeReadyEvent struct {
	StreamID string   `json:"stream_id"`
	Mode     string   `json:"mode"`
	Peer     *PeerRef `json:"peer,omitempty"`
}

// JoinRequestEvent 通知发布者有人请求观看。
type JoinRequestEvent struct {
	StreamID string `json:"stream_id"`
	CLID     int    `json:"clid"`
	UID      string `json:"uid"`
	Nickname string `json:"nickname,omitempty"`
}

// JoinRejectedEvent 通知订阅者其请求被拒绝。
type JoinRejectedEvent struct {
	StreamID string `json:"stream_id"`
	Reason   string `json:"reason,omitempty"`
}

// PeerEvent 是 P2P 模式下 peer_joined / peer_left 的负载。
type PeerEvent struct {
	StreamID string `json:"stream_id"`
	CLID     int    `json:"clid"`
	UID      string `json:"uid,omitempty"`
	Nickname string `json:"nickname,omitempty"`
	Reason   string `json:"reason,omitempty"`
}

// RemovedFromStreamEvent 通知客户端被移出某路流。
type RemovedFromStreamEvent struct {
	StreamID string `json:"stream_id"`
	Reason   string `json:"reason"`
}

// TokenExpiringEvent 提醒客户端续签。
type TokenExpiringEvent struct {
	ExpiresAt int64 `json:"expires_at"`
}

// StatsRequestEvent 请求客户端上报质量数据。
type StatsRequestEvent struct {
	StreamID string `json:"stream_id"`
}

// ByeEvent 是服务端主动断开的说明。
type ByeEvent struct {
	Code    string `json:"code"`
	Message string `json:"message,omitempty"`
}
