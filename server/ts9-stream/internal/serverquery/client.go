package serverquery

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// Response 是一条命令的完整响应。
type Response struct {
	Records []Record
	Raw     []string
}

// First 返回第一条记录，没有记录时返回空 Record。
func (r *Response) First() Record {
	if len(r.Records) == 0 {
		return Record{}
	}
	return r.Records[0]
}

// Conn 是单条 ServerQuery 连接的抽象，便于测试替换。
type Conn interface {
	// Exec 执行一条命令并返回响应。
	Exec(ctx context.Context, cmd string) (*Response, error)
	// Close 关闭连接。
	Close() error
}

// Dialer 建立一条 ServerQuery 连接。
type Dialer func(ctx context.Context) (Conn, error)

// Options 是客户端的连接参数。
type Options struct {
	Protocol string
	Host     string
	Port     int
	User     string
	Password string
	// VirtualPort 是登录后要 use 的虚拟服务器语音端口。
	VirtualPort int
	Timeout     time.Duration
	// CacheTTL 是查询结果缓存时间，0 表示不缓存。
	CacheTTL time.Duration
	// Dial 可覆盖底层连接方式，仅用于测试。
	Dial Dialer
}

// Client 是带自动重连、串行化与结果缓存的 ServerQuery 客户端。
//
// tsserver 的 ServerQuery 是有状态的请求-响应协议，同一连接上不能并发发命令，
// 因此这里用互斥锁把所有调用串行化。
type Client struct {
	opts Options
	log  Logger

	mu       sync.Mutex
	conn     Conn
	failedAt time.Time
	backoff  time.Duration

	cacheMu sync.Mutex
	cache   map[string]cacheEntry
}

// Logger 是本包需要的最小日志接口。
type Logger interface {
	Debug(msg string, args ...any)
	Warn(msg string, args ...any)
}

type nopLogger struct{}

func (nopLogger) Debug(string, ...any) {}
func (nopLogger) Warn(string, ...any)  {}

type cacheEntry struct {
	resp *Response
	err  error
	at   time.Time
}

const (
	minBackoff = 500 * time.Millisecond
	maxBackoff = 30 * time.Second
)

// errBackoff 标记「因退避期而未真正拨号」的错误，供 exec 判断是否要保留首次的根因错误。
var errBackoff = errors.New("serverquery 处于退避期")

// NewClient 创建客户端，此时不会立即建立连接。
func NewClient(opts Options, log Logger) *Client {
	if opts.Timeout <= 0 {
		opts.Timeout = 5 * time.Second
	}
	if log == nil {
		log = nopLogger{}
	}
	return &Client{
		opts:  opts,
		log:   log,
		cache: make(map[string]cacheEntry),
	}
}

// Addr 返回 query 端点地址，用于日志。
func (c *Client) Addr() string {
	return net.JoinHostPort(c.opts.Host, strconv.Itoa(c.opts.Port))
}

// Close 关闭底层连接。
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return nil
	}
	err := c.conn.Close()
	c.conn = nil
	return err
}

// ClientInfo 是本服务关心的客户端在线信息子集。
type ClientInfo struct {
	CLID         int
	UID          string
	CID          int64
	Nickname     string
	ClientType   int
	ServerGroups []int
	ChannelGroup int
	Platform     string
	Version      string
}

// IsQueryClient 判断是否为 ServerQuery 客户端（client_type=1）。
// 这类客户端不占据语音频道，不允许参与流会话。
func (ci ClientInfo) IsQueryClient() bool { return ci.ClientType != 0 }

// ClientInfo 查询指定 clid 的客户端信息。
func (c *Client) ClientInfo(ctx context.Context, clid int) (ClientInfo, error) {
	cmd := BuildCommand("clientinfo", map[string]string{"clid": strconv.Itoa(clid)})
	resp, err := c.exec(ctx, cmd, true)
	if err != nil {
		return ClientInfo{}, err
	}
	rec := resp.First()
	if len(rec) == 0 {
		return ClientInfo{}, &QueryError{ID: ErrIDInvalidClientID, Msg: "invalid clientID", Command: "clientinfo"}
	}
	return recordToClientInfo(clid, rec), nil
}

