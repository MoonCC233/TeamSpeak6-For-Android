//! TS6 屏幕共享协议支持（TeamSpeak 6 Screen Share）
//!
//! TS6 屏幕共享 = 客户端协议命令（由服务端中转 WebRTC 信令）+ P2P/TURN 媒体。
//! 本模块只处理"协议面"（不涉及媒体/WebRTC 本身）：
//!
//! - **发送**：构造 7 条协议命令，见 [`request_stream_info`]、[`setup_stream`]、
//!   [`join_stream_request`]、[`respond_join_stream_request`]、[`stream_signaling`]、
//!   [`remove_client_from_stream`]、[`stop_stream`]；
//! - **接收**：解析服务端下发通知，见 [`parse_notification`]。
//!
//! 协议来源：从 WebSpeak 网关实现（`pad/src/server/screen-share-coordinator.ts`）逐行提取，
//! 完整规格（命令表 / 通知表 / 信令 JSON / 端到端流程）见仓库内
//! `docs/A2-screen-share-protocol.md`。
//!
//! # 前置条件（重要）
//!
//! TS6 的 `notifystream*` 通知不在 tsproto 的 TS3 消息声明范围内。上游 tsclientlib 会直接
//! 丢弃无法解析的命令，因此必须依赖 vendored tsclientlib 的 **TS6-ANDROID-PATCH**
//! （未知命令透传，见 `tslib/vendor/tsclientlib/VENDOR-INFO.md`），通知才能到达本模块。
//! 对应入口为 `StreamItem::UnknownCommand`，分派逻辑见 `client.rs` 的 `process_stream_item`。

use serde::Serialize;
use serde_json::{json, Value};
use tsproto_packets::commands::{CommandItem, CommandParser};
use tsproto_packets::packets::{Direction, Flags, OutCommand, PacketType};

// ============================== 通知名常量 ==============================

/// 通知：流开始（也是 `setupstream` 的回执，携带流 id）
pub const NOTIFY_STREAM_STARTED: &str = "notifystreamstarted";
/// 通知：流信息（`requeststreaminfo` 的应答）
pub const NOTIFY_STREAM_INFO: &str = "notifystreaminfo";
/// 通知：流结束
pub const NOTIFY_STREAM_STOPPED: &str = "notifystreamstopped";
/// 通知：有人请求观看我的流（发布者视角）
pub const NOTIFY_JOIN_STREAM_REQUEST: &str = "notifyjoinstreamrequest";
/// 通知：来源接受了观看请求（观看者视角，参数中直接携带 offer SDP）
pub const NOTIFY_RESPOND_JOIN_STREAM_REQUEST: &str = "notifyrespondjoinstreamrequest";
/// 通知：对端信令（answer / ICE 候选）
pub const NOTIFY_STREAM_SIGNALING: &str = "notifystreamsignaling";
/// 通知：观看者离开了我的流（发布者视角）
pub const NOTIFY_STREAM_CLIENT_LEFT: &str = "notifystreamclientleft";

// ============================== 事件与信令类型 ==============================

/// 屏幕共享事件（由 TS6 通知解析而来，随 `Event::ScreenShare` 分发给上层）
#[derive(Debug, Clone, Serialize)]
pub enum ScreenShareEvent {
    /// 新流开始：流 id、发布者客户端 id、名称、是否附带音频
    StreamStarted {
        stream_id: String,
        client_id: u16,
        name: String,
        audio: bool,
    },
    /// 流信息应答（`requeststreaminfo` 的结果，字段含义同 [`ScreenShareEvent::StreamStarted`]）
    StreamInfo {
        stream_id: String,
        client_id: u16,
        name: String,
        audio: bool,
    },
    /// 流结束
    StreamStopped { stream_id: String },
    /// 有人请求观看我的流（发布者视角，需要创建 offer 并回复）
    JoinRequest { stream_id: String, viewer_id: u16 },
    /// 来源接受了观看请求（观看者视角，`offer` 为 SDP）
    RespondJoinRequest { stream_id: String, offer: String },
    /// 对端信令：`json` 为协议原文，`signal` 为尽力解析出的结构（无法识别时为 `None`）
    Signal {
        stream_id: String,
        peer_id: u16,
        json: String,
        signal: Option<ScreenShareSignal>,
    },
    /// 观看者离开（发布者视角）
    ViewerLeft { stream_id: String, viewer_id: u16 },
}

