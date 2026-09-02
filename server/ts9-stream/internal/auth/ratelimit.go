package auth

import (
	"net"
	"sync"
	"time"
)

// RateLimiter 按客户端 IP 统计 hello 失败次数，超限后临时封禁。
//
// 这是防止暴力猜测 clid/uid 组合、以及防止把 tsserver 的 ServerQuery 打爆的第一道闸。
type RateLimiter struct {
	window time.Duration
	max    int
	ban    time.Duration

	mu      sync.Mutex
	entries map[string]*rlEntry
	// lastGC 记录上次清理时间，避免每次调用都遍历整表。
	lastGC time.Time
}

type rlEntry struct {
	failures  []time.Time
	bannedTil time.Time
}

// NewRateLimiter 创建限流器。window 内失败超过 max 次则封禁 ban 时长。
func NewRateLimiter(window time.Duration, max int, ban time.Duration) *RateLimiter {
	if window <= 0 {
		window = 5 * time.Minute
	}
	if max <= 0 {
		max = 10
	}
	if ban <= 0 {
		ban = 5 * time.Minute
	}
	return &RateLimiter{
		window:  window,
		max:     max,
		ban:     ban,
		entries: make(map[string]*rlEntry),
	}
}

// Allow 判断该 IP 当前是否允许发起 hello。
// 第二个返回值是建议的重试等待时间。
func (r *RateLimiter) Allow(ip string, now time.Time) (bool, time.Duration) {
	key := normalizeIP(ip)
	r.mu.Lock()
	defer r.mu.Unlock()
	r.gcLocked(now)

	e, ok := r.entries[key]
	if !ok {
		return true, 0
	}
	if now.Before(e.bannedTil) {
		return false, e.bannedTil.Sub(now)
	}
	return true, 0
}

// Fail 记一次失败，返回是否因此被封禁。
func (r *RateLimiter) Fail(ip string, now time.Time) bool {
	key := normalizeIP(ip)
	r.mu.Lock()
	defer r.mu.Unlock()
	r.gcLocked(now)

	e, ok := r.entries[key]
	if !ok {
		e = &rlEntry{}
		r.entries[key] = e
	}
	cutoff := now.Add(-r.window)
	kept := e.failures[:0]
	for _, t := range e.failures {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	e.failures = append(kept, now)
	if len(e.failures) >= r.max {
		e.bannedTil = now.Add(r.ban)
		e.failures = nil
		return true
	}
	return false
}

// Success 清除该 IP 的失败记录。
func (r *RateLimiter) Success(ip string) {
	key := normalizeIP(ip)
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.entries, key)
}

func (r *RateLimiter) gcLocked(now time.Time) {
	if now.Sub(r.lastGC) < r.window {
		return
	}
	r.lastGC = now
	cutoff := now.Add(-r.window)
	for k, e := range r.entries {
		if now.Before(e.bannedTil) {
			continue
		}
		fresh := false
		for _, t := range e.failures {
			if t.After(cutoff) {
				fresh = true
				break
			}
		}
		if !fresh {
			delete(r.entries, k)
		}
	}
}

// normalizeIP 去掉端口并归一化，使同一主机的多次连接落到同一条目。
func normalizeIP(addr string) string {
	if host, _, err := net.SplitHostPort(addr); err == nil {
		addr = host
	}
	if ip := net.ParseIP(addr); ip != nil {
		return ip.String()
	}
	return addr
}
