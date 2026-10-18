//! TS6 屏幕共享协议探针（A2b 验证工具）
//!
//! 用途：连接真实 TS6 服务器，验证「TS6-ANDROID-PATCH 未知通知透传」端到端可用：
//!
//! ```text
//! # 发布端（触发 setupstream，等待 notifystreamstarted 回执）
//! cargo run -p screen-share-probe -- ts.example.com:9987 --publish MyScreen
//!
//! # 观看端（对发布者发起观看请求；发布端应打印 JoinRequest）
//! cargo run -p screen-share-probe -- ts.example.com:9987 --join <stream_id> --source-clid 42
//!
//! # 全信令闭环（双探针自动应答，验证 C4/N5 与 C5/N6 往返）
//! # 发布端：
//! cargo run -p screen-share-probe -- ts.example.com:9987 --publish MyScreen --auto-respond
//! # 观看端：
//! cargo run -p screen-share-probe -- ts.example.com:9987 --join <stream_id> \
//!     --source-clid <发布者 clid> --auto-answer
//! ```
//!
//! 所有屏幕共享事件以 JSON 行输出（与 tslib-jni 的 `screen_share` 事件同构），
//! 每行带进程内相对时间戳，便于跨进程对齐时延（起始墙钟见 `# START unix_ms=`）。
//! 协议规格见仓库 `docs/A2-screen-share-protocol.md`。

use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::Result;
use clap::Parser;
use tslib_core::{Client, ClientConfig, Event, Identity, ScreenShareEvent};

/// TS6 屏幕共享协议探针
#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    /// 服务器地址（host:port）
    server: String,

    /// 昵称
    #[arg(short, long, default_value = "Ts6Probe")]
    nickname: String,

    /// 服务器密码（可选）
    #[arg(short, long)]
    password: Option<String>,

    /// 身份文件（不存在则创建；同一服务器上多实例须使用不同身份）
    #[arg(short, long, default_value = "ts6-probe-identity.json")]
    identity: String,

    /// 连接后查询指定客户端的直播状态（可多次，对应 `requeststreaminfo`）
    #[arg(long)]
    watch: Vec<u16>,

    /// 连接后发起屏幕共享（参数为流名称，对应 `setupstream`）
    #[arg(long)]
    publish: Option<String>,

    /// 连接后请求观看指定流 id（对应 `joinstreamrequest`，需配合 --source-clid）
    #[arg(long)]
    join: Option<String>,

    /// --join 对应的发布者客户端 id
    #[arg(long)]
    source_clid: Option<u16>,

    /// 收到 JoinRequest 时自动回复占位 offer（验证 C4 → N5 往返）
    #[arg(long)]
    auto_respond: bool,

    /// 收到 offer 时自动回复 answer 与 ICE 候选（验证 C5 → N6 往返；需配合 --source-clid）
    #[arg(long)]
    auto_answer: bool,

    /// 自动退出秒数（默认运行到 Ctrl+C）
    #[arg(long)]
    stop_after: Option<u64>,
}

/// 占位 SDP（仅验证信令中转，不建立真实媒体会话）
const DUMMY_SDP: &str = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n\
     m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
/// 占位 ICE 候选
const DUMMY_ICE: &str = "candidate:1 1 udp 2130706431 127.0.0.1 9 typ host generation 0";

/// 按探针统一格式打印一行（进程启动墙钟见 `# START unix_ms=`）
fn report(start: &Instant, message: impl AsRef<str>) {
    println!("[+{:>8}ms] {}", start.elapsed().as_millis(), message.as_ref());
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // 日志：`RUST_LOG=tslib_core=debug,tsclientlib=debug` 可观察未知命令与连接细节
    let _ = tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .with_writer(std::io::stderr)
        .try_init();

    let start = Instant::now();
    let unix_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    println!("# START unix_ms={unix_ms}");

    // 加载或创建身份
    let identity = if std::path::Path::new(&args.identity).exists() {
        report(&start, format!("# 加载身份 {}", args.identity));
        Identity::load(&args.identity)?
    } else {
        let identity = Identity::create()?;
        identity.save(&args.identity)?;
        report(&start, format!("# 已创建身份并保存到 {}", args.identity));
        identity
    };

    let mut builder = ClientConfig::builder()
        .address(&args.server)
        .identity(identity)
        .nickname(&args.nickname);
    if let Some(password) = &args.password {
        builder = builder.password(password);
    }
    let config = builder.build()?;

    let mut client = Client::connect(config)?;
    report(&start, format!("# 已连接 {}，等待服务器事件…", args.server));

    // 等待连接就绪，然后执行一次性动作
    client.wait_connected().await?;
    run_actions(&mut client, &args, &start)?;

    let deadline = args
        .stop_after
        .map(|secs| tokio::time::Instant::now() + Duration::from_secs(secs));

    loop {
        if let Some(deadline) = deadline {
            if tokio::time::Instant::now() >= deadline {
                break;
            }
        }

        tokio::select! {
            _ = tokio::signal::ctrl_c() => break,
            result = client.process_events() => {
                for event in result? {
                    print_event(&event, &start);
                    handle_auto_actions(&mut client, &args, &event, &start)?;
                }
            }
        }
    }

    let _ = client.disconnect();
    report(&start, "# 已断开");
    Ok(())
}