/// `streamsignaling` 的 JSON 载荷（`{"cmd": ..., "args": {...}}`，见 A2 文档 §5）
///
/// 说明：`reconnectOffer` 与 `offer` 一并归一化为 [`ScreenShareSignal::Offer`]（与 WebSpeak 行为一致）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub enum ScreenShareSignal {
    /// 对端下发 offer（观看者视角）
    Offer { sdp: String },
    /// 来源对加入请求的答复（`decision=false` 为拒绝）
    JoinResponse { decision: bool, offer: Option<String> },
    /// 对端应答（发布者视角）
    Answer { sdp: String },
    /// ICE 候选
    IceCandidate {
        candidate: String,
        mid: Option<String>,
        m_line: Option<u32>,
    },
    /// 未能识别的信令（原样保留 cmd 名，供上层扩展）
    Unknown { cmd: String },
}

// ============================== 命令构造 ==============================

/// 新建一条 C2S 命令包
fn new_command(name: &str) -> OutCommand {
    OutCommand::new(Direction::C2S, Flags::empty(), PacketType::Command, name)
}

/// C1：查询某客户端是否在直播（服务端将回复 `notifystreaminfo`）
pub fn request_stream_info(client_id: u16) -> OutCommand {
    let mut cmd = new_command("requeststreaminfo");
    cmd.write_arg("clid", &client_id);
    cmd
}

/// C2：发起屏幕共享；成功时服务端会广播 `notifystreamstarted`（内含流 id）
///
/// `with_audio` 表示是否附带系统声音；其余参数沿用 WebSpeak 已验证的组合
/// （`type=3` 视频流、`bitrate=4608`、`accessibility=1`、`mode=1`、`viewer_limit=0` 不限人数）。
pub fn setup_stream(name: &str, with_audio: bool) -> OutCommand {
    let mut cmd = new_command("setupstream");
    cmd.write_arg("name", &name);
    cmd.write_arg("type", &3);
    cmd.write_arg("bitrate", &4608);
    cmd.write_arg("accessibility", &1);
    cmd.write_arg("mode", &1);
    cmd.write_arg("viewer_limit", &0);
    cmd.write_arg("audio", &if with_audio { 1 } else { 0 });
    cmd
}

/// C3：请求观看某人的流（观看者视角；发布者将收到 `notifyjoinstreamrequest`）
///
/// 参数集严格对齐 WebSpeak（`pad/src/server/screen-share-coordinator.ts`）；
/// `msg` 虽为空串但**必须携带**，否则服务器返回 `ParameterMissing`（真实服务器实测）。
pub fn join_stream_request(stream_id: &str, source_client_id: u16) -> OutCommand {
    let mut cmd = new_command("joinstreamrequest");
    cmd.write_arg("id", &stream_id);
    cmd.write_arg("clid", &source_client_id);
    cmd.write_arg("msg", &"");
    cmd.write_arg("is_remove", &0);
    cmd.write_arg("muted", &0);
    cmd.write_arg("volume", &0);
    cmd.write_arg("hidden", &0);
    cmd
}

/// C3（主动退订）：停止观看某人的流。与加入使用同一条 `joinstreamrequest`，
/// 仅把 `is_remove` 置为 1；发布端会收到 `notifystreamclientleft` 并关闭对应 PC。
pub fn leave_stream_request(stream_id: &str, source_client_id: u16) -> OutCommand {
    let mut cmd = new_command("joinstreamrequest");
    cmd.write_arg("id", &stream_id);
    cmd.write_arg("clid", &source_client_id);
    cmd.write_arg("msg", &"");
    cmd.write_arg("is_remove", &1);
    cmd.write_arg("muted", &0);
    cmd.write_arg("volume", &0);
    cmd.write_arg("hidden", &0);
    cmd
}

