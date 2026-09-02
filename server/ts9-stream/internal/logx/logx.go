// Package logx 提供结构化日志器构造与密钥脱敏工具。
package logx

import (
	"log/slog"
	"os"
	"strconv"
	"strings"
)

// New 按名称创建结构化日志器。format 支持 "text" 与 "json"。
func New(level, format string) *slog.Logger {
	var lvl slog.Level
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		lvl = slog.LevelDebug
	case "warn", "warning":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}

	opts := &slog.HandlerOptions{Level: lvl}

	var h slog.Handler
	if strings.EqualFold(strings.TrimSpace(format), "json") {
		h = slog.NewJSONHandler(os.Stdout, opts)
	} else {
		h = slog.NewTextHandler(os.Stdout, opts)
	}
	return slog.New(h)
}

// Redact 把密钥类字符串替换为可安全记录的占位符，仅保留长度信息。
func Redact(secret string) string {
	if secret == "" {
		return "<empty>"
	}
	return "<redacted len=" + strconv.Itoa(len(secret)) + ">"
}
