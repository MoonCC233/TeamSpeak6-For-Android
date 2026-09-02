// Package serverquery 实现 TeamSpeak ServerQuery 的行协议、转义规则与连接管理。
//
// 本包只使用官方公开的 ServerQuery 接口，且只执行读取类命令，
// 不修改 tsserver 的任何状态。
package serverquery

import (
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// Escape 按 ServerQuery 规则转义参数值。
//
// 转义表见官方文档：反斜杠必须最先处理，否则会二次转义。
func Escape(s string) string {
	var b strings.Builder
	b.Grow(len(s) + 8)
	for _, r := range s {
		switch r {
		case '\\':
			b.WriteString(`\\`)
		case '/':
			b.WriteString(`\/`)
		case ' ':
			b.WriteString(`\s`)
		case '|':
			b.WriteString(`\p`)
		case '\a':
			b.WriteString(`\a`)
		case '\b':
			b.WriteString(`\b`)
		case '\f':
			b.WriteString(`\f`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		case '\v':
			b.WriteString(`\v`)
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// Unescape 还原 ServerQuery 转义后的值。
func Unescape(s string) string {
	if !strings.ContainsRune(s, '\\') {
		return s
	}
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c != '\\' || i+1 >= len(s) {
			b.WriteByte(c)
			continue
		}
		i++
		switch s[i] {
		case '\\':
			b.WriteByte('\\')
		case '/':
			b.WriteByte('/')
		case 's':
			b.WriteByte(' ')
		case 'p':
			b.WriteByte('|')
		case 'a':
			b.WriteByte('\a')
		case 'b':
			b.WriteByte('\b')
		case 'f':
			b.WriteByte('\f')
		case 'n':
			b.WriteByte('\n')
		case 'r':
			b.WriteByte('\r')
		case 't':
			b.WriteByte('\t')
		case 'v':
			b.WriteByte('\v')
		default:
			// 未知转义序列保持原样，避免丢失信息。
			b.WriteByte('\\')
			b.WriteByte(s[i])
		}
	}
	return b.String()
}

// BuildCommand 拼装一条 ServerQuery 命令。
//
// params 为键值参数（值会被转义），flags 为不带值的选项（如 -uid）。
func BuildCommand(cmd string, params map[string]string, flags ...string) string {
	var b strings.Builder
	b.WriteString(cmd)
	// 为了输出稳定（便于测试与日志比对），按键名排序。
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		b.WriteByte(' ')
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(Escape(params[k]))
	}
	for _, f := range flags {
		b.WriteByte(' ')
		if !strings.HasPrefix(f, "-") {
			b.WriteByte('-')
		}
		b.WriteString(f)
	}
	return b.String()
}

// Record 是一条 ServerQuery 记录，键为属性名，值已反转义。
type Record map[string]string

// Str 返回字符串值。
func (r Record) Str(key string) string { return r[key] }

// Int 解析整数值，缺失或非法时返回 ok=false。
func (r Record) Int(key string) (int, bool) {
	v, ok := r[key]
	if !ok || v == "" {
		return 0, false
	}
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return 0, false
	}
	return n, true
}

// Int64 解析 64 位整数值。
func (r Record) Int64(key string) (int64, bool) {
	v, ok := r[key]
	if !ok || v == "" {
		return 0, false
	}
	n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
	if err != nil {
		return 0, false
	}
	return n, true
}

// IntList 解析以逗号分隔的整数列表，例如 client_servergroups=6,8,15。
func (r Record) IntList(key string) []int {
	v, ok := r[key]
	if !ok || v == "" {
		return nil
	}
	parts := strings.Split(v, ",")
	out := make([]int, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		if n, err := strconv.Atoi(p); err == nil {
			out = append(out, n)
		}
	}
	return out
}

// ParseRecords 解析一行响应数据，多条记录以 | 分隔。
func ParseRecords(line string) []Record {
	line = strings.TrimRight(line, "\r\n")
	if strings.TrimSpace(line) == "" {
		return nil
	}
	chunks := strings.Split(line, "|")
	out := make([]Record, 0, len(chunks))
	for _, chunk := range chunks {
		rec := ParseRecord(chunk)
		if len(rec) > 0 {
			out = append(out, rec)
		}
	}
	return out
}

// ParseRecord 解析单条记录。无值的键映射为空字符串。
func ParseRecord(chunk string) Record {
	rec := make(Record)
	for _, field := range strings.Fields(chunk) {
		if field == "" {
			continue
		}
		if eq := strings.IndexByte(field, '='); eq >= 0 {
			key := field[:eq]
			if key == "" {
				continue
			}
			rec[key] = Unescape(field[eq+1:])
		} else {
			rec[field] = ""
		}
	}
	return rec
}

// QueryError 表示 tsserver 返回的非零错误。
type QueryError struct {
	ID       int
	Msg      string
	ExtraMsg string
	Command  string
}

func (e *QueryError) Error() string {
	s := fmt.Sprintf("serverquery %s 失败: error id=%d msg=%s", e.Command, e.ID, e.Msg)
	if e.ExtraMsg != "" {
		s += " (" + e.ExtraMsg + ")"
	}
	return s
}

// ServerQuery 错误码，仅列出本服务会区分处理的少数几个。
const (
	// ErrIDOK 表示成功。
	ErrIDOK = 0
	// ErrIDInvalidClientID 表示 clid 不存在。
	ErrIDInvalidClientID = 512
	// ErrIDInvalidChannelID 表示 cid 不存在。
	ErrIDInvalidChannelID = 768
	// ErrIDDatabaseEmptyResult 表示查询无结果。
	ErrIDDatabaseEmptyResult = 1281
	// ErrIDServerNotFound 表示虚拟服务器不存在。
	ErrIDServerNotFound = 1024
	// ErrIDInsufficientPermissions 表示 query 账号权限不足。
	ErrIDInsufficientPermissions = 2568
)

// IsClientNotFound 判断错误是否为「客户端不存在」。
func IsClientNotFound(err error) bool {
	var qe *QueryError
	if errors.As(err, &qe) {
		return qe.ID == ErrIDInvalidClientID || qe.ID == ErrIDDatabaseEmptyResult
	}
	return false
}

// IsPermissionDenied 判断错误是否为权限不足。
func IsPermissionDenied(err error) bool {
	var qe *QueryError
	if errors.As(err, &qe) {
		return qe.ID == ErrIDInsufficientPermissions
	}
	return false
}

// parseErrorLine 解析 "error id=0 msg=ok" 形式的结束行。
func parseErrorLine(line string) (*QueryError, bool) {
	trimmed := strings.TrimSpace(line)
	if !strings.HasPrefix(trimmed, "error ") && trimmed != "error" {
		return nil, false
	}
	rec := ParseRecord(strings.TrimPrefix(trimmed, "error"))
	id, _ := rec.Int("id")
	return &QueryError{
		ID:       id,
		Msg:      rec.Str("msg"),
		ExtraMsg: rec.Str("extra_msg"),
	}, true
}