/// C4：接受观看请求并下发 offer（发布者视角，回应 `notifyjoinstreamrequest`）
///
/// 同 C3，`msg=""` 必填。
pub fn respond_join_stream_request(
    stream_id: &str, viewer_id: u16, offer_sdp: &str,
) -> OutCommand {
    let mut cmd = new_command("respondjoinstreamrequest");
    cmd.write_arg("id", &stream_id);
    cmd.write_arg("clid", &viewer_id);
    cmd.write_arg("msg", &"");
    cmd.write_arg("offer", &offer_sdp);
    cmd.write_arg("decision", &1);
    cmd
}

/// C5：经服务端中转 WebRTC 信令（answer / ICE）；`json` 建议用
/// [`answer_signal_json`] / [`ice_candidate_signal_json`] 生成
pub fn stream_signaling(stream_id: &str, peer_id: u16, json: &str) -> OutCommand {
    let mut cmd = new_command("streamsignaling");
    cmd.write_arg("id", &stream_id);
    cmd.write_arg("clid", &peer_id);
    cmd.write_arg("json", &json);
    cmd
}

/// C6：把某观看者移出我的流（拒绝 / 踢出，发布者视角）
pub fn remove_client_from_stream(stream_id: &str, viewer_id: u16) -> OutCommand {
    let mut cmd = new_command("removeclientfromstream");
    cmd.write_arg("id", &stream_id);
    cmd.write_arg("clid", &viewer_id);
    cmd.write_arg("reason", &1);
    cmd
}

/// C7：停止共享
pub fn stop_stream(stream_id: &str) -> OutCommand {
    let mut cmd = new_command("stopstream");
    cmd.write_arg("id", &stream_id);
    cmd.write_arg("reason", &1);
    cmd
}

// ============================== 信令 JSON 构造 ==============================

/// 构造 `cmd=answer` 的信令 JSON（发布者应答观看者）
pub fn answer_signal_json(sdp: &str) -> String {
    json!({ "cmd": "answer", "args": { "answer": sdp } }).to_string()
}

/// 构造 `cmd=iceCandidate` 的信令 JSON
///
/// 与 WebSpeak 一致：`mid` 仅在已知时携带，`mLine` 仅在已知时携带。
pub fn ice_candidate_signal_json(candidate: &str, mid: Option<&str>, m_line: Option<u32>) -> String {
    let mut args = serde_json::Map::new();
    args.insert("sdp".to_string(), Value::String(candidate.to_string()));
    if let Some(mid) = mid {
        args.insert("mid".to_string(), Value::String(mid.to_string()));
    }
    if let Some(line) = m_line {
        args.insert("mLine".to_string(), Value::from(line));
    }
    json!({ "cmd": "iceCandidate", "args": args }).to_string()
}

// ============================== 通知解析 ==============================

