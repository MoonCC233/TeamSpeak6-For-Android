package serverquery

import (
	"reflect"
	"testing"
)

func TestEscapeRoundTrip(t *testing.T) {
	cases := []string{
		"",
		"plain",
		"with space",
		`back\slash`,
		"pipe|char",
		"slash/char",
		"tab\there",
		"newline\nhere",
		"cr\rhere",
		"bell\ahere",
		"vertical\vtab",
		"form\ffeed",
		"backspace\bhere",
		`all \ / | at once`,
		"中文昵称 与 空格",
	}
	for _, in := range cases {
		got := Unescape(Escape(in))
		if got != in {
			t.Errorf("往返失败: 输入 %q，转义后 %q，还原为 %q", in, Escape(in), got)
		}
	}
}

func TestEscapeBackslashFirst(t *testing.T) {
	// 反斜杠必须先转义，否则 `\s` 会被误解析成空格。
	if got, want := Escape(`\s`), `\\s`; got != want {
		t.Fatalf("Escape(%q) = %q, 期望 %q", `\s`, got, want)
	}
	if got, want := Unescape(`\\s`), `\s`; got != want {
		t.Fatalf("Unescape(%q) = %q, 期望 %q", `\\s`, got, want)
	}
}

func TestEscapeTable(t *testing.T) {
	cases := map[string]string{
		" ":  `\s`,
		"|":  `\p`,
		"/":  `\/`,
		"\\": `\\`,
		"\n": `\n`,
		"\r": `\r`,
		"\t": `\t`,
		"\a": `\a`,
		"\b": `\b`,
		"\f": `\f`,
		"\v": `\v`,
	}
	for in, want := range cases {
		if got := Escape(in); got != want {
			t.Errorf("Escape(%q) = %q, 期望 %q", in, got, want)
		}
	}
}