// ClientList 列出在线客户端，附带 uid、频道与服务器组。
func (c *Client) ClientList(ctx context.Context) ([]ClientInfo, error) {
	cmd := BuildCommand("clientlist", nil, "-uid", "-groups", "-info")
	resp, err := c.exec(ctx, cmd, true)
	if err != nil {
		return nil, err
	}
	out := make([]ClientInfo, 0, len(resp.Records))
	for _, rec := range resp.Records {
		clid, ok := rec.Int("clid")
		if !ok {
			continue
		}
		out = append(out, recordToClientInfo(clid, rec))
	}
	return out, nil
}

// recordToClientInfo 把一条记录映射为 ClientInfo。
//
// clientinfo 的响应不含 clid（它是请求参数），clientlist 的响应含 clid；
// 同样 clientinfo 用 cid 表示所在频道，clientlist 也用 cid，这里统一处理。
func recordToClientInfo(clid int, rec Record) ClientInfo {
	ci := ClientInfo{
		CLID:     clid,
		UID:      rec.Str("client_unique_identifier"),
		Nickname: rec.Str("client_nickname"),
		Platform: rec.Str("client_platform"),
		Version:  rec.Str("client_version"),
	}
	if v, ok := rec.Int("clid"); ok {
		ci.CLID = v
	}
	if v, ok := rec.Int64("cid"); ok {
		ci.CID = v
	} else if v, ok := rec.Int64("client_channel_id"); ok {
		// 部分版本的 clientinfo 用 client_channel_id 而非 cid。
		ci.CID = v
	}
	if v, ok := rec.Int("client_type"); ok {
		ci.ClientType = v
	}
	if v, ok := rec.Int("client_channel_group_id"); ok {
		ci.ChannelGroup = v
	}
	ci.ServerGroups = rec.IntList("client_servergroups")
	return ci
}

// WhoAmI 返回当前 query 连接的自身信息，用于连通性自检。
func (c *Client) WhoAmI(ctx context.Context) (Record, error) {
	resp, err := c.exec(ctx, "whoami", false)
	if err != nil {
		return nil, err
	}
	return resp.First(), nil
}

// ServerInfo 返回虚拟服务器信息，用于健康检查。
func (c *Client) ServerInfo(ctx context.Context) (Record, error) {
	resp, err := c.exec(ctx, "serverinfo", true)
	if err != nil {
		return nil, err
	}
	return resp.First(), nil
}

// exec 在保证连接可用的前提下执行命令，可选择走缓存。
func (c *Client) exec(ctx context.Context, cmd string, cacheable bool) (*Response, error) {
	if cacheable && c.opts.CacheTTL > 0 {
		if resp, err, ok := c.lookupCache(cmd); ok {
			return resp, err
		}
	}
	resp, err := c.execOnce(ctx, cmd, false)
	if err != nil && !isQueryError(err) {
		// 连接层错误：丢弃连接后重试一次，覆盖服务端主动断开空闲连接的情况。
		c.dropConn()
		retryResp, retryErr := c.execOnce(ctx, cmd, true)
		// 重试若在拨号前就被退避拦下，说明首次的失败才是根因；
		// 保留原错误，否则调用方只会看到「处于退避期」这类无用信息。
		if !errors.Is(retryErr, errBackoff) {
			resp, err = retryResp, retryErr
		}
	}
	if cacheable && c.opts.CacheTTL > 0 && (err == nil || isQueryError(err)) {
		c.storeCache(cmd, resp, err)
	}
	return resp, err
}

// execOnce 执行一次命令。isRetry 为 true 时，传输失败会计入退避，
// 避免服务端持续不可用时每次调用都重复拨号。
func (c *Client) execOnce(ctx context.Context, cmd string, isRetry bool) (*Response, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if err := c.ensureLocked(ctx); err != nil {
		return nil, err
	}
	resp, err := c.conn.Exec(ctx, cmd)
	if err != nil {
		if !isQueryError(err) {
			_ = c.conn.Close()
			c.conn = nil
			// 首次失败不进入退避：最常见的原因是服务端回收了空闲连接，
			// 此时应当立即重连重试，而不是把调用方挡在退避期外。
			if isRetry {
				c.noteFailureLocked()
			}
		}
		return nil, err
	}
	return resp, nil
}

func (c *Client) dropConn() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
	}
}