/// 解析一条（透传的）服务端命令；若与屏幕共享无关则返回 `None`。
///
/// 由 `client.rs` 的 `process_stream_item` 在收到 `StreamItem::UnknownCommand` 时调用。
pub fn parse_notification(name: &str, content: &[u8]) -> Option<ScreenShareEvent> {
    let args = parse_args(content);

    match name {
        NOTIFY_STREAM_STARTED => {
            let (stream_id, client_id, name, audio) = parse_stream_descriptor(&args)?;
            Some(ScreenShareEvent::StreamStarted {
                stream_id,
                client_id,
                name,
                audio,
            })
        }
        NOTIFY_STREAM_INFO => {
            let (stream_id, client_id, name, audio) = parse_stream_descriptor(&args)?;
            Some(ScreenShareEvent::StreamInfo {
                stream_id,
                client_id,
                name,
                audio,
            })
        }
        NOTIFY_STREAM_STOPPED => {
            let stream_id = arg(args.as_slice(), &["id", "stream_id"])?.to_string();
            Some(ScreenShareEvent::StreamStopped { stream_id })
        }
        NOTIFY_JOIN_STREAM_REQUEST => {
            let stream_id = arg(args.as_slice(), &["id", "stream_id"])?.to_string();
            let viewer_id = parse_u16(arg(args.as_slice(), &["clid"])?)?;
            Some(ScreenShareEvent::JoinRequest {
                stream_id,
                viewer_id,
            })
        }
        NOTIFY_RESPOND_JOIN_STREAM_REQUEST => {
            let stream_id = arg(args.as_slice(), &["id", "stream_id"])?.to_string();
            let offer = arg(args.as_slice(), &["offer", "sdp"])?.to_string();
            Some(ScreenShareEvent::RespondJoinRequest { stream_id, offer })
        }
        NOTIFY_STREAM_SIGNALING => {
            let stream_id = arg(args.as_slice(), &["id", "stream_id"])?.to_string();
            let peer_id = parse_u16(arg(args.as_slice(), &["clid"])?)?;
            let json = arg(args.as_slice(), &["json", "data"])?.to_string();
            let signal = parse_signal_json(&json);
            Some(ScreenShareEvent::Signal {
                stream_id,
                peer_id,
                json,
                signal,
            })
        }
        NOTIFY_STREAM_CLIENT_LEFT => {
            let stream_id = arg(args.as_slice(), &["id", "stream_id"])?.to_string();
            let viewer_id = parse_u16(arg(args.as_slice(), &["clid"])?)?;
            Some(ScreenShareEvent::ViewerLeft {
                stream_id,
                viewer_id,
            })
        }
        _ => None,
    }
}

/// 解析 `notifystreamstarted` / `notifystreaminfo` 的公共字段
fn parse_stream_descriptor(args: &[(String, String)]) -> Option<(String, u16, String, bool)> {
    let stream_id = arg(args, &["id", "stream_id"])?.to_string();
    let client_id = parse_u16(arg(args, &["clid"])?)?;
    let name = arg(args, &["name"]).unwrap_or_default().to_string();
    let audio = matches!(arg(args, &["audio"]), Some("1") | Some("true"));
    Some((stream_id, client_id, name, audio))
}

/// 解析 `streamsignaling` 的 JSON 载荷（宽松解析：无法识别时返回结构化 `Unknown`/`None`）
pub fn parse_signal_json(raw: &str) -> Option<ScreenShareSignal> {
    let root: Value = serde_json::from_str(raw).ok()?;
    let cmd = root.get("cmd")?.as_str()?.to_string();
    let args = root.get("args").cloned().unwrap_or(Value::Null);
    let str_arg =
        |key: &str| args.get(key).and_then(Value::as_str).map(|s| s.to_string());

    Some(match cmd.as_str() {
        // `reconnectOffer` 与 `offer` 归一化处理（与 WebSpeak 一致）
        "offer" | "reconnectOffer" => ScreenShareSignal::Offer {
            sdp: str_arg("offer").or_else(|| str_arg("sdp"))?,
        },
        "answer" => ScreenShareSignal::Answer {
            sdp: str_arg("answer").or_else(|| str_arg("sdp"))?,
        },
        "joinResponse" => ScreenShareSignal::JoinResponse {
            decision: args.get("decision").map(truthy).unwrap_or(true),
            offer: str_arg("offer").or_else(|| str_arg("sdp")),
        },
        "iceCandidate" => ScreenShareSignal::IceCandidate {
            candidate: str_arg("sdp").or_else(|| str_arg("candidate"))?,
            mid: str_arg("mid"),
            m_line: args.get("mLine").and_then(Value::as_u64).map(|n| n as u32),
        },
        _ => ScreenShareSignal::Unknown { cmd },
    })
}

// ============================== 内部工具 ==============================

