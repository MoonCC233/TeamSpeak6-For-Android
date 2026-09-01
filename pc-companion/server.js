const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const root = __dirname;
const port = Number(process.env.PORT || 4173);
const rooms = new Map();
const peersBySocket = new Map();

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function roomFor(roomId) {
  if (!rooms.has(roomId)) rooms.set(roomId, new Map());
  return rooms.get(roomId);
}

function makePeerId() {
  return `p_${crypto.randomBytes(6).toString('hex')}`;
}

function send(ws, payload) {
  if (!ws || ws.readyState !== ws.OPEN) return;
  ws.send(JSON.stringify(payload));
}

function errorPayload(code, message, fatal = false) {
  return { type: 'error', v: 1, code, message, fatal };
}

function summarizePeer(peer) {
  return {
    peerId: peer.peerId,
    clientUid: peer.clientUid || '',
    tsClientId: peer.tsClientId || 0,
    nickname: peer.nickname || '',
  };
}

function summarizeShare(peer) {
  if (!peer.share) return null;
  return {
    publisherId: peer.peerId,
    nickname: peer.nickname || '',
    mode: peer.share.mode || 'p2p',
    hasAudio: !!peer.share.hasAudio,
    video: peer.share.video || null,
    audio: peer.share.audio || null,
  };
}

function notifyRoom(roomId, payload, exceptPeerId = null) {
  const room = roomFor(roomId);
  for (const peer of room.values()) {
    if (!peer || peer.peerId === exceptPeerId) continue;
    send(peer.socket, payload);
  }
}

function removePeer(peer) {
  if (!peer || !peer.roomId) return;
  const room = rooms.get(peer.roomId);
  if (!room) return;

  if (peer.share) {
    notifyRoom(peer.roomId, { type: 'share-stopped', v: 1, publisherId: peer.peerId }, peer.peerId);
  }

  room.delete(peer.peerId);
  peersBySocket.delete(peer.socket);

  if (room.size === 0) {
    rooms.delete(peer.roomId);
    return;
  }

  const peers = Array.from(room.values()).map(summarizePeer);
  for (const other of room.values()) {
    send(other.socket, {
      type: 'peer-left',
      v: 1,
      peerId: peer.peerId,
    });
    send(other.socket, {
      type: 'welcome',
      v: 1,
      peerId: other.peerId,
      roomId: peer.roomId,
      sfuAvailable: false,
      iceServers: [{ urls: ['stun:stun.l.google.com:19302'] }],
      peers,
      shares: Array.from(room.values()).filter((entry) => entry.share).map((entry) => summarizeShare(entry)).filter(Boolean),
    });
  }
}