// ensureLocked 在持有锁的前提下确保连接已建立、已登录并已选定虚拟服务器。
func (c *Client) ensureLocked(ctx context.Context) error {
	if c.conn != nil {
		return nil
	}
	if !c.failedAt.IsZero() && time.Since(c.failedAt) < c.backoff {
		return fmt.Errorf("serverquery %s 处于退避期，剩余 %s: %w", c.Addr(),
			(c.backoff - time.Since(c.failedAt)).Truncate(time.Millisecond), errBackoff)
	}

	dial := c.opts.Dial
	if dial == nil {
		dial = c.defaultDial
	}
	conn, err := dial(ctx)
	if err != nil {
		c.noteFailureLocked()
		return fmt.Errorf("连接 serverquery %s 失败: %w", c.Addr(), err)
	}

	if err := c.handshakeLocked(ctx, conn); err != nil {
		_ = conn.Close()
		c.noteFailureLocked()
		return err
	}

	c.conn = conn
	c.failedAt = time.Time{}
	c.backoff = 0
	c.log.Debug("serverquery 连接就绪", "addr", c.Addr(), "virtual_port", c.opts.VirtualPort)
	return nil
}

// handshakeLocked 执行登录与 use。SSH 传输在建立时已完成认证，无需再 login。
func (c *Client) handshakeLocked(ctx context.Context, conn Conn) error {
	if c.opts.Protocol != "ssh" && c.opts.User != "" {
		cmd := BuildCommand("login", map[string]string{
			"client_login_name":     c.opts.User,
			"client_login_password": c.opts.Password,
		})
		if _, err := conn.Exec(ctx, cmd); err != nil {
			return fmt.Errorf("serverquery 登录失败: %w", err)
		}
	}
	if c.opts.VirtualPort > 0 {
		cmd := BuildCommand("use", map[string]string{"port": strconv.Itoa(c.opts.VirtualPort)})
		if _, err := conn.Exec(ctx, cmd); err != nil {
			return fmt.Errorf("serverquery 选择虚拟服务器 %d 失败: %w", c.opts.VirtualPort, err)
		}
	}
	return nil
}

func (c *Client) noteFailureLocked() {
	c.failedAt = time.Now()
	if c.backoff == 0 {
		c.backoff = minBackoff
	} else {
		c.backoff *= 2
		if c.backoff > maxBackoff {
			c.backoff = maxBackoff
		}
	}
}

func (c *Client) lookupCache(cmd string) (*Response, error, bool) {
	c.cacheMu.Lock()
	defer c.cacheMu.Unlock()
	e, ok := c.cache[cmd]
	if !ok || time.Since(e.at) > c.opts.CacheTTL {
		return nil, nil, false
	}
	return e.resp, e.err, true
}

func (c *Client) storeCache(cmd string, resp *Response, err error) {
	c.cacheMu.Lock()
	defer c.cacheMu.Unlock()
	// 缓存条目数以命令种类为界，正常只有个位数；超出时整体清空最省事。
	if len(c.cache) > 512 {
		c.cache = make(map[string]cacheEntry)
	}
	c.cache[cmd] = cacheEntry{resp: resp, err: err, at: time.Now()}
}

func isQueryError(err error) bool {
	var qe *QueryError
	return errors.As(err, &qe)
}

func (c *Client) defaultDial(ctx context.Context) (Conn, error) {
	addr := c.Addr()
	if c.opts.Protocol == "ssh" {
		return dialSSH(ctx, addr, c.opts)
	}
	return dialRaw(ctx, addr, c.opts.Timeout)
}

// lineConn 是基于文本行的 ServerQuery 连接实现，raw 与 ssh 共用。
type lineConn struct {
	closer  io.Closer
	extra   io.Closer
	w       io.Writer
	r       *bufio.Reader
	setDL   func(time.Time) error
	timeout time.Duration
	// greeted 表示是否已消费掉连接建立时的欢迎横幅。
	greeted bool
}

func (lc *lineConn) Close() error {
	err := lc.closer.Close()
	if lc.extra != nil {
		if e := lc.extra.Close(); err == nil {
			err = e
		}
	}
	return err
}