/// 解析命令参数为 `(键, 值)` 列表（值已完成协议反转义）
fn parse_args(content: &[u8]) -> Vec<(String, String)> {
    let (_name, parser) = CommandParser::new(content);
    let mut args = Vec::new();
    for item in parser {
        if let CommandItem::Argument(argument) = item {
            let key = String::from_utf8_lossy(argument.name()).into_owned();
            let value = argument
                .value()
                .get_str()
                .map(|s| s.into_owned())
                .unwrap_or_default();
            args.push((key, value));
        }
    }
    args
}

/// 按候选键顺序取第一个存在的参数值
fn arg<'a>(args: &'a [(String, String)], keys: &[&str]) -> Option<&'a str> {
    keys.iter().find_map(|key| {
        args.iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    })
}

fn parse_u16(value: &str) -> Option<u16> {
    value.parse().ok()
}

/// 将 JSON 值按"布尔真值"解释（`true` / 非零数字 / 非 `"0"`/`"false"` 字符串）
fn truthy(value: &Value) -> bool {
    match value {
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().map(|f| f != 0.0).unwrap_or(false),
        Value::String(s) => !(s.is_empty() || s == "0" || s == "false"),
        _ => false,
    }
}

// ============================== 单元测试 ==============================

#[cfg(test)]
mod tests {
    use super::*;

    fn command_content(cmd: &OutCommand) -> String {
        String::from_utf8_lossy(cmd.0.content()).into_owned()
    }

