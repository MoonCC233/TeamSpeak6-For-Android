package auth

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
)

func TestICEProviderNil(t *testing.T) {
	var p *ICEProvider
	if got := p.Servers("uid", time.Now()); got != nil {
		t.Errorf("nil provider 应返回 nil，得到 %v", got)
	}
	if got := NewICEProvider(nil).Servers("uid", time.Now()); got != nil {
		t.Errorf("nil 配置应返回 nil，得到 %v", got)
	}
}

func TestICEProviderSTUNOnly(t *testing.T) {
	p := NewICEProvider(&config.ICE{STUNURLs: []string{"stun:stun.example.com:3478"}})
	got := p.Servers("uid", time.Now())
	if len(got) != 1 {
		t.Fatalf("条目数 = %d, 期望 1", len(got))
	}
	if got[0].URLs[0] != "stun:stun.example.com:3478" {
		t.Errorf("URL = %v", got[0].URLs)
	}
	if got[0].Username != "" || got[0].Credential != "" {
		t.Error("STUN 条目不应带凭据")
	}
}

func TestICEProviderTURNStaticCredential(t *testing.T) {
	p := NewICEProvider(&config.ICE{
		TURNURLs:     []string{"turn:turn.example.com:3478?transport=udp"},
		TURNUsername: "user",
		TURNPassword: "pass",
	})
	got := p.Servers("uid", time.Now())
	if len(got) != 1 {
		t.Fatalf("条目数 = %d, 期望 1", len(got))
	}
	if got[0].Username != "user" || got[0].Credential != "pass" {
		t.Errorf("凭据 = %q/%q", got[0].Username, got[0].Credential)
	}
	if got[0].CredentialTTL != 0 {
		t.Error("长期凭据不应带 TTL")
	}
}

func TestICEProviderTURNRESTCredential(t *testing.T) {
	const secret = "turn-secret"
	ttl := 5 * time.Minute
	now := time.Unix(1700000000, 0)
	p := NewICEProvider(&config.ICE{
		TURNURLs:             []string{"turn:turn.example.com:3478"},
		TURNStaticAuthSecret: secret,
		TURNCredentialTTL:    ttl,
		// 即使配了长期凭据，也应优先使用短时凭据。
		TURNUsername: "ignored",
		TURNPassword: "ignored",
	})
	got := p.Servers("myuid", now)
	if len(got) != 1 {
		t.Fatalf("条目数 = %d, 期望 1", len(got))
	}
	entry := got[0]

	wantUser := strconv.FormatInt(now.Add(ttl).Unix(), 10) + ":myuid"
	if entry.Username != wantUser {
		t.Errorf("username = %q, 期望 %q", entry.Username, wantUser)
	}
	m := hmac.New(sha1.New, []byte(secret))
	m.Write([]byte(wantUser))
	if want := base64.StdEncoding.EncodeToString(m.Sum(nil)); entry.Credential != want {
		t.Errorf("credential = %q, 期望 %q", entry.Credential, want)
	}
	if entry.CredentialTTL != int64(ttl/time.Second) {
		t.Errorf("credential_ttl = %d, 期望 %d", entry.CredentialTTL, int64(ttl/time.Second))
	}
	if strings.Contains(entry.Credential, secret) {
		t.Error("下发内容不得包含 TURN 密钥本身")
	}
}

func TestICEProviderTURNRESTDefaultTTL(t *testing.T) {
	now := time.Unix(1700000000, 0)
	p := NewICEProvider(&config.ICE{
		TURNURLs:             []string{"turn:t:3478"},
		TURNStaticAuthSecret: "s",
	})
	got := p.Servers("u", now)
	if got[0].CredentialTTL != int64((10 * time.Minute).Seconds()) {
		t.Errorf("默认 TTL = %d 秒, 期望 600", got[0].CredentialTTL)
	}
}

func TestICEProviderTURNRESTAnonymousUID(t *testing.T) {
	now := time.Unix(1700000000, 0)
	p := NewICEProvider(&config.ICE{
		TURNURLs:             []string{"turn:t:3478"},
		TURNStaticAuthSecret: "s",
		TURNCredentialTTL:    time.Minute,
	})
	got := p.Servers("", now)
	if !strings.HasSuffix(got[0].Username, ":ts9") {
		t.Errorf("空 uid 应回退为 ts9，得到 %q", got[0].Username)
	}
}

func TestICEProviderCredentialsRotate(t *testing.T) {
	p := NewICEProvider(&config.ICE{
		TURNURLs:             []string{"turn:t:3478"},
		TURNStaticAuthSecret: "s",
		TURNCredentialTTL:    time.Minute,
	})
	base := time.Unix(1700000000, 0)
	a := p.Servers("u", base)[0]
	b := p.Servers("u", base.Add(time.Second))[0]
	if a.Credential == b.Credential {
		t.Error("不同签发时刻应产生不同凭据")
	}
	c := p.Servers("other", base)[0]
	if a.Credential == c.Credential {
		t.Error("不同用户应产生不同凭据")
	}
}

func TestICEProviderStaticEntries(t *testing.T) {
	p := NewICEProvider(&config.ICE{
		STUNURLs: []string{"stun:a:3478"},
		Static: []config.ICEServer{
			{URLs: []string{"turns:b:5349"}, Username: "u2", Credential: "c2"},
		},
	})
	got := p.Servers("uid", time.Now())
	if len(got) != 2 {
		t.Fatalf("条目数 = %d, 期望 2", len(got))
	}
	last := got[len(got)-1]
	if last.URLs[0] != "turns:b:5349" || last.Username != "u2" || last.Credential != "c2" {
		t.Errorf("静态条目未原样下发: %+v", last)
	}
}

func TestICEProviderCopiesURLSlices(t *testing.T) {
	cfg := &config.ICE{STUNURLs: []string{"stun:a:3478"}}
	p := NewICEProvider(cfg)
	got := p.Servers("uid", time.Now())
	got[0].URLs[0] = "mutated"
	if cfg.STUNURLs[0] != "stun:a:3478" {
		t.Error("返回值应为副本，不能让调用方改写配置")
	}
}
