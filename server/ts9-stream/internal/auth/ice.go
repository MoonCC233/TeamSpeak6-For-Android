package auth

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"strconv"
	"time"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// ICEProvider 按配置生成下发给客户端的 ICE 服务器列表。
type ICEProvider struct {
	cfg *config.ICE
}

// NewICEProvider 创建 ICE 配置提供者。
func NewICEProvider(cfg *config.ICE) *ICEProvider {
	return &ICEProvider{cfg: cfg}
}

// Servers 返回给定用户可用的 ICE 服务器列表。
//
// TURN 优先使用 coturn 的 REST 风格短时凭据（use-auth-secret），
// 这样 TURN 密码不会以长期有效的形式下发给客户端。
func (p *ICEProvider) Servers(uid string, now time.Time) []tssp.ICEServer {
	if p == nil || p.cfg == nil {
		return nil
	}
	out := make([]tssp.ICEServer, 0, 2+len(p.cfg.Static))

	if len(p.cfg.STUNURLs) > 0 {
		out = append(out, tssp.ICEServer{URLs: append([]string(nil), p.cfg.STUNURLs...)})
	}

	if len(p.cfg.TURNURLs) > 0 {
		entry := tssp.ICEServer{URLs: append([]string(nil), p.cfg.TURNURLs...)}
		if p.cfg.TURNStaticAuthSecret != "" {
			ttl := p.cfg.TURNCredentialTTL
			if ttl <= 0 {
				ttl = 10 * time.Minute
			}
			user, cred := turnRESTCredential(p.cfg.TURNStaticAuthSecret, uid, now.Add(ttl))
			entry.Username = user
			entry.Credential = cred
			entry.CredentialTTL = int64(ttl / time.Second)
		} else {
			entry.Username = p.cfg.TURNUsername
			entry.Credential = p.cfg.TURNPassword
		}
		out = append(out, entry)
	}

	for _, s := range p.cfg.Static {
		out = append(out, tssp.ICEServer{
			URLs:       append([]string(nil), s.URLs...),
			Username:   s.Username,
			Credential: s.Credential,
		})
	}
	return out
}

// turnRESTCredential 按 coturn 的 REST API 约定生成短时凭据：
// username = "<过期unix秒>:<用户标识>"，credential = base64(hmac_sha1(username, secret))。
func turnRESTCredential(secret, uid string, exp time.Time) (string, string) {
	if uid == "" {
		uid = "ts9"
	}
	username := strconv.FormatInt(exp.Unix(), 10) + ":" + uid
	m := hmac.New(sha1.New, []byte(secret))
	m.Write([]byte(username))
	return username, base64.StdEncoding.EncodeToString(m.Sum(nil))
}