func TestUnescapeUnknownSequence(t *testing.T) {
	// 未知转义序列必须原样保留，不能丢字符。
	if got, want := Unescape(`\q`), `\q`; got != want {
		t.Errorf("Unescape(%q) = %q, 期望 %q", `\q`, got, want)
	}
	// 末尾孤立的反斜杠也不能越界。
	if got, want := Unescape(`abc\`), `abc\`; got != want {
		t.Errorf("Unescape(%q) = %q, 期望 %q", `abc\`, got, want)
	}
}

func TestBuildCommand(t *testing.T) {
	got := BuildCommand("clientinfo", map[string]string{"clid": "7"})
	if want := "clientinfo clid=7"; got != want {
		t.Errorf("BuildCommand = %q, 期望 %q", got, want)
	}

	// 键名排序保证输出稳定。
	got = BuildCommand("clientdbfind", map[string]string{"pattern": "a b", "cldbid": "3"})
	if want := `clientdbfind cldbid=3 pattern=a\sb`; got != want {
		t.Errorf("BuildCommand = %q, 期望 %q", got, want)
	}

	got = BuildCommand("clientlist", nil, "-uid", "groups")
	if want := "clientlist -uid -groups"; got != want {
		t.Errorf("BuildCommand = %q, 期望 %q", got, want)
	}
}

func TestBuildCommandEscapesInjection(t *testing.T) {
	// 攻击者控制的昵称里塞入空格与换行，转义后不能拆出新的参数或新命令。
	got := BuildCommand("clientfind", map[string]string{"pattern": "x\r\nserverstop"})
	if want := `clientfind pattern=x\r\nserverstop`; got != want {
		t.Fatalf("BuildCommand = %q, 期望 %q", got, want)
	}
}

func TestParseRecord(t *testing.T) {
	rec := ParseRecord(`clid=5 cid=7 client_database_id=40 client_nickname=Test\sUser client_type=0`)
	if got, ok := rec.Int("clid"); !ok || got != 5 {
		t.Errorf("clid = %d (ok=%v), 期望 5", got, ok)
	}
	if got, ok := rec.Int64("cid"); !ok || got != 7 {
		t.Errorf("cid = %d (ok=%v), 期望 7", got, ok)
	}
	if got := rec.Str("client_nickname"); got != "Test User" {
		t.Errorf("client_nickname = %q, 期望 %q", got, "Test User")
	}
	if got, ok := rec.Int("client_type"); !ok || got != 0 {
		t.Errorf("client_type = %d (ok=%v), 期望 0", got, ok)
	}
}

func TestParseRecordFlagWithoutValue(t *testing.T) {
	rec := ParseRecord("virtualserver_status=online -uid")
	if _, ok := rec["-uid"]; !ok {
		t.Error("无值的键应映射为空字符串")
	}
}

func TestParseRecords(t *testing.T) {
	line := `clid=1 cid=1 client_nickname=A|clid=2 cid=5 client_nickname=B\sB`
	recs := ParseRecords(line)
	if len(recs) != 2 {
		t.Fatalf("记录数 = %d, 期望 2", len(recs))
	}
	if got := recs[0].Str("client_nickname"); got != "A" {
		t.Errorf("第一条昵称 = %q, 期望 A", got)
	}
	if got := recs[1].Str("client_nickname"); got != "B B" {
		t.Errorf("第二条昵称 = %q, 期望 %q", got, "B B")
	}
	if got, _ := recs[1].Int64("cid"); got != 5 {
		t.Errorf("第二条 cid = %d, 期望 5", got)
	}
}

func TestParseRecordsEmpty(t *testing.T) {
	if recs := ParseRecords(""); recs != nil {
		t.Errorf("空行应返回 nil，得到 %v", recs)
	}
	if recs := ParseRecords("  \r\n"); recs != nil {
		t.Errorf("空白行应返回 nil，得到 %v", recs)
	}
}

func TestRecordIntList(t *testing.T) {
	rec := ParseRecord("client_servergroups=6,8,15")
	if got, want := rec.IntList("client_servergroups"), []int{6, 8, 15}; !reflect.DeepEqual(got, want) {
		t.Errorf("IntList = %v, 期望 %v", got, want)
	}
	if got := rec.IntList("missing"); got != nil {
		t.Errorf("缺失键应返回 nil，得到 %v", got)
	}
	// 含空白与非法项时跳过非法项。
	rec2 := ParseRecord("g=1,\\s2,x,3")
	if got, want := rec2.IntList("g"), []int{1, 2, 3}; !reflect.DeepEqual(got, want) {
		t.Errorf("IntList = %v, 期望 %v", got, want)
	}
}

func TestRecordIntInvalid(t *testing.T) {
	rec := ParseRecord("a=notanumber b=")
	if _, ok := rec.Int("a"); ok {
		t.Error("非法整数应返回 ok=false")
	}
	if _, ok := rec.Int("b"); ok {
		t.Error("空值应返回 ok=false")
	}
	if _, ok := rec.Int("missing"); ok {
		t.Error("缺失键应返回 ok=false")
	}
}

func TestParseErrorLine(t *testing.T) {
	qe, ok := parseErrorLine("error id=0 msg=ok")
	if !ok {
		t.Fatal("应识别为 error 行")
	}
	if qe.ID != 0 || qe.Msg != "ok" {
		t.Errorf("解析结果 = %+v", qe)
	}

	qe, ok = parseErrorLine(`error id=512 msg=invalid\sclientID`)
	if !ok {
		t.Fatal("应识别为 error 行")
	}
	if qe.ID != ErrIDInvalidClientID {
		t.Errorf("ID = %d, 期望 %d", qe.ID, ErrIDInvalidClientID)
	}
	if qe.Msg != "invalid clientID" {
		t.Errorf("Msg = %q", qe.Msg)
	}

	if _, ok := parseErrorLine("clid=5 cid=7"); ok {
		t.Error("数据行不应被识别为 error 行")
	}
	// 前缀相同但不是 error 命令的行不能误判。
	if _, ok := parseErrorLine("errorcode=5"); ok {
		t.Error("errorcode=5 不应被识别为 error 行")
	}
}

func TestParseErrorLineExtraMsg(t *testing.T) {
	qe, ok := parseErrorLine(`error id=2568 msg=insufficient\sclient\spermissions extra_msg=failed\son\sb_client_info_view`)
	if !ok {
		t.Fatal("应识别为 error 行")
	}
	if qe.ID != ErrIDInsufficientPermissions {
		t.Errorf("ID = %d", qe.ID)
	}
	if qe.ExtraMsg != "failed on b_client_info_view" {
		t.Errorf("ExtraMsg = %q", qe.ExtraMsg)
	}
	qe.Command = "clientinfo"
	if got := qe.Error(); got == "" {
		t.Error("Error() 不应为空")
	}
}

func TestErrorClassification(t *testing.T) {
	if !IsClientNotFound(&QueryError{ID: ErrIDInvalidClientID}) {
		t.Error("512 应判定为客户端不存在")
	}
	if !IsClientNotFound(&QueryError{ID: ErrIDDatabaseEmptyResult}) {
		t.Error("1281 应判定为客户端不存在")
	}
	if IsClientNotFound(&QueryError{ID: ErrIDInsufficientPermissions}) {
		t.Error("2568 不应判定为客户端不存在")
	}
	if !IsPermissionDenied(&QueryError{ID: ErrIDInsufficientPermissions}) {
		t.Error("2568 应判定为权限不足")
	}
	if IsClientNotFound(nil) || IsPermissionDenied(nil) {
		t.Error("nil 错误不应命中任何分类")
	}
}

func TestResponseFirst(t *testing.T) {
	var empty Response
	if got := empty.First(); len(got) != 0 {
		t.Errorf("空响应的 First 应为空 Record，得到 %v", got)
	}
	r := &Response{Records: []Record{{"a": "1"}, {"a": "2"}}}
	if got := r.First().Str("a"); got != "1" {
		t.Errorf("First = %q, 期望 1", got)
	}
}

func TestCommandName(t *testing.T) {
	if got := commandName("clientinfo clid=5"); got != "clientinfo" {
		t.Errorf("commandName = %q", got)
	}
	if got := commandName("whoami"); got != "whoami" {
		t.Errorf("commandName = %q", got)
	}
}
