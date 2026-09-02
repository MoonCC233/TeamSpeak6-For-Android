package auth

import (
	"testing"
	"time"
)

func TestRateLimiterAllowsUntilThreshold(t *testing.T) {
	r := NewRateLimiter(time.Minute, 3, time.Minute)
	now := time.Now()

	if ok, _ := r.Allow("1.2.3.4", now); !ok {
		t.Fatal("初始状态应放行")
	}
	if banned := r.Fail("1.2.3.4", now); banned {
		t.Fatal("第 1 次失败不应封禁")
	}
	if banned := r.Fail("1.2.3.4", now.Add(time.Second)); banned {
		t.Fatal("第 2 次失败不应封禁")
	}
	if ok, _ := r.Allow("1.2.3.4", now.Add(2*time.Second)); !ok {
		t.Fatal("未达阈值应继续放行")
	}
	if banned := r.Fail("1.2.3.4", now.Add(2*time.Second)); !banned {
		t.Fatal("第 3 次失败应触发封禁")
	}

	ok, retry := r.Allow("1.2.3.4", now.Add(3*time.Second))
	if ok {
		t.Fatal("封禁期内应拒绝")
	}
	if retry <= 0 {
		t.Errorf("应给出重试等待时间，得到 %v", retry)
	}
}

func TestRateLimiterBanExpires(t *testing.T) {
	r := NewRateLimiter(time.Minute, 2, 30*time.Second)
	now := time.Now()
	r.Fail("10.0.0.1", now)
	if banned := r.Fail("10.0.0.1", now); !banned {
		t.Fatal("应触发封禁")
	}
	if ok, _ := r.Allow("10.0.0.1", now.Add(29*time.Second)); ok {
		t.Fatal("封禁未到期应继续拒绝")
	}
	if ok, _ := r.Allow("10.0.0.1", now.Add(31*time.Second)); !ok {
		t.Fatal("封禁到期后应恢复放行")
	}
}

func TestRateLimiterWindowSlides(t *testing.T) {
	// 失败次数只在窗口内累计：窗口外的旧失败不应叠加导致误封。
	r := NewRateLimiter(time.Minute, 3, time.Minute)
	now := time.Now()
	r.Fail("10.0.0.2", now)
	r.Fail("10.0.0.2", now.Add(time.Second))
	if banned := r.Fail("10.0.0.2", now.Add(2*time.Minute)); banned {
		t.Fatal("窗口外的旧失败不应计入")
	}
	if ok, _ := r.Allow("10.0.0.2", now.Add(2*time.Minute)); !ok {
		t.Fatal("应仍然放行")
	}
}

func TestRateLimiterSuccessResets(t *testing.T) {
	r := NewRateLimiter(time.Minute, 3, time.Minute)
	now := time.Now()
	r.Fail("192.168.1.5", now)
	r.Fail("192.168.1.5", now)
	r.Success("192.168.1.5")

	// 清零后重新累计，两次失败仍不应封禁。
	if banned := r.Fail("192.168.1.5", now); banned {
		t.Fatal("Success 后失败计数应清零")
	}
	if banned := r.Fail("192.168.1.5", now); banned {
		t.Fatal("Success 后失败计数应清零")
	}
	if ok, _ := r.Allow("192.168.1.5", now); !ok {
		t.Fatal("应放行")
	}
}

func TestRateLimiterPerIPIsolation(t *testing.T) {
	r := NewRateLimiter(time.Minute, 2, time.Minute)
	now := time.Now()
	r.Fail("1.1.1.1", now)
	r.Fail("1.1.1.1", now)

	if ok, _ := r.Allow("1.1.1.1", now); ok {
		t.Fatal("被封禁的 IP 应拒绝")
	}
	if ok, _ := r.Allow("2.2.2.2", now); !ok {
		t.Fatal("其他 IP 不应受影响")
	}
}

func TestRateLimiterNormalizesAddr(t *testing.T) {
	r := NewRateLimiter(time.Minute, 2, time.Minute)
	now := time.Now()
	// 带端口与不带端口必须落到同一条目，否则换个源端口就能绕过限流。
	r.Fail("203.0.113.9:51000", now)
	r.Fail("203.0.113.9", now)
	if ok, _ := r.Allow("203.0.113.9:52000", now); ok {
		t.Fatal("同一 IP 的不同端口应共享封禁状态")
	}
}

func TestRateLimiterNormalizeIPv6(t *testing.T) {
	r := NewRateLimiter(time.Minute, 2, time.Minute)
	now := time.Now()
	r.Fail("[2001:db8::1]:51000", now)
	r.Fail("2001:0db8:0000:0000:0000:0000:0000:0001", now)
	if ok, _ := r.Allow("2001:db8::1", now); ok {
		t.Fatal("IPv6 的等价写法应归一化到同一条目")
	}
}

func TestRateLimiterDefaults(t *testing.T) {
	r := NewRateLimiter(0, 0, 0)
	if r.window != 5*time.Minute || r.max != 10 || r.ban != 5*time.Minute {
		t.Errorf("默认值不正确: window=%v max=%d ban=%v", r.window, r.max, r.ban)
	}
}

func TestRateLimiterGCDropsStaleEntries(t *testing.T) {
	r := NewRateLimiter(time.Minute, 5, time.Minute)
	now := time.Now()
	r.Fail("198.51.100.1", now)
	r.mu.Lock()
	n := len(r.entries)
	r.mu.Unlock()
	if n != 1 {
		t.Fatalf("条目数 = %d, 期望 1", n)
	}

	// 越过一个完整窗口后触发 GC，过期条目应被清掉。
	r.Fail("198.51.100.2", now.Add(3*time.Minute))
	r.mu.Lock()
	_, stale := r.entries["198.51.100.1"]
	r.mu.Unlock()
	if stale {
		t.Error("过期条目应被 GC 清除")
	}
}

func TestNormalizeIPPassthrough(t *testing.T) {
	// 非 IP 字符串（例如反向代理传来的主机名）原样保留。
	if got := normalizeIP("proxy.internal"); got != "proxy.internal" {
		t.Errorf("normalizeIP = %q", got)
	}
	if got := normalizeIP("1.2.3.4"); got != "1.2.3.4" {
		t.Errorf("normalizeIP = %q", got)
	}
}
