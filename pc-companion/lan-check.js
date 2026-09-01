const WebSocket = require('ws');

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
    roomId: process.env.MSS_ROOM_ID || 'demo-room',
    clientUid: process.env.MSS_CLIENT_UID || `lan-${Date.now()}`,
    nickname: process.env.MSS_NICKNAME || 'LanCheck',
    publish: false,
    watch: null,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const value = argv[i];
    if (value === '--help' || value === '-h') {
      printUsage();
      process.exit(0);
    }
    if (value === '--server') args.server = normalizeSignalUrl(argv[++i]);
    else if (value === '--room') args.roomId = argv[++i];
    else if (value === '--uid') args.clientUid = argv[++i];
    else if (value === '--name') args.nickname = argv[++i];
    else if (value === '--publish') args.publish = true;
    else if (value === '--watch') args.watch = argv[++i];
  }

  return args;
}

function printUsage() {
  console.log('Usage: node lan-check.js [options]\n\n' +
    '  --server http://host:port   Signal server URL; http/https auto-normalized\n' +
    '  --room roomId              TeamSpeak/MSS room to join\n' +
    '  --uid clientUid            Client ID for this check\n' +
    '  --name nickname            Display name\n' +
    '  --publish                  Send announce immediately\n' +
    '  --watch publisherId        Request to watch a known publisher\n' +
    '  --help                     Show help\n');
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
      tsClientId: 9000,
      nickname: opts.nickname,
    });
  });

  ws.on('message', (raw) => {
    const msg = JSON.parse(String(raw));
    console.log('[signal]', msg.type, JSON.stringify(msg));

    switch (msg.type) {
      case 'welcome': {
        peerId = msg.peerId;
        console.log(`Joined room ${msg.roomId} as ${peerId}`);
        console.log(`Peers in room: ${msg.peers.map((peer) => peer.peerId).join(', ') || 'none'}`);
        console.log(`Shares in room: ${msg.shares.map((share) => share.publisherId).join(', ') || 'none'}`);
        if (opts.publish) {
          send(ws, 'announce', {
            mode: 'p2p',
            hasAudio: true,
            video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 4000 },
          });
        }
        if (opts.watch) {
          setTimeout(() => send(ws, 'watch', { publisherId: opts.watch }), 250);
        }
        break;
      }
      case 'share-started': {
        console.log(`share-started from ${msg.share.publisherId}`);
        break;
      }
      case 'watch-request': {
        console.log(`watch-request from ${msg.from} (${msg.nickname})`);
        send(ws, 'offer', {
          to: msg.from,
          sdp: 'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=sendrecv\r\n',
          streamId: 'screen',
        });
        break;
      }
      case 'offer': {
        console.log(`incoming offer from ${msg.from}`);
        send(ws, 'answer', {
          to: msg.from,
          sdp: 'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=recvonly\r\n',
        });
        break;
      }
      case 'candidate': {
        console.log(`ice candidate from ${msg.from} mid=${msg.sdpMid || ''}`);
        break;
      }
      case 'peer-left': {
        console.log(`peer-left ${msg.peerId}`);
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