/// 连接就绪后执行的一次性动作（全部为协议命令发送）
fn run_actions(client: &mut Client, args: &Args, start: &Instant) -> Result<()> {
    for client_id in &args.watch {
        report(start, format!("# 发送 requeststreaminfo clid={client_id}"));
        client.request_stream_info(*client_id)?;
    }

    if let Some(name) = &args.publish {
        report(
            start,
            format!("# 发送 setupstream name={name}（等待 notifystreamstarted）"),
        );
        client.setup_screen_share(name, false)?;
    }

    if let Some(stream_id) = &args.join {
        let source = args
            .source_clid
            .ok_or_else(|| anyhow::anyhow!("--join 需要同时指定 --source-clid"))?;
        report(
            start,
            format!("# 发送 joinstreamrequest id={stream_id} clid={source}"),
        );
        client.join_screen_share(stream_id, source)?;
    }

    Ok(())
}

/// 打印关注的事件（屏幕共享事件以 JSON 行输出）
fn print_event(event: &Event, start: &Instant) {
    match event {
        Event::Connected { server_name, .. } => {
            report(start, format!("# 连接成功（服务器：{server_name}）"));
        }
        Event::Disconnected { reason } => {
            report(start, format!("# 已断开：{reason}"));
        }
        Event::ConnectionLost { reason } => {
            report(start, format!("# 连接丢失：{reason}"));
        }
        Event::TextMessage {
            sender_name,
            message,
            ..
        } => {
            report(start, format!("# [消息] {sender_name}: {message}"));
        }
        Event::ScreenShare(screen_event) => {
            let json = serde_json::to_string(screen_event).unwrap_or_default();
            println!("SCREEN_SHARE {json}");
        }
        Event::CommandError { error_id, message } => {
            // 服务器对命令的错误应答（例如权限不足、未知命令）
            println!("COMMAND_ERROR id={error_id} message={message}");
        }
        _ => {}
    }
}

/// 自动应答动作（可选，用于验证信令往返）
fn handle_auto_actions(
    client: &mut Client, args: &Args, event: &Event, start: &Instant,
) -> Result<()> {
    let Event::ScreenShare(screen_event) = event else {
        return Ok(());
    };

    match screen_event {
        ScreenShareEvent::JoinRequest {
            stream_id,
            viewer_id,
        } => {
            if args.auto_respond {
                report(
                    start,
                    format!("# 收到观看请求 clid={viewer_id}，自动回复占位 offer"),
                );
                client.respond_screen_share_join(stream_id, *viewer_id, DUMMY_SDP)?;
                report(start, "# 已发送 respondjoinstreamrequest（decision=1）");
            }
        }
        ScreenShareEvent::RespondJoinRequest { stream_id, offer } => {
            report(start, format!("# 收到 offer（{} 字节）", offer.len()));
            if args.auto_answer {
                let publisher = args.source_clid.ok_or_else(|| {
                    anyhow::anyhow!("--auto-answer 需要 --source-clid 指定发布者 clid")
                })?;
                let answer = tslib_core::screen_share::answer_signal_json(DUMMY_SDP);
                report(start, "# 发送 answer（streamsignaling）");
                client.send_screen_share_signal(stream_id, publisher, &answer)?;
                let ice = tslib_core::screen_share::ice_candidate_signal_json(
                    DUMMY_ICE,
                    Some("0"),
                    Some(0),
                );
                report(start, "# 发送 ICE 候选（streamsignaling）");
                client.send_screen_share_signal(stream_id, publisher, &ice)?;
            }
        }
        _ => {}
    }

    Ok(())
}
