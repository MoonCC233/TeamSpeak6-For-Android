package auth

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/tssp"
)

// wantCode 断言错误是指定错误码的 TSSP 错误。
func wantCode(t *testing.T, err error, code string) {
	t.Helper()
	if err == nil {
		t.Fatalf("期望错误码 %s，但没有错误", code)
	}
	var te *tssp.Error
	if !errors.As(err, &te) {
		t.Fatalf("期望 TSSP 错误，得到 %T: %v", err, err)
	}
	if te.Code != code {
		t.Fatalf("错误码 = %s（%s），期望 %s", te.Code, te.Message, code)
	}
}

func testClaims() Claims {
	return Claims{
		SessionID:  "sess-1",
		UID:        "abcdefghijklmnopqrstuvwxyz0=",
		CLID:       7,
		CID:        12,
		ServerHash: HashServerAddr("127.0.0.1:9987"),
	}
}

func TestSignVerifyRoundTrip(t *testing.T) {
	s, err := NewSigner("super-secret", 10*time.Minute)
	if err != nil {
		t.Fatalf("NewSigner 失败: %v", err)
	}
	now := time.Now()
	token, exp, err := s.Sign(testClaims(), now)
	if err != nil {
		t.Fatalf("Sign 失败: %v", err)
	}
	if !exp.After(now) {
		t.Fatalf("过期时间 %v 应晚于 %v", exp, now)
	}
	if exp.Sub(now) != 10*time.Minute {
		t.Errorf("有效期 = %v, 期望 10m", exp.Sub(now))
	}
	if !strings.HasPrefix(token, "v1.") {
		t.Errorf("令牌缺少版本前缀: %q", token)
	}

	claims, err := s.Verify(token, now.Add(time.Minute))
	if err != nil {
		t.Fatalf("Verify 失败: %v", err)
	}
	want := testClaims()
	if claims.SessionID != want.SessionID || claims.UID != want.UID ||
		claims.CLID != want.CLID || claims.CID != want.CID || claims.ServerHash != want.ServerHash {
		t.Errorf("声明不一致: %+v", claims)
	}
	if claims.ExpiresAt().UnixMilli() != exp.UnixMilli() {
		t.Errorf("ExpiresAt = %v, 期望 %v", claims.ExpiresAt(), exp)
	}
}

func TestNewSignerRejectsEmptySecret(t *testing.T) {
	if _, err := NewSigner("", time.Minute); err == nil {
		t.Fatal("空密钥应报错")
	}
}

func TestNewSignerDefaultTTL(t *testing.T) {
	s, err := NewSigner("k", 0)
	if err != nil {
		t.Fatal(err)
	}
	if s.TTL() != 10*time.Minute {
		t.Errorf("默认 TTL = %v, 期望 10m", s.TTL())
	}
}

func TestVerifyRejectsTamperedPayload(t *testing.T) {
	s, _ := NewSigner("super-secret", time.Minute)
	now := time.Now()
	token, _, err := s.Sign(testClaims(), now)
	if err != nil {
		t.Fatal(err)
	}
	parts := strings.Split(token, ".")

	// 把 clid 改成 999 后重新编码负载，签名自然不再匹配。
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatal(err)
	}
	var c Claims
	if err := json.Unmarshal(raw, &c); err != nil {
		t.Fatal(err)
	}
	c.CLID = 999
	forged, err := json.Marshal(&c)
	if err != nil {
		t.Fatal(err)
	}
	tampered := parts[0] + "." + base64.RawURLEncoding.EncodeToString(forged) + "." + parts[2]

	_, err = s.Verify(tampered, now)
	wantCode(t, err, tssp.ErrTokenInvalid)
}

func TestVerifyRejectsTamperedSignature(t *testing.T) {
	s, _ := NewSigner("super-secret", time.Minute)
	now := time.Now()
	token, _, _ := s.Sign(testClaims(), now)
	parts := strings.Split(token, ".")

	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		t.Fatal(err)
	}
	sig[0] ^= 0xFF
	bad := parts[0] + "." + parts[1] + "." + base64.RawURLEncoding.EncodeToString(sig)

	_, err = s.Verify(bad, now)
	wantCode(t, err, tssp.ErrTokenInvalid)
}

func TestVerifyRejectsOtherSecret(t *testing.T) {
	a, _ := NewSigner("secret-a", time.Minute)
	b, _ := NewSigner("secret-b", time.Minute)
	now := time.Now()
	token, _, _ := a.Sign(testClaims(), now)

	_, err := b.Verify(token, now)
	wantCode(t, err, tssp.ErrTokenInvalid)
}

func TestVerifyRejectsMalformedTokens(t *testing.T) {
	s, _ := NewSigner("super-secret", time.Minute)
	now := time.Now()
	valid, _, _ := s.Sign(testClaims(), now)
	parts := strings.Split(valid, ".")

	cases := map[string]string{
		"空串":         "",
		"段数不足":       "v1.payload",
		"段数过多":       valid + ".extra",
		"版本前缀错误":     "v2." + parts[1] + "." + parts[2],
		"签名非法base64": parts[0] + "." + parts[1] + ".!!!!",
	}
	for name, token := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := s.Verify(token, now)
			wantCode(t, err, tssp.ErrTokenInvalid)
		})
	}
}

func TestVerifyRejectsPayloadWithoutSessionID(t *testing.T) {
	s, _ := NewSigner("super-secret", time.Minute)
	now := time.Now()
	c := testClaims()
	c.SessionID = ""
	token, _, err := s.Sign(c, now)
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.Verify(token, now)
	wantCode(t, err, tssp.ErrTokenInvalid)
}

func TestVerifyExpired(t *testing.T) {
	ttl := 10 * time.Minute
	s, _ := NewSigner("super-secret", ttl)
	now := time.Now()
	token, exp, _ := s.Sign(testClaims(), now)

	// 恰好到期的瞬间即视为过期。
	if _, err := s.Verify(token, exp); err == nil {
		t.Fatal("到期瞬间应判定为过期")
	} else {
		wantCode(t, err, tssp.ErrTokenExpired)
	}

	_, err := s.Verify(token, now.Add(ttl+time.Second))
	wantCode(t, err, tssp.ErrTokenExpired)
}

func TestVerifyExpiredStillReportsInvalidWhenSignatureBroken(t *testing.T) {
	// 校验顺序必须是「先验签名，再看过期」：签名坏了就不该泄露过期信息。
	s, _ := NewSigner("super-secret", time.Minute)
	now := time.Now()
	token, _, _ := s.Sign(testClaims(), now)
	parts := strings.Split(token, ".")
	sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
	sig[len(sig)-1] ^= 0x01
	broken := parts[0] + "." + parts[1] + "." + base64.RawURLEncoding.EncodeToString(sig)

	_, err := s.Verify(broken, now.Add(time.Hour))
	wantCode(t, err, tssp.ErrTokenInvalid)
}

func TestHashServerAddr(t *testing.T) {
	a := HashServerAddr("127.0.0.1:9987")
	if len(a) != 64 {
		t.Errorf("哈希长度 = %d, 期望 64", len(a))
	}
	if a != HashServerAddr("127.0.0.1:9987") {
		t.Error("同一输入应得到相同哈希")
	}
	if a == HashServerAddr("127.0.0.1:9988") {
		t.Error("不同输入不应得到相同哈希")
	}
	if strings.Contains(a, "127.0.0.1") {
		t.Error("哈希不应包含原始地址")
	}
}
