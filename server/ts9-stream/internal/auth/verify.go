package auth

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"sync"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/serverquery"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// QueryClient 是校验所需的 ServerQuery 能力，抽象成接口便于测试。
type QueryClient interface {
	ClientInfo(ctx context.Context, clid int) (serverquery.ClientInfo, error)
	Close() error
}

// Identity 是校验通过后的客户端身份。
type Identity struct {
	ServerAddr string
	ServerHash string
	VirtualSrv *config.VirtualSrv
	UID        string
	CLID       int
	CID        int64
	Nickname   string
	Groups     []int
}

// Verifier 用 ServerQuery 反向校验客户端声明的身份。
type Verifier struct {
	cfg *config.Config
	log *slog.Logger

	mu      sync.Mutex
	clients map[string]QueryClient
	// newClient 可被测试替换。
	newClient func(*config.VirtualSrv) QueryClient
}

// NewVerifier 创建校验器。
func NewVerifier(cfg *config.Config, log *slog.Logger) *Verifier {
	v := &Verifier{
		cfg:     cfg,
		log:     log,
		clients: make(map[string]QueryClient),
	}
	v.newClient = v.defaultNewClient
	return v
}

// SetClientFactory 覆盖 ServerQuery 客户端的构造方式，仅用于测试。
func (v *Verifier) SetClientFactory(f func(*config.VirtualSrv) QueryClient) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.newClient = f
	v.clients = make(map[string]QueryClient)
}

func (v *Verifier) defaultNewClient(srv *config.VirtualSrv) QueryClient {
	return serverquery.NewClient(serverquery.Options{
		Protocol:    string(srv.QueryProtocol),
		Host:        srv.QueryHost,
		Port:        srv.QueryPort,
		User:        srv.QueryUser,
		Password:    srv.QueryPassword,
		VirtualPort: srv.VirtualPort,
		Timeout:     srv.QueryTimeout,
		CacheTTL:    v.cfg.Auth.QueryCacheTTL,
	}, queryLogger{v.log})
}

type queryLogger struct{ log *slog.Logger }

func (q queryLogger) Debug(msg string, args ...any) {
	if q.log != nil {
		q.log.Debug(msg, args...)
	}
}

func (q queryLogger) Warn(msg string, args ...any) {
	if q.log != nil {
		q.log.Warn(msg, args...)
	}
}

// Close 关闭所有 ServerQuery 连接。
func (v *Verifier) Close() {
	v.mu.Lock()
	clients := v.clients
	v.clients = make(map[string]QueryClient)
	v.mu.Unlock()
	for _, c := range clients {
		_ = c.Close()
	}
}

func (v *Verifier) clientFor(srv *config.VirtualSrv) QueryClient {
	key := srv.QueryHost + ":" + strconv.Itoa(srv.QueryPort) + "#" + strconv.Itoa(srv.VirtualPort)
	v.mu.Lock()
	defer v.mu.Unlock()
	if c, ok := v.clients[key]; ok {
		return c
	}
	c := v.newClient(srv)
	v.clients[key] = c
	return c
}

// Verify 核对客户端声明的 (server_addr, uid, clid, cid) 是否真实在线，
// 并施加服务器组白/黑名单。任何失败都返回 TSSP 错误。
func (v *Verifier) Verify(ctx context.Context, serverAddr, uid string, clid int, cid int64) (*Identity, error) {
	if uid == "" {
		return nil, tssp.NewError(tssp.ErrBadRequest, "uid 不能为空")
	}
	if clid <= 0 {
		return nil, tssp.NewError(tssp.ErrBadRequest, "clid 必须为正整数")
	}
	if cid < 0 {
		return nil, tssp.NewError(tssp.ErrBadRequest, "cid 非法")
	}

	norm, err := config.NormalizeServerAddr(serverAddr)
	if err != nil {
		return nil, tssp.NewError(tssp.ErrBadRequest, "server_addr 非法: "+err.Error())
	}
	srv, ok := v.cfg.FindServer(norm)
	if !ok {
		return nil, tssp.NewError(tssp.ErrUnknownServer, "服务端未配置该虚拟服务器: "+norm)
	}

	info, err := v.clientFor(srv).ClientInfo(ctx, clid)
	if err != nil {
		switch {
		case serverquery.IsClientNotFound(err):
			return nil, tssp.NewError(tssp.ErrClientNotFound, fmt.Sprintf("clid=%d 不在线", clid))
		case serverquery.IsPermissionDenied(err):
			v.log.Error("ServerQuery 账号权限不足，无法执行 clientinfo", "err", err)
			return nil, tssp.NewError(tssp.ErrQueryUnavailable, "服务端 ServerQuery 账号权限不足")
		default:
			v.log.Warn("ServerQuery 查询失败", "server_addr", norm, "clid", clid, "err", err)
			return nil, tssp.NewRetryError(tssp.ErrQueryUnavailable, "无法连接 tsserver 的 ServerQuery 接口", 3000)
		}
	}

	if info.UID != uid {
		return nil, tssp.NewError(tssp.ErrIdentityMismatch, "uid 与服务器记录不一致")
	}
	if info.IsQueryClient() {
		return nil, tssp.NewError(tssp.ErrNotAllowed, "ServerQuery 客户端不能参与流会话")
	}
	if info.CID != cid {
		return nil, tssp.NewError(tssp.ErrIdentityMismatch,
			fmt.Sprintf("cid 与服务器记录不一致：声明 %d，实际 %d", cid, info.CID))
	}
	if err := v.checkGroups(info.ServerGroups); err != nil {
		return nil, err
	}

	return &Identity{
		ServerAddr: norm,
		ServerHash: HashServerAddr(norm),
		VirtualSrv: srv,
		UID:        info.UID,
		CLID:       info.CLID,
		CID:        info.CID,
		Nickname:   info.Nickname,
		Groups:     info.ServerGroups,
	}, nil
}

func (v *Verifier) checkGroups(groups []int) error {
	for _, g := range groups {
		for _, deny := range v.cfg.Access.DenyServerGroups {
			if g == deny {
				return tssp.NewError(tssp.ErrNotAllowed, "所属服务器组被禁止使用屏幕共享")
			}
		}
	}
	if len(v.cfg.Access.AllowServerGroups) == 0 {
		return nil
	}
	for _, g := range groups {
		for _, allow := range v.cfg.Access.AllowServerGroups {
			if g == allow {
				return nil
			}
		}
	}
	return tssp.NewError(tssp.ErrNotAllowed, "所属服务器组不在允许名单内")
}

// ErrNoQueryClient 表示未能取得可用的 ServerQuery 客户端。
var ErrNoQueryClient = errors.New("没有可用的 ServerQuery 客户端")

// HealthCheck 对所有配置的虚拟服务器做一次连通性探测，返回第一个错误。
func (v *Verifier) HealthCheck(ctx context.Context) error {
	if len(v.cfg.Servers) == 0 {
		return ErrNoQueryClient
	}
	for i := range v.cfg.Servers {
		srv := &v.cfg.Servers[i]
		// clid=0 永不存在；这里只关心「连接与登录是否成功」，
		// 返回 CLIENT_NOT_FOUND 说明链路是通的。
		_, err := v.clientFor(srv).ClientInfo(ctx, 0)
		if err == nil || serverquery.IsClientNotFound(err) {
			continue
		}
		return fmt.Errorf("虚拟服务器 %v 的 ServerQuery 不可用: %w", srv.ServerAddr, err)
	}
	return nil
}

// 编译期断言：serverquery.Client 满足 QueryClient。
var _ QueryClient = (*serverquery.Client)(nil)
