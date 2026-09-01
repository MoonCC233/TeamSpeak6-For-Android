'use strict';

const { WebSocketServer } = require('ws');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8765);
const HEARTBEAT_MS = Number(process.env.MSS_HEARTBEAT_MS || 25_000);
const AUTH_TOKEN = String(process.env.MSS_AUTH_TOKEN || '');
const PROTOCOL_VERSION = 1;

const rooms = new Map();
const peersBySocket = new Map();

/**
 * Room ids are derived rather than assigned so a phone and a desktop that know
 * the same TeamSpeak location join the same room without a lookup service. All
 * three clients must derive it identically.
 */
function deriveRoomId(serverUid, channelId) {
  const input = `${serverUid || ''}|${channelId ?? 0}`;
  return crypto.createHash('sha256').update(input, 'utf8').digest('hex').slice(0, 32);
}

function resolveRoomId(message) {
  const explicit = String(message.roomId || '').trim();
  if (explicit) return explicit;
  const serverUid = String(message.serverUid || '').trim();
  const channelId = Number(message.channelId ?? 0);
  if (!serverUid || !Number.isFinite(channelId) || channelId <= 0) {
    return '';
  }
  return deriveRoomId(serverUid, channelId);
}

function roomFor(roomId) {
  if (!rooms.has(roomId)) {
    rooms.set(roomId, new Map());
  }
  return rooms.get(roomId);
}

/** Looks a peer up without creating an empty room as a side effect. */
function peerInRoom(roomId, peerId) {
  const room = rooms.get(roomId);
  if (!room) return null;
  return room.get(String(peerId || '')) || null;
}

function makePeerId() {
  return `p_${crypto.randomBytes(6).toString('hex')}`;
}

function send(ws, payload) {
  if (!ws || ws.readyState !== ws.OPEN) return;
  ws.send(JSON.stringify(payload));
}

function isSocketActive(ws) {
  return !!ws && ws.readyState === ws.OPEN;
}

function errorPayload(code, message, fatal = false) {
  return { type: 'error', v: PROTOCOL_VERSION, code, message, fatal };
}

/**
 * Compares the token in constant time: a plain `!==` leaks the expected value
 * one byte at a time to a caller who can measure the response.
 */
function tokenMatches(candidate) {
  if (!AUTH_TOKEN) return true;
  const expected = Buffer.from(AUTH_TOKEN, 'utf8');
  const actual = Buffer.from(String(candidate || ''), 'utf8');
  if (actual.length !== expected.length) return false;
  return crypto.timingSafeEqual(expected, actual);
}

/** Reads `?token=` so a client can authenticate without a protocol change. */
function tokenFromRequest(request) {
  const raw = request && request.url ? request.url : '';
  const query = raw.indexOf('?');
  if (query < 0) return '';
  return new URLSearchParams(raw.slice(query + 1)).get('token') || '';
}

function iceServerConfig() {
  const raw = String(process.env.MSS_ICE_SERVERS || '').trim();
  if (!raw) return [{ urls: ['stun:stun.l.google.com:19302'] }];
  return raw
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((urls) => ({ urls: [urls] }));
}