    #[test]
    fn parses_stream_started() {
        let content = br"notifystreamstarted id=7 clid=42 name=Mein\sBildschirm audio=1";
        let event = parse_notification(NOTIFY_STREAM_STARTED, content).unwrap();
        match event {
            ScreenShareEvent::StreamStarted {
                stream_id,
                client_id,
                name,
                audio,
            } => {
                assert_eq!(stream_id, "7");
                assert_eq!(client_id, 42);
                assert_eq!(name, "Mein Bildschirm");
                assert!(audio);
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_stream_info_with_stream_id_fallback() {
        let content = br"notifystreaminfo stream_id=99 clid=1 name=Cam audio=0";
        let event = parse_notification(NOTIFY_STREAM_INFO, content).unwrap();
        match event {
            ScreenShareEvent::StreamInfo {
                stream_id,
                client_id,
                name,
                audio,
            } => {
                assert_eq!(stream_id, "99");
                assert_eq!(client_id, 1);
                assert_eq!(name, "Cam");
                assert!(!audio);
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_stream_stopped() {
        let event = parse_notification(NOTIFY_STREAM_STOPPED, b"notifystreamstopped id=7").unwrap();
        assert!(matches!(event, ScreenShareEvent::StreamStopped { stream_id } if stream_id == "7"));
    }

    #[test]
    fn parses_join_request() {
        let event =
            parse_notification(NOTIFY_JOIN_STREAM_REQUEST, b"notifyjoinstreamrequest id=7 clid=42")
                .unwrap();
        match event {
            ScreenShareEvent::JoinRequest {
                stream_id,
                viewer_id,
            } => {
                assert_eq!(stream_id, "7");
                assert_eq!(viewer_id, 42);
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_respond_join_request() {
        let content = br"notifyrespondjoinstreamrequest id=7 offer=v=0\so=-";
        let event = parse_notification(NOTIFY_RESPOND_JOIN_STREAM_REQUEST, content).unwrap();
        match event {
            ScreenShareEvent::RespondJoinRequest { stream_id, offer } => {
                assert_eq!(stream_id, "7");
                assert_eq!(offer, "v=0 o=-");
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_signaling_answer_with_escapes() {
        let content = br#"notifystreamsignaling id=7 clid=42 json={"cmd":"answer","args":{"answer":"v=0\so=-"}}"#;
        let event = parse_notification(NOTIFY_STREAM_SIGNALING, content).unwrap();
        match event {
            ScreenShareEvent::Signal {
                stream_id,
                peer_id,
                json,
                signal,
            } => {
                assert_eq!(stream_id, "7");
                assert_eq!(peer_id, 42);
                assert!(json.contains("\"answer\""));
                assert_eq!(
                    signal,
                    Some(ScreenShareSignal::Answer {
                        sdp: "v=0 o=-".to_string()
                    })
                );
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_signaling_ice_candidate() {
        let content = br#"notifystreamsignaling id=7 clid=42 json={"cmd":"iceCandidate","args":{"sdp":"candidate:1","mid":"0","mLine":0}}"#;
        let event = parse_notification(NOTIFY_STREAM_SIGNALING, content).unwrap();
        match event {
            ScreenShareEvent::Signal { signal, .. } => assert_eq!(
                signal,
                Some(ScreenShareSignal::IceCandidate {
                    candidate: "candidate:1".to_string(),
                    mid: Some("0".to_string()),
                    m_line: Some(0),
                })
            ),
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn parses_signaling_join_response_decision() {
        let signal = parse_signal_json(r#"{"cmd":"joinResponse","args":{"decision":0,"offer":"x"}}"#);
        assert_eq!(
            signal,
            Some(ScreenShareSignal::JoinResponse {
                decision: false,
                offer: Some("x".to_string())
            })
        );
    }

    #[test]
    fn ignores_unrelated_or_incomplete_commands() {
        assert!(parse_notification("someothercommand", b"a=1").is_none());
        // 缺少必要字段（id）时不产生事件
        assert!(parse_notification(NOTIFY_STREAM_STARTED, b"clid=1").is_none());
        assert!(parse_notification(NOTIFY_STREAM_SIGNALING, b"id=1 clid=2").is_none());
    }

    #[test]
    fn builds_setup_stream_command() {
        let content = command_content(&setup_stream("My Screen", true));
        assert!(content.contains("setupstream"));
        assert!(content.contains(r"name=My\sScreen"));
        assert!(content.contains("type=3"));
        assert!(content.contains("bitrate=4608"));
        assert!(content.contains("accessibility=1"));
        assert!(content.contains("mode=1"));
        assert!(content.contains("viewer_limit=0"));
        assert!(content.contains("audio=1"));
    }

    #[test]
    fn builds_join_and_respond_commands() {
        // 参数集严格对齐 WebSpeak（含必填的 msg；缺失会导致真实服务器报 ParameterMissing）。
        // 注意：空串值经 write_arg 序列化为裸键（无 `=`，协议解析等价于空串）
        let join = command_content(&join_stream_request("7", 42));
        assert!(join.contains("joinstreamrequest id=7 clid=42 msg is_remove=0 muted=0 volume=0 hidden=0"));

        let leave = command_content(&leave_stream_request("7", 42));
        assert!(leave.contains(
            "joinstreamrequest id=7 clid=42 msg is_remove=1 muted=0 volume=0 hidden=0"
        ));

        let respond = command_content(&respond_join_stream_request("7", 42, "v=0 o=-"));
        assert!(respond.contains(r"respondjoinstreamrequest id=7 clid=42 msg offer=v=0\so=- decision=1"));
    }

    #[test]
    fn builds_signaling_json_payloads() {
        let answer: Value = serde_json::from_str(&answer_signal_json("v=0")).unwrap();
        assert_eq!(answer["cmd"], "answer");
        assert_eq!(answer["args"]["answer"], "v=0");

        let ice: Value =
            serde_json::from_str(&ice_candidate_signal_json("candidate:1", Some("0"), Some(3)))
                .unwrap();
        assert_eq!(ice["cmd"], "iceCandidate");
        assert_eq!(ice["args"]["sdp"], "candidate:1");
        assert_eq!(ice["args"]["mid"], "0");
        assert_eq!(ice["args"]["mLine"], 3);

        // mid / mLine 未知时不应出现
        let ice: Value = serde_json::from_str(&ice_candidate_signal_json("candidate:1", None, None))
            .unwrap();
        assert!(ice["args"].get("mid").is_none());
        assert!(ice["args"].get("mLine").is_none());
    }
}