function handleMessage(ws, raw) {
  let message;
  try {
    message = JSON.parse(raw);
  } catch (error) {
    send(ws, errorPayload('bad_request', 'Malformed JSON payload'));
    ws.close();
    return;
  }

  if (!message || typeof message.type !== 'string') {
    send(ws, errorPayload('bad_request', 'Missing message type'));
    ws.close();
    return;
  }

  if (typeof message.v !== 'number' || message.v !== 1) {
    send(ws, errorPayload('bad_version', 'Unsupported protocol version', true));
    ws.close();
    return;
  }

  const peer = peersBySocket.get(ws);
  if (message.type !== 'hello' && !peer) {
    send(ws, errorPayload('not_in_room', 'You must send hello before any other message', true));
    ws.close();
    return;
  }

  switch (message.type) {
    case 'hello': {
      if (peer) {
        send(ws, errorPayload('bad_request', 'Socket already belongs to a room'));
        ws.close();
        return;
      }

      const roomId = String(message.roomId || '');
      if (!roomId) {
        send(ws, errorPayload('bad_request', 'roomId is required'));
        ws.close();
        return;
      }

      const assignedPeerId = makePeerId();
      const room = roomFor(roomId);
      const entry = {
        socket: ws,
        peerId: assignedPeerId,
        roomId,
        clientUid: String(message.clientUid || ''),
        tsClientId: Number(message.tsClientId || 0),
        nickname: String(message.nickname || 'Guest'),
        share: null,
      };
      room.set(assignedPeerId, entry);
      peersBySocket.set(ws, entry);

      const peerList = Array.from(room.values()).map(summarizePeer);
      const shareList = Array.from(room.values()).filter((item) => item.share).map((item) => summarizeShare(item)).filter(Boolean);
      send(ws, {
        type: 'welcome',
        v: 1,
        peerId: assignedPeerId,
        roomId,
        sfuAvailable: false,
        iceServers: [{ urls: ['stun:stun.l.google.com:19302'] }],
        peers: peerList.filter((p) => p.peerId !== assignedPeerId),
        shares: shareList,
      });

      for (const other of room.values()) {
        if (other.peerId === assignedPeerId) continue;
        send(other.socket, {
          type: 'peer-joined',
          v: 1,
          peer: summarizePeer(entry),
        });
      }
      break;
    }

    case 'announce': {
      if (!peer.share || peer.share.mode !== message.mode || peer.share.video !== message.video) {
        peer.share = {
          mode: String(message.mode || 'p2p'),
          hasAudio: !!message.hasAudio,
          video: message.video || null,
          audio: message.audio || null,
        };
      }
      notifyRoom(peer.roomId, {
        type: 'share-started',
        v: 1,
        share: summarizeShare(peer),
      }, peer.peerId);
      break;
    }

    case 'unannounce': {
      if (peer.share) {
        notifyRoom(peer.roomId, {
          type: 'share-stopped',
          v: 1,
          publisherId: peer.peerId,
        }, peer.peerId);
      }
      peer.share = null;
      break;
    }

    case 'watch': {
      const publisher = roomFor(peer.roomId).get(message.publisherId);
      if (!publisher) {
        send(ws, errorPayload('no_such_peer', `Publisher ${message.publisherId} not found`));
        return;
      }
      send(publisher.socket, {
        type: 'watch-request',
        v: 1,
        from: peer.peerId,
        nickname: peer.nickname,
        clientUid: peer.clientUid,
      });
      break;
    }

    case 'unwatch': {
      const publisher = roomFor(peer.roomId).get(message.publisherId);
      if (!publisher) return;
      send(publisher.socket, {
        type: 'bye',
        v: 1,
        from: peer.peerId,
        reason: 'viewer stopped',
      });
      break;
    }

    case 'offer': {
      const target = roomFor(peer.roomId).get(message.to);
      if (!target) {
        send(ws, errorPayload('no_such_peer', `Target ${message.to} not found`));
        return;
      }
      send(target.socket, {
        type: 'offer',
        v: 1,
        from: peer.peerId,
        sdp: message.sdp,
        streamId: message.streamId || 'screen',
      });
      break;
    }

    case 'answer': {
      const target = roomFor(peer.roomId).get(message.to);
      if (!target) {
        send(ws, errorPayload('no_such_peer', `Target ${message.to} not found`));
        return;
      }
      send(target.socket, {
        type: 'answer',
        v: 1,
        from: peer.peerId,
        sdp: message.sdp,
      });
      break;
    }

    case 'candidate': {
      const target = roomFor(peer.roomId).get(message.to);
      if (!target) {
        send(ws, errorPayload('no_such_peer', `Target ${message.to} not found`));
        return;
      }
      send(target.socket, {
        type: 'candidate',
        v: 1,
        from: peer.peerId,
        candidate: String(message.candidate ?? ''),
        sdpMid: String(message.sdpMid || ''),
        sdpMLineIndex: Number(message.sdpMLineIndex || 0),
      });
      break;
    }

    case 'bye': {
      const target = roomFor(peer.roomId).get(message.to);
      if (!target) return;
      send(target.socket, {
        type: 'bye',
        v: 1,
        from: peer.peerId,
        reason: message.reason || '',
      });
      break;
    }

    case 'leave': {
      removePeer(peer);
      if (ws && ws.readyState === ws.OPEN) ws.close();
      break;
    }

    case 'ping': {
      send(ws, { type: 'pong', v: 1, nonce: Number(message.nonce || 0) });
      break;
    }

    default:
      break;
  }
}

function createSignalServer(httpServer) {
  const wss = new WebSocketServer({ server: httpServer });
  wss.on('connection', (ws) => {
    ws.on('message', (raw) => {
      handleMessage(ws, raw.toString());
    });
    ws.on('close', () => {
      const peer = peersBySocket.get(ws);
      if (peer) removePeer(peer);
    });
    ws.on('error', () => {
      const peer = peersBySocket.get(ws);
      if (peer) removePeer(peer);
    });
  });
  return wss;
}

const server = http.createServer((req, res) => {
  const urlPath = req.url === '/' ? '/index.html' : req.url.split('?')[0];
  const safePath = path.normalize(urlPath).replace(/^\.+[\\/]+/, '');
  const filePath = path.join(root, safePath);

  if (!filePath.startsWith(root)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Forbidden');
    return;
  }

  fs.readFile(filePath, (error, content) => {
    if (error) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not found');
      return;
    }

    const extension = path.extname(filePath).toLowerCase();
    res.writeHead(200, { 'Content-Type': mimeTypes[extension] || 'application/octet-stream' });
    res.end(content);
  });
});

createSignalServer(server);

server.listen(port, () => {
  console.log(`PC companion UI + MSS signaling server ready at http://127.0.0.1:${port}`);
  console.log(`WebSocket endpoint: ws://127.0.0.1:${port}`);
});
