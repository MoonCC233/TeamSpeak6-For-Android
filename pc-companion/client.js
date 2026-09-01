const WebSocket = require('ws');

function printUsage() {
  console.log('Usage: node client.js [options]\n\n' +
    '  --server ws://host:port   Signal server URL (default: ws://127.0.0.1:8765)\n' +
    '  --room roomId            TeamSpeak room ID / MSS room to join\n' +
    '  --uid clientUid          Client UID for display\n' +
    '  --name nickname          Display name\n' +
    '  --publish                Announce screen share in this room\n' +
    '  --watch publisherId      Request to watch a specific publisher\n' +
    '  --wait                   Print share notices while waiting\n' +
    '  --help                   Show this help\n');
}

function normalizeSignalUrl(raw) {
  const candidate = String(raw || '').trim();
  if (!candidate) return '';
  if (candidate.startsWith('ws://') || candidate.startsWith('wss://')) return candidate;
  if (candidate.startsWith('http://')) return `ws${candidate.slice(4)}`;
  if (candidate.startsWith('https://')) return `wss${candidate.slice(5)}`;
  return candidate;
}

function parseArgs(argv) {
  const args = {
    server: normalizeSignalUrl(process.env.MSS_SIGNALING_URL || 'ws://127.0.0.1:8765'),
    roomId: 'demo-room',
    clientUid: 'pc-demo',
    nickname: 'DesktopCompanion',
    publish: false,
    watch: null,
    waitForSignals: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--help') {
      printUsage();
      process.exit(0);
    }
    if (value === '--server') args.server = normalizeSignalUrl(argv[++index]);
    else if (value === '--room') args.roomId = argv[++index];
    else if (value === '--uid') args.clientUid = argv[++index];
    else if (value === '--name') args.nickname = argv[++index];
    else if (value === '--publish') args.publish = true;
    else if (value === '--watch') args.watch = argv[++index];
    else if (value === '--wait') args.waitForSignals = true;
  }

  return args;
}

function makeSdp(label = 'screen') {
  return `v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:${label}\r\na=sendrecv\r\n`;
}

function send(ws, type, payload = {}) {
  ws.send(JSON.stringify({ type, v: 1, ...payload }));
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const ws = new WebSocket(opts.server);
  let peerId = null;

  ws.on('open', () => {
    console.log(`Connected to ${opts.server}`);
    send(ws, 'hello', {
      roomId: opts.roomId,
      clientUid: opts.clientUid,
      tsClientId: 9999,
      nickname: opts.nickname,
    });
  });

  ws.on('message', (raw) => {
    const msg = JSON.parse(String(raw));
    console.log('[signal]', msg.type, JSON.stringify(msg));

    switch (msg.type) {
      case 'welcome': {
        peerId = msg.peerId;
        if (opts.publish) {
          send(ws, 'announce', {
            mode: 'p2p',
            hasAudio: true,
            video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 6000 },
          });
        }
        if (opts.watch) {
          setTimeout(() => {
            send(ws, 'watch', { publisherId: opts.watch });
          }, 250);
        }
        break;
      }
      case 'watch-request': {
        if (!opts.publish) break;
        send(ws, 'offer', {
          to: msg.from,
          sdp: makeSdp('screen'),
          streamId: 'screen',
        });
        break;
      }
      case 'offer': {
        send(ws, 'answer', {
          to: msg.from,
          sdp: makeSdp('screen'),
        });
        break;
      }
      case 'candidate': {
        if (msg.from && msg.candidate) {
          console.log('ICE candidate forwarded by server');
        }
        break;
      }
      case 'share-started': {
        if (opts.waitForSignals || opts.watch) {
          console.log(`Share started by ${msg.share.publisherId}`);
        }
        break;
      }
      case 'share-stopped': {
        console.log(`Share stopped by ${msg.publisherId}`);
        break;
      }
      case 'peer-joined': {
        console.log(`Peer joined: ${msg.peer.nickname}`);
        break;
      }
      case 'peer-left': {
        console.log(`Peer left: ${msg.peerId}`);
        break;
      }
      case 'error': {
        console.error('Server error:', msg.code, msg.message);
        break;
      }
      default:
        break;
    }
  });

  ws.on('close', () => console.log('Disconnected from signaling server'));
  ws.on('error', (error) => console.error('WebSocket error:', error.message));
}

main();