function notifyRoom(roomId, payload, exceptPeerId = null) {
  const room = rooms.get(roomId);
  if (!room) return;
  for (const peer of room.values()) {
    if (!peer || peer.peerId === exceptPeerId) continue;
    send(peer.socket, payload);
  }
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

function collectShares(room) {
  const shares = [];
  for (const peer of room.values()) {
    const share = summarizeShare(peer);
    if (share) shares.push(share);
  }
  return shares;
}

function logRoute(peer, type, details = '') {
  const peerLabel = peer ? `${peer.peerId}@${peer.roomId}` : 'unknown';
  const suffix = details ? ` ${details}` : '';
  console.log(`[mss] ${peerLabel} ${type}${suffix}`);
}

/** Drops [viewerId] from every publisher in the room that was serving it. */
function forgetViewer(roomId, viewerId) {
  const room = rooms.get(roomId);
  if (!room) return;
  for (const peer of room.values()) {
    peer.viewers.delete(viewerId);
  }
}

function removePeer(peer) {
  if (!peer || !peer.roomId) return;
  const room = rooms.get(peer.roomId);
  if (!room) return;

  if (peer.share) {
    notifyRoom(peer.roomId, {
      type: 'share-stopped',
      v: PROTOCOL_VERSION,
      publisherId: peer.peerId,
    }, peer.peerId);
  }

  room.delete(peer.peerId);
  peersBySocket.delete(peer.socket);
  forgetViewer(peer.roomId, peer.peerId);

  if (room.size === 0) {
    rooms.delete(peer.roomId);
    return;
  }

  // `peer-left` alone: replaying `welcome` would make the remaining clients
  // reset their share list and forget which streams they are already watching.
  notifyRoom(peer.roomId, {
    type: 'peer-left',
    v: PROTOCOL_VERSION,
    peerId: peer.peerId,
  });
}

/**
 * Decides whether [viewer] may watch [publisher].
 *
 * The publisher still has the final say for a `private` share — it is prompted
 * and answers with an offer or a `bye` — but the viewer limit and the allow list
 * are enforced here so a client cannot talk its way past them.
 */
function watchRejection(publisher, viewer) {
  const share = publisher.share;
  if (!share) {
    return errorPayload('not_sharing', `Peer ${publisher.peerId} is not sharing`);
  }

  const limit = Number(share.viewerLimit || 0);
  const alreadyAdmitted = publisher.viewers.has(viewer.peerId);
  if (limit > 0 && !alreadyAdmitted && publisher.viewers.size >= limit) {
    return errorPayload('viewer_limit', `Publisher ${publisher.peerId} reached its viewer limit`);
  }

  const allowed = share.allowedUids || [];
  if (allowed.length > 0 && !allowed.includes(viewer.clientUid)) {
    return errorPayload('not_allowed', 'The publisher did not share this screen with you');
  }

  return null;
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

  if (typeof message.v !== 'number' || message.v !== PROTOCOL_VERSION) {
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

  if (peer) peer.lastSeen = Date.now();

  logRoute(peer, message.type, `room=${peer ? peer.roomId : message.roomId || '(none)'}`);

  switch (message.type) {
    case 'hello': {
      if (peer) {
        send(ws, errorPayload('bad_request', 'Socket already belongs to a room'));
        ws.close();
        return;
      }

      if (!tokenMatches(message.token || ws.mssQueryToken)) {
        send(ws, errorPayload('unauthorized', 'Invalid or missing signaling token', true));
        ws.close();
        return;
      }

      const roomId = resolveRoomId(message);
      if (!roomId) {
        send(ws, errorPayload('bad_request', 'roomId or serverUid+channelId is required'));
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
        viewers: new Set(),
        lastSeen: Date.now(),
      };
      room.set(assignedPeerId, entry);
      peersBySocket.set(ws, entry);

      send(ws, {
        type: 'welcome',
        v: PROTOCOL_VERSION,
        peerId: assignedPeerId,
        roomId,
        heartbeatMs: HEARTBEAT_MS,
        sfuAvailable: false,
        iceServers: iceServerConfig(),
        peers: Array.from(room.values())
          .filter((other) => other.peerId !== assignedPeerId)
          .map(summarizePeer),
        shares: collectShares(room),
      });

      notifyRoom(roomId, {
        type: 'peer-joined',
        v: PROTOCOL_VERSION,
        peer: summarizePeer(entry),
      }, assignedPeerId);
      break;
    }

    case 'announce': {
      peer.share = {
        mode: String(message.mode || 'p2p'),
        privacy: String(message.privacy || 'public'),
        hasAudio: !!message.hasAudio,
        video: message.video || null,
        audio: message.audio || null,
        allowedUids: Array.isArray(message.allowedUids)
          ? message.allowedUids.map((uid) => String(uid))
          : [],
        viewerLimit: Number(message.viewerLimit || 0),
      };
      logRoute(peer, 'announce', `mode=${peer.share.mode} hasAudio=${!!peer.share.hasAudio} limit=${peer.share.viewerLimit}`);
      notifyRoom(peer.roomId, {
        type: 'share-started',
        v: PROTOCOL_VERSION,
        share: summarizeShare(peer),
      }, peer.peerId);
      break;
    }

    case 'unannounce': {
      if (peer.share) {
        notifyRoom(peer.roomId, {
          type: 'share-stopped',
          v: PROTOCOL_VERSION,
          publisherId: peer.peerId,
        }, peer.peerId);
      }
      peer.share = null;
      peer.viewers.clear();
      break;
    }

    case 'watch': {
      const publisher = peerInRoom(peer.roomId, message.publisherId);
      if (!publisher) {
        send(ws, errorPayload('no_such_peer', `Publisher ${message.publisherId} not found`));
        return;
      }

      const rejection = watchRejection(publisher, peer);
      if (rejection) {
        logRoute(peer, 'watch-rejected', `publisher=${publisher.peerId} code=${rejection.code}`);
        send(ws, rejection);
        return;
      }

      publisher.viewers.add(peer.peerId);
      logRoute(peer, 'watch', `publisher=${publisher.peerId} viewers=${publisher.viewers.size}`);
      send(publisher.socket, {
        type: 'watch-request',
        v: PROTOCOL_VERSION,
        from: peer.peerId,
        nickname: peer.nickname,
        clientUid: peer.clientUid,
      });
      break;
    }

    case 'unwatch': {
      const publisher = peerInRoom(peer.roomId, message.publisherId);
      if (!publisher) return;
      publisher.viewers.delete(peer.peerId);
      send(publisher.socket, {
        type: 'bye',
        v: PROTOCOL_VERSION,
        from: peer.peerId,
        reason: 'viewer stopped',
      });
      break;
    }

    case 'offer': {
      if (String(message.to) === 'sfu') {
        send(ws, errorPayload('sfu_unavailable', 'SFU mode is not enabled on this signaling server', false));
        return;
      }
      const target = peerInRoom(peer.roomId, message.to);
      if (!target) {
        send(ws, errorPayload('no_such_peer', `Target ${message.to} not found`));
        return;
      }
      logRoute(peer, 'offer', `to=${message.to} bytes=${String(message.sdp || '').length}`);
      send(target.socket, {
        type: 'offer',
        v: PROTOCOL_VERSION,
        from: peer.peerId,
        sdp: message.sdp,
        streamId: message.streamId || 'screen',
      });
      break;
    }

    case 'answer': {
      const target = peerInRoom(peer.roomId, message.to);
      if (!target) {
        send(ws, errorPayload('no_such_peer', `Target ${message.to} not found`));
        return;
      }
      logRoute(peer, 'answer', `to=${message.to} bytes=${String(message.sdp || '').length}`);
      send(target.socket, {
        type: 'answer',
        v: PROTOCOL_VERSION,
        from: peer.peerId,
        sdp: message.sdp,
      });
      break;
    }

    case 'candidate': {
      const target = peerInRoom(peer.roomId, message.to);
      if (!target) {
        send(ws, errorPayload('no_such_peer', `Target ${message.to} not found`));
        return;
      }
      logRoute(peer, 'candidate', `to=${message.to} mid=${String(message.sdpMid || '')}`);
      send(target.socket, {
        type: 'candidate',
        v: PROTOCOL_VERSION,
        from: peer.peerId,
        candidate: String(message.candidate ?? ''),
        sdpMid: String(message.sdpMid || ''),
        sdpMLineIndex: Number(message.sdpMLineIndex || 0),
      });
      break;
    }

    case 'bye': {
      const target = peerInRoom(peer.roomId, message.to);
      if (!target) return;
      peer.viewers.delete(target.peerId);
      target.viewers.delete(peer.peerId);
      send(target.socket, {
        type: 'bye',
        v: PROTOCOL_VERSION,
        from: peer.peerId,
        reason: message.reason || '',
      });
      break;
    }

    case 'leave': {
      removePeer(peer);
      if (isSocketActive(ws)) {
        ws.close();
      }
      break;
    }

    case 'ping': {
      send(ws, { type: 'pong', v: PROTOCOL_VERSION, nonce: Number(message.nonce || 0) });
      break;
    }

    case 'pong': {
      break;
    }

    default:
      break;
  }
}

/**
 * Sends a heartbeat and evicts peers that missed two rounds, so a socket that
 * dies without a close frame (mobile suspend, NAT timeout) stops appearing in
 * the room and stops holding a viewer slot.
 */
function sweep() {
  const staleBefore = Date.now() - HEARTBEAT_MS * 2;
  for (const room of Array.from(rooms.values())) {
    for (const peer of Array.from(room.values())) {
      if (!isSocketActive(peer.socket)) {
        removePeer(peer);
        continue;
      }
      if (peer.lastSeen < staleBefore) {
        logRoute(peer, 'evict', 'heartbeat timeout');
        removePeer(peer);
        closeQuietly(peer.socket);
        continue;
      }
      send(peer.socket, { type: 'ping', v: PROTOCOL_VERSION, nonce: Date.now() });
    }
  }
}

function closeQuietly(ws) {
  try {
    ws.close();
  } catch (error) {
    // already gone, which is the outcome we wanted
  }
}

/** Attaches the signaling protocol to an existing `ws` server. */
function attachSignaling(wss) {
  wss.on('connection', (ws, request) => {
    ws.mssQueryToken = tokenFromRequest(request);

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

  const heartbeat = setInterval(sweep, HEARTBEAT_MS);
  // The heartbeat must never be the reason the process stays alive: when the
  // signaling server is attached to an HTTP server, closing that server does not
  // emit `close` here, so a ref'd timer would hang the host process forever.
  heartbeat.unref();
  wss.on('close', () => clearInterval(heartbeat));
  return wss;
}

function createServer(port = PORT) {
  return attachSignaling(new WebSocketServer({ port }));
}

if (require.main === module) {
  createServer(PORT);
  console.log(`MSS signaling server listening on ws://0.0.0.0:${PORT}`);
  if (!AUTH_TOKEN) {
    console.warn('[mss] MSS_AUTH_TOKEN is unset: anyone who can reach this port can join any room.');
  }
}

module.exports = {
  attachSignaling,
  createServer,
  deriveRoomId,
  handleMessage,
  peersBySocket,
  removePeer,
  roomFor,
  rooms,
  summarizePeer,
  summarizeShare,
  HEARTBEAT_MS,
  PROTOCOL_VERSION,
};
