package signaling

import (
	"strconv"
	"strings"
)

// maxNameLen 是流名称与拒绝原因的长度上限，防止被塞入超长文本广播给全频道。
const maxNameLen = 128

// maxProperties 是 properties 字典的键数量上限。
const maxProperties = 32

// maxPropertyValueLen 是单个属性值的长度上限。
const maxPropertyValueLen = 256

// stripControl 把 \n \r \t 归一为空格、删除其余控制字符，并裁掉两端空白。
func stripControl(s string) string {
	s = strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r == '\t' {
			return ' '
		}
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, s)
	return strings.TrimSpace(s)
}

// truncateBytes 按 rune 边界把字符串截断到不超过 limit 字节，避免切断多字节字符。
func truncateBytes(s string, limit int) string {
	if len(s) <= limit {
		return s
	}
	cut := 0
	for i := range s {
		if i > limit {
			break
		}
		cut = i
	}
	return s[:cut]
}

// clampText 清洗控制字符并按 rune 边界截断到 limit 字节。
func clampText(s string, limit int) string {
	return strings.TrimSpace(truncateBytes(stripControl(s), limit))
}

// sanitizeName 去掉控制字符并把名称截断到 maxNameLen 字节。
func sanitizeName(s string) string {
	return clampText(s, maxNameLen)
}

// clampProperties 复制并清洗属性字典，同时把 bitrate_kbps 截断到服务端上限。
func clampProperties(in map[string]string, maxBitrateKbps int) map[string]string {
	if len(in) == 0 {
		return map[string]string{}
	}
	out := make(map[string]string, len(in))
	for k, v := range in {
		if len(out) >= maxProperties {
			break
		}
		key := sanitizeName(k)
		if key == "" {
			continue
		}
		val := clampText(v, maxPropertyValueLen)
		if key == "bitrate_kbps" && maxBitrateKbps > 0 {
			if n, err := strconv.Atoi(val); err == nil && n > maxBitrateKbps {
				val = strconv.Itoa(maxBitrateKbps)
			}
		}
		out[key] = val
	}
	return out
}

// hasCommonCodec 判断两个编解码列表是否有交集（大小写不敏感）。
func hasCommonCodec(a, b []string) bool {
	for _, x := range a {
		for _, y := range b {
			if strings.EqualFold(strings.TrimSpace(x), strings.TrimSpace(y)) {
				return true
			}
		}
	}
	return false
}

// subKey 把 clid 转换为 SFU 内部的订阅者键。
func subKey(clid int) string {
	return strconv.Itoa(clid)
}

func itoaSafe(n int) string {
	return strconv.Itoa(n)
}
