// Package auth 实现 TSSP 的身份校验：ServerQuery 反向核对、会话令牌签发与校验、
// 速率限制以及 ICE/TURN 短时凭据签发。
//
// 规范见 docs/protocol/tssp-v1.md §4。
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// tokenPrefix 是令牌的版本前缀。
const tokenPrefix = "v1"

// Claims 是会话令牌的负载。字段名保持紧凑以缩短令牌长度。
type Claims struct {
	SessionID  string `json:"sid"`
	UID        string `json:"uid"`
	CLID       int    `json:"clid"`
	CID        int64  `json:"cid"`
	ServerHash string `json:"sa"`
	// ExpiresAtMS 是过期时间，Unix 毫秒。
	ExpiresAtMS int64 `json:"exp"`
}

// ExpiresAt 返回过期时间。
func (c *Claims) ExpiresAt() time.Time {
	return time.UnixMilli(c.ExpiresAtMS)
}

// Signer 负责会话令牌的签发与校验。
type Signer struct {
	secret []byte
	ttl    time.Duration
}

// NewSigner 创建签名器。secret 不得为空。
func NewSigner(secret string, ttl time.Duration) (*Signer, error) {
	if secret == "" {
		return nil, fmt.Errorf("token secret 为空")
	}
	if ttl <= 0 {
		ttl = 10 * time.Minute
	}
	return &Signer{secret: []byte(secret), ttl: ttl}, nil
}

// TTL 返回令牌有效期。
func (s *Signer) TTL() time.Duration { return s.ttl }

// Sign 基于身份签发令牌，过期时间为 now+TTL。
func (s *Signer) Sign(claims Claims, now time.Time) (string, time.Time, error) {
	exp := now.Add(s.ttl)
	claims.ExpiresAtMS = exp.UnixMilli()
	payload, err := json.Marshal(&claims)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("序列化令牌负载: %w", err)
	}
	encPayload := base64.RawURLEncoding.EncodeToString(payload)
	sig := s.mac(encPayload)
	return tokenPrefix + "." + encPayload + "." + base64.RawURLEncoding.EncodeToString(sig), exp, nil
}

// Verify 校验令牌签名与有效期，返回其中的身份声明。
//
// 校验顺序：格式 → 签名 → 过期。先验签名再看过期，避免用过期判断泄露签名信息。
func (s *Signer) Verify(token string, now time.Time) (*Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] != tokenPrefix {
		return nil, tssp.NewError(tssp.ErrTokenInvalid, "令牌格式错误")
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, tssp.NewError(tssp.ErrTokenInvalid, "令牌签名无法解码")
	}
	if !hmac.Equal(sig, s.mac(parts[1])) {
		return nil, tssp.NewError(tssp.ErrTokenInvalid, "令牌签名不匹配")
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, tssp.NewError(tssp.ErrTokenInvalid, "令牌负载无法解码")
	}
	var claims Claims
	if err := json.Unmarshal(raw, &claims); err != nil {
		return nil, tssp.NewError(tssp.ErrTokenInvalid, "令牌负载无法解析")
	}
	if claims.SessionID == "" {
		return nil, tssp.NewError(tssp.ErrTokenInvalid, "令牌缺少会话标识")
	}
	if now.UnixMilli() >= claims.ExpiresAtMS {
		return nil, tssp.NewError(tssp.ErrTokenExpired, "令牌已过期，请调用 renew")
	}
	return &claims, nil
}

func (s *Signer) mac(encPayload string) []byte {
	m := hmac.New(sha256.New, s.secret)
	m.Write([]byte(tokenPrefix))
	m.Write([]byte{'.'})
	m.Write([]byte(encPayload))
	return m.Sum(nil)
}

// HashServerAddr 返回归一化服务器地址的 SHA-256 十六进制串。
// 令牌中只放哈希，避免把服务器地址明文暴露给持有令牌的第三方。
func HashServerAddr(normalizedAddr string) string {
	sum := sha256.Sum256([]byte(normalizedAddr))
	return hex.EncodeToString(sum[:])
}