func (lc *lineConn) Exec(ctx context.Context, cmd string) (*Response, error) {
	deadline := time.Now().Add(lc.timeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	if lc.setDL != nil {
		if err := lc.setDL(deadline); err != nil {
			return nil, err
		}
		defer func() { _ = lc.setDL(time.Time{}) }()
	}

	if !lc.greeted {
		if err := lc.consumeGreeting(); err != nil {
			return nil, err
		}
		lc.greeted = true
	}

	if _, err := io.WriteString(lc.w, cmd+"\r\n"); err != nil {
		return nil, fmt.Errorf("发送命令失败: %w", err)
	}
	if f, ok := lc.w.(interface{ Flush() error }); ok {
		if err := f.Flush(); err != nil {
			return nil, fmt.Errorf("刷新命令失败: %w", err)
		}
	}

	resp := &Response{}
	for {
		line, err := lc.readLine()
		if err != nil {
			return nil, err
		}
		if qe, ok := parseErrorLine(line); ok {
			if qe.ID != ErrIDOK {
				qe.Command = commandName(cmd)
				return nil, qe
			}
			return resp, nil
		}
		if strings.TrimSpace(line) == "" {
			continue
		}
		resp.Raw = append(resp.Raw, line)
		resp.Records = append(resp.Records, ParseRecords(line)...)
	}
}

// consumeGreeting 读掉 "TS3" 横幅与随后的提示行。
//
// 横幅格式为 "TS3\r\nWelcome to the TeamSpeak 3 ServerQuery interface...\r\n"，
// 其中第二段可能跨多行且不以 error 行结束，因此按「读到空行或超时」处理。
func (lc *lineConn) consumeGreeting() error {
	for i := 0; i < 16; i++ {
		line, err := lc.readLine()
		if err != nil {
			return err
		}
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			return nil
		}
		if qe, ok := parseErrorLine(trimmed); ok && qe.ID == ErrIDOK {
			return nil
		}
	}
	return nil
}

func (lc *lineConn) readLine() (string, error) {
	line, err := lc.r.ReadString('\n')
	if err != nil {
		if line != "" {
			return strings.TrimRight(line, "\r\n"), nil
		}
		if errors.Is(err, io.EOF) {
			return "", fmt.Errorf("serverquery 连接已关闭: %w", err)
		}
		return "", fmt.Errorf("读取响应失败: %w", err)
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func commandName(cmd string) string {
	if i := strings.IndexByte(cmd, ' '); i > 0 {
		return cmd[:i]
	}
	return cmd
}

func dialRaw(ctx context.Context, addr string, timeout time.Duration) (Conn, error) {
	d := net.Dialer{Timeout: timeout}
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	return &lineConn{
		closer:  conn,
		w:       conn,
		r:       bufio.NewReader(conn),
		setDL:   conn.SetDeadline,
		timeout: timeout,
	}, nil
}

func dialSSH(ctx context.Context, addr string, opts Options) (Conn, error) {
	cfg := &ssh.ClientConfig{
		User:    opts.User,
		Auth:    []ssh.AuthMethod{ssh.Password(opts.Password)},
		Timeout: opts.Timeout,
		// tsserver 的 query SSH 主机密钥在首次启动时随机生成且随实例变化，
		// 没有可信分发渠道；连接目标通常是本机或同一内网。这里不校验主机密钥，
		// 但要求配置层把 query 端点限制在可信网络内。
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
	}
	d := net.Dialer{Timeout: opts.Timeout}
	tcp, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	sc, chans, reqs, err := ssh.NewClientConn(tcp, addr, cfg)
	if err != nil {
		_ = tcp.Close()
		return nil, err
	}
	client := ssh.NewClient(sc, chans, reqs)
	session, err := client.NewSession()
	if err != nil {
		_ = client.Close()
		return nil, err
	}
	stdin, err := session.StdinPipe()
	if err != nil {
		_ = session.Close()
		_ = client.Close()
		return nil, err
	}
	stdout, err := session.StdoutPipe()
	if err != nil {
		_ = session.Close()
		_ = client.Close()
		return nil, err
	}
	if err := session.Shell(); err != nil {
		_ = session.Close()
		_ = client.Close()
		return nil, err
	}
	return &lineConn{
		closer:  session,
		extra:   client,
		w:       stdin,
		r:       bufio.NewReader(stdout),
		timeout: opts.Timeout,
	}, nil
}
