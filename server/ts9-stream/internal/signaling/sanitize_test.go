package signaling

import (
	"strconv"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestSanitizeName(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{"普通文本", "我的屏幕", "我的屏幕"},
		{"换行制表转空格", "a\nb\tc\rd", "a b c d"},
		{"删除控制字符", "a\x00b\x01c\x7fd", "abcd"},
		{"两端空白裁掉", "  hello  ", "hello"},
		{"仅控制字符结果为空", "\x00\x01\x02", ""},
		{"换行在两端会被裁掉", "\nhello\n", "hello"},
		{"空串", "", ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := sanitizeName(tc.in); got != tc.want {
				t.Errorf("sanitizeName(%q) = %q, 期望 %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestSanitizeNameTruncatesASCII(t *testing.T) {
	in := strings.Repeat("a", maxNameLen+50)
	got := sanitizeName(in)
	if len(got) != maxNameLen {
		t.Fatalf("截断后字节数 = %d, 期望 %d", len(got), maxNameLen)
	}
}

// 中文占 3 字节，128 不是 3 的整数倍，截断必须落在字符边界上而不是切半个字符。
func TestSanitizeNameTruncatesOnRuneBoundary(t *testing.T) {
	in := strings.Repeat("屏", 60) // 180 字节
	got := sanitizeName(in)
	if len(got) > maxNameLen {
		t.Fatalf("截断后字节数 = %d, 超过上限 %d", len(got), maxNameLen)
	}
	if len(got) != 126 {
		t.Errorf("截断后字节数 = %d, 期望 126（42 个 3 字节字符）", len(got))
	}
	if n := len([]rune(got)); n != 42 {
		t.Errorf("截断后字符数 = %d, 期望 42", n)
	}
	if !strings.HasPrefix(in, got) {
		t.Error("截断结果不是原串前缀，说明切断了多字节字符")
	}
	if !utf8.ValidString(got) {
		t.Error("截断后不是合法 UTF-8")
	}
}

func TestClampPropertiesEmptyReturnsNonNilMap(t *testing.T) {
	for _, in := range []map[string]string{nil, {}} {
		got := clampProperties(in, 4000)
		if got == nil {
			t.Fatal("clampProperties 返回 nil，应返回空 map 以便 JSON 序列化为 {}")
		}
		if len(got) != 0 {
			t.Errorf("len = %d, 期望 0", len(got))
		}
	}
}

func TestClampPropertiesCopiesInput(t *testing.T) {
	in := map[string]string{"width": "1920"}
	got := clampProperties(in, 4000)
	got["width"] = "changed"
	if in["width"] != "1920" {
		t.Error("clampProperties 未复制入参，修改结果影响了原字典")
	}
}

func TestClampPropertiesLimitsKeyCount(t *testing.T) {
	in := make(map[string]string, maxProperties*2)
	for i := 0; i < maxProperties*2; i++ {
		in["k"+strconv.Itoa(i)] = "v"
	}
	got := clampProperties(in, 4000)
	if len(got) != maxProperties {
		t.Fatalf("len = %d, 期望 %d", len(got), maxProperties)
	}
}

func TestClampPropertiesSkipsEmptyKey(t *testing.T) {
	got := clampProperties(map[string]string{
		"":       "dropped",
		"  ":     "dropped",
		"\x00":   "dropped",
		"height": "1080",
	}, 4000)
	if len(got) != 1 {
		t.Fatalf("len = %d, 期望 1, got = %v", len(got), got)
	}
	if got["height"] != "1080" {
		t.Errorf("height = %q", got["height"])
	}
}

func TestClampPropertiesTruncatesValue(t *testing.T) {
	got := clampProperties(map[string]string{
		"note": strings.Repeat("x", maxPropertyValueLen+100),
	}, 4000)
	if len(got["note"]) != maxPropertyValueLen {
		t.Fatalf("值长度 = %d, 期望 %d", len(got["note"]), maxPropertyValueLen)
	}
}

// 属性值同样要按字符边界截断，否则会产出非法 UTF-8 破坏 JSON 序列化。
func TestClampPropertiesTruncatesValueOnRuneBoundary(t *testing.T) {
	in := strings.Repeat("屏", 100) // 300 字节
	got := clampProperties(map[string]string{"note": in}, 4000)["note"]
	if len(got) > maxPropertyValueLen {
		t.Fatalf("值字节数 = %d, 超过上限 %d", len(got), maxPropertyValueLen)
	}
	if len(got) != 255 {
		t.Errorf("值字节数 = %d, 期望 255（85 个 3 字节字符）", len(got))
	}
	if !utf8.ValidString(got) {
		t.Error("截断后不是合法 UTF-8，说明切断了多字节字符")
	}
	if !strings.HasPrefix(in, got) {
		t.Error("截断结果不是原串前缀")
	}
}

func TestTruncateBytes(t *testing.T) {
	tests := []struct {
		name  string
		in    string
		limit int
		want  string
	}{
		{"短于上限原样返回", "abc", 8, "abc"},
		{"等于上限原样返回", "abcd", 4, "abcd"},
		{"ASCII 精确截断", "abcdef", 3, "abc"},
		{"上限为零返回空", "abc", 0, ""},
		{"空串", "", 4, ""},
		{"多字节退到边界", "屏屏屏", 4, "屏"},
		{"多字节恰好命中边界", "屏屏屏", 6, "屏屏"},
		{"混合字节", "ab屏cd", 4, "ab"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := truncateBytes(tc.in, tc.limit)
			if got != tc.want {
				t.Errorf("truncateBytes(%q, %d) = %q, 期望 %q", tc.in, tc.limit, got, tc.want)
			}
			if !utf8.ValidString(got) {
				t.Errorf("truncateBytes(%q, %d) = %q 不是合法 UTF-8", tc.in, tc.limit, got)
			}
		})
	}
}

// 截断可能把一个空格留在末尾，clampText 必须再裁一次。
func TestClampTextTrimsAfterTruncate(t *testing.T) {
	if got := clampText("ab cd", 3); got != "ab" {
		t.Errorf("clampText(%q, 3) = %q, 期望 %q", "ab cd", got, "ab")
	}
	if got := clampText("a\tbcd", 2); got != "a" {
		t.Errorf("clampText(%q, 2) = %q, 期望 %q", "a\tbcd", got, "a")
	}
}

func TestClampPropertiesClampsBitrate(t *testing.T) {
	tests := []struct {
		name    string
		value   string
		maxKbps int
		want    string
	}{
		{"超上限改写为上限", "99999", 4000, "4000"},
		{"未超上限保留", "2500", 4000, "2500"},
		{"恰好等于上限保留", "4000", 4000, "4000"},
		{"上限为零不改写", "99999", 0, "99999"},
		{"上限为负不改写", "99999", -1, "99999"},
		{"非数值不改写", "fast", 4000, "fast"},
		{"负数不改写", "-5", 4000, "-5"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := clampProperties(map[string]string{"bitrate_kbps": tc.value}, tc.maxKbps)
			if got["bitrate_kbps"] != tc.want {
				t.Errorf("bitrate_kbps = %q, 期望 %q", got["bitrate_kbps"], tc.want)
			}
		})
	}
}

// 只有 bitrate_kbps 这个键会被夹紧，同名前缀或其他键不受影响。
func TestClampPropertiesOnlyClampsBitrateKey(t *testing.T) {
	got := clampProperties(map[string]string{
		"bitrate_kbps_max": "99999",
		"fps":              "99999",
	}, 4000)
	if got["bitrate_kbps_max"] != "99999" {
		t.Errorf("bitrate_kbps_max = %q, 不该被夹紧", got["bitrate_kbps_max"])
	}
	if got["fps"] != "99999" {
		t.Errorf("fps = %q, 不该被夹紧", got["fps"])
	}
}

func TestHasCommonCodec(t *testing.T) {
	tests := []struct {
		name string
		a    []string
		b    []string
		want bool
	}{
		{"完全相同", []string{"H264"}, []string{"H264"}, true},
		{"大小写不敏感", []string{"h264"}, []string{"H264"}, true},
		{"前后空白不敏感", []string{" H264 "}, []string{"H264"}, true},
		{"部分交集", []string{"VP9", "VP8"}, []string{"H264", "VP8"}, true},
		{"无交集", []string{"VP9"}, []string{"H264", "VP8"}, false},
		{"a 为空", nil, []string{"H264"}, false},
		{"b 为空", []string{"H264"}, nil, false},
		{"都为空", nil, nil, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := hasCommonCodec(tc.a, tc.b); got != tc.want {
				t.Errorf("hasCommonCodec(%v, %v) = %v, 期望 %v", tc.a, tc.b, got, tc.want)
			}
		})
	}
}

func TestSubKeyAndItoaSafe(t *testing.T) {
	if got := subKey(42); got != "42" {
		t.Errorf("subKey(42) = %q", got)
	}
	if got := subKey(0); got != "0" {
		t.Errorf("subKey(0) = %q", got)
	}
	if got := itoaSafe(-7); got != "-7" {
		t.Errorf("itoaSafe(-7) = %q", got)
	}
}
