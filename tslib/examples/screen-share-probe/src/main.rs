//! TS6 屏幕共享协议探针（A2b 验证工具）
//!
//! 用途：连接真实 TS6 服务器，验证「TS6-ANDROID-PATCH 未知通知透传」端到端可用：
//!
//! ```text
//! # 发布端（触发 setupstream，等待 notifystreamstarted 回执）
//! cargo run -p screen-share-probe -- ts.example.com:9987 --publish MyScreen
//!
//! # 观看端（对发布者发起观看请求；发布端应打印 JoinRequest）
//! cargo run -p screen-share-probe -- ts.example.com:9987 --join 123 --source-clid 42
//! ```
//!
//! 所有屏幕共享事件以 JSON 行输出（与 tslib-jni 的 `screen_share` 事件同构），
//! 协议规格见仓库 `docs/A2-screen-share-protocol.md`。

use std::time::Duration;

use anyhow::Result;
use clap::Parser;
use tslib_core::{Client, ClientConfig, Event, Identity};

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

    /// 身份文件（不存在则创建）
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

    /// 自动退出秒数（默认运行到 Ctrl+C）
    #[arg(long)]
    stop_after: Option<u64>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // 加载或创建身份
    let identity = if std::path::Path::new(&args.identity).exists() {
        println!("# 加载身份 {}", args.identity);
        Identity::load(&args.identity)?
    } else {
        let identity = Identity::create()?;
        identity.save(&args.identity)?;
        println!("# 已创建身份并保存到 {}", args.identity);
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
    println!("# 已连接 {}，等待服务器事件…", args.server);

    // 等待连接就绪，然后执行一次性动作
    client.wait_connected().await?;
    run_actions(&mut client, &args)?;

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
                    print_event(&event);
                }
            }
        }
    }

    let _ = client.disconnect();
    println!("# 已断开");
    Ok(())
}

/// 连接就绪后执行的一次性动作（全部为协议命令发送）
fn run_actions(client: &mut Client, args: &Args) -> Result<()> {
    for client_id in &args.watch {
        println!("# 发送 requeststreaminfo clid={client_id}");
        client.request_stream_info(*client_id)?;
    }

    if let Some(name) = &args.publish {
        println!("# 发送 setupstream name={name}（等待 notifystreamstarted）");
        client.setup_screen_share(name, false)?;
    }

    if let Some(stream_id) = &args.join {
        let source = args
            .source_clid
            .ok_or_else(|| anyhow::anyhow!("--join 需要同时指定 --source-clid"))?;
        println!("# 发送 joinstreamrequest id={stream_id} clid={source}");
        client.join_screen_share(stream_id, source)?;
    }

    Ok(())
}

/// 打印关注的事件（屏幕共享事件以 JSON 行输出）
fn print_event(event: &Event) {
    match event {
        Event::Connected { server_name, .. } => {
            println!("# 连接成功（服务器：{server_name}）");
        }
        Event::Disconnected { reason } => {
            println!("# 已断开：{reason}");
        }
        Event::ConnectionLost { reason } => {
            println!("# 连接丢失：{reason}");
        }
        Event::TextMessage {
            sender_name,
            message,
            ..
        } => {
            println!("# [消息] {sender_name}: {message}");
        }
        Event::ScreenShare(screen_event) => {
            let json = serde_json::to_string(screen_event).unwrap_or_default();
            println!("SCREEN_SHARE {json}");
        }
        _ => {}
    }
}
