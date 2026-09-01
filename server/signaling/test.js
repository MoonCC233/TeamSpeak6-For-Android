const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');
const WebSocket = require('ws');
const { createServer, deriveRoomId } = require('./index.js');

function waitForMessage(ws, predicate, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.removeListener('message', onMessage);
      reject(new Error('Timed out waiting for signaling message'));
    }, timeoutMs);

    const onMessage = (raw) => {
      try {
        const payload = JSON.parse(String(raw));
        if (predicate(payload)) {
          clearTimeout(timer);
          ws.removeListener('message', onMessage);
          resolve(payload);
        }
      } catch (error) {
        // ignore malformed frames until the expected one arrives
      }
    };

    ws.on('message', onMessage);
  });
}

function connect(port, query = '') {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${query}`);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

/** Waits for a spawned server to accept connections. */
async function waitForPort(port, attempts = 40) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const probe = await connect(port);
      probe.close();
      return;
    } catch (error) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }
  throw new Error(`server on port ${port} never came up`);
}

/** Resolves when nothing matching [predicate] arrives within [timeoutMs]. */
function assertNoMessage(ws, timeoutMs, predicate) {
  return new Promise((resolve, reject) => {
    const onMessage = (raw) => {
      try {
        const payload = JSON.parse(String(raw));
        if (!predicate(payload)) return;
        clearTimeout(timer);
        ws.removeListener('message', onMessage);
        reject(new Error(`unexpectedly received ${payload.type}`));
      } catch (error) {
        // malformed frames are not what this assertion is about
      }
    };

    const timer = setTimeout(() => {
      ws.removeListener('message', onMessage);
      resolve();
    }, timeoutMs);

    ws.on('message', onMessage);
  });
}

async function joinRoom(port, roomId, clientUid, nickname) {
  const ws = await connect(port);
  ws.send(JSON.stringify({
    type: 'hello',
    v: 1,
    roomId,
    clientUid,
    tsClientId: Math.floor(Math.random() * 10_000),
    nickname,
  }));

  const welcome = await waitForMessage(ws, (payload) => payload.type === 'welcome');
  return { ws, welcome };
}

test('room ids are derived deterministically from server uid and channel id', () => {
  const first = deriveRoomId('server-uid', 42);
  const second = deriveRoomId('server-uid', 42);
  const differentChannel = deriveRoomId('server-uid', 43);
  const differentServer = deriveRoomId('server-uid-2', 42);

  assert.equal(first, second);
  assert.notEqual(first, differentChannel);
  assert.notEqual(first, differentServer);
  assert.equal(first.length, 32);
  assert.match(first, /^[0-9a-f]+$/);
});

test('hello accepts roomId or serverUid+channelId and derives the same room', async () => {
  const port = 9876;
  const server = createServer(port);

  try {
    const serverUid = 'server-uid';
    const channelId = 42;
    const expectedRoomId = deriveRoomId(serverUid, channelId);

    const alpha = await connect(port);
    alpha.send(JSON.stringify({
      type: 'hello',
      v: 1,
      serverUid,
      channelId,
      clientUid: 'alpha',
      tsClientId: 1,
      nickname: 'Alpha',
    }));
    const alphaWelcome = await waitForMessage(alpha, (payload) => payload.type === 'welcome');
    assert.equal(alphaWelcome.roomId, expectedRoomId);

    const beta = await connect(port);
    beta.send(JSON.stringify({
      type: 'hello',
      v: 1,
      roomId: expectedRoomId,
      clientUid: 'beta',
      tsClientId: 2,
      nickname: 'Beta',
    }));
    const betaWelcome = await waitForMessage(beta, (payload) => payload.type === 'welcome');
    assert.equal(betaWelcome.roomId, expectedRoomId);

    const peerJoined = await waitForMessage(alpha, (payload) => payload.type === 'peer-joined');
    assert.equal(peerJoined.peer.nickname, 'Beta');

    beta.close();
    alpha.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('announce/watch/offer flow routes correctly within a room', async () => {
  const port = 9877;
  const server = createServer(port);

  try {
    const publisher = await joinRoom(port, 'room-share', 'pub', 'Publisher');
    const viewer = await joinRoom(port, 'room-share', 'view', 'Viewer');

    const shareStarted = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('share announce did not arrive')), 4000);
      const onMessage = (raw) => {
        try {
          const payload = JSON.parse(String(raw));
          if (payload.type === 'share-started') {
            clearTimeout(timer);
            viewer.ws.removeListener('message', onMessage);
            resolve(payload);
          }
        } catch (_) {
          // ignore
        }
      };
      viewer.ws.on('message', onMessage);
      publisher.ws.send(JSON.stringify({
        type: 'announce',
        v: 1,
        mode: 'p2p',
        hasAudio: true,
        video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 4000 },
      }));
    });

    assert.equal(shareStarted.share.publisherId, publisher.welcome.peerId);

    viewer.ws.send(JSON.stringify({
      type: 'watch',
      v: 1,
      publisherId: publisher.welcome.peerId,
    }));

    const watchRequest = await waitForMessage(publisher.ws, (payload) => payload.type === 'watch-request');
    assert.equal(watchRequest.from, viewer.welcome.peerId);

    publisher.ws.send(JSON.stringify({
      type: 'offer',
      v: 1,
      to: viewer.welcome.peerId,
      sdp: 'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=sendrecv\r\n',
      streamId: 'screen',
    }));

    const offerReceived = await waitForMessage(viewer.ws, (payload) => payload.type === 'offer');
    assert.equal(offerReceived.from, publisher.welcome.peerId);

    viewer.ws.send(JSON.stringify({
      type: 'candidate',
      v: 1,
      to: publisher.welcome.peerId,
      candidate: 'candidate:1 1 UDP 2130706431 192.168.1.10 52000 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0,
    }));

    const candidateReceived = await waitForMessage(publisher.ws, (payload) => payload.type === 'candidate');
    assert.equal(candidateReceived.from, viewer.welcome.peerId);

    viewer.ws.send(JSON.stringify({
      type: 'candidate',
      v: 1,
      to: publisher.welcome.peerId,
      candidate: '',
      sdpMid: '0',
      sdpMLineIndex: 0,
    }));

    const emptyCandidate = await waitForMessage(publisher.ws, (payload) => payload.type === 'candidate' && payload.candidate === '');
    assert.equal(emptyCandidate.from, viewer.welcome.peerId);

    viewer.ws.close();
    publisher.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('ping/pong heartbeat keeps the signaling socket alive', async () => {
  const port = 9878;
  const server = createServer(port);

  try {
    const peer = await joinRoom(port, 'room-keepalive', 'keepalive', 'KeepAlive');
    assert.equal(peer.welcome.heartbeatMs > 0, true);

    peer.ws.send(JSON.stringify({ type: 'ping', v: 1, nonce: 42 }));
    const pong = await waitForMessage(peer.ws, (payload) => payload.type === 'pong' && payload.nonce === 42);
    assert.equal(pong.type, 'pong');
    assert.equal(pong.nonce, 42);

    peer.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('leave closes the peer and emits peer-left to remaining room members', async () => {
  const port = 9879;
  const server = createServer(port);

  try {
    const alpha = await joinRoom(port, 'room-bye', 'alpha', 'Alpha');
    const beta = await joinRoom(port, 'room-bye', 'beta', 'Beta');

    alpha.ws.send(JSON.stringify({ type: 'leave', v: 1 }));

    const peerLeft = await waitForMessage(beta.ws, (payload) => payload.type === 'peer-left' && payload.peerId === alpha.welcome.peerId);
    assert.equal(peerLeft.peerId, alpha.welcome.peerId);

    // A replayed `welcome` would make beta reset its share list and forget the
    // streams it is already watching.
    await assertNoMessage(beta.ws, 300, (payload) => payload.type === 'welcome');

    alpha.ws.close();
    beta.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('re-announcing replaces the advertised share parameters', async () => {
  const port = 9880;
  const server = createServer(port);

  try {
    const publisher = await joinRoom(port, 'room-reannounce', 'pub', 'Publisher');
    const viewer = await joinRoom(port, 'room-reannounce', 'view', 'Viewer');

    publisher.ws.send(JSON.stringify({
      type: 'announce',
      v: 1,
      mode: 'p2p',
      hasAudio: true,
      video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 4000 },
    }));
    await waitForMessage(viewer.ws, (payload) => payload.type === 'share-started');

    publisher.ws.send(JSON.stringify({
      type: 'announce',
      v: 1,
      mode: 'p2p',
      hasAudio: false,
      video: { width: 1280, height: 720, fps: 24, bitrateKbps: 1500 },
    }));

    const updated = await waitForMessage(
      viewer.ws,
      (payload) => payload.type === 'share-started' && payload.share.video.height === 720,
    );
    assert.equal(updated.share.hasAudio, false);
    assert.equal(updated.share.video.bitrateKbps, 1500);

    // A late joiner must see the same parameters rather than the first announce.
    const latecomer = await joinRoom(port, 'room-reannounce', 'late', 'Latecomer');
    assert.equal(latecomer.welcome.shares.length, 1);
    assert.equal(latecomer.welcome.shares[0].video.height, 720);
    assert.equal(latecomer.welcome.shares[0].hasAudio, false);

    latecomer.ws.close();
    viewer.ws.close();
    publisher.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('viewer limit is enforced by the server, not just the publisher', async () => {
  const port = 9881;
  const server = createServer(port);

  try {
    const publisher = await joinRoom(port, 'room-limit', 'pub', 'Publisher');
    const first = await joinRoom(port, 'room-limit', 'v1', 'Viewer1');
    const second = await joinRoom(port, 'room-limit', 'v2', 'Viewer2');

    publisher.ws.send(JSON.stringify({
      type: 'announce',
      v: 1,
      mode: 'p2p',
      hasAudio: false,
      video: { width: 1280, height: 720, fps: 30, bitrateKbps: 2000 },
      viewerLimit: 1,
    }));
    await waitForMessage(first.ws, (payload) => payload.type === 'share-started');

    first.ws.send(JSON.stringify({ type: 'watch', v: 1, publisherId: publisher.welcome.peerId }));
    await waitForMessage(publisher.ws, (payload) => payload.type === 'watch-request');

    second.ws.send(JSON.stringify({ type: 'watch', v: 1, publisherId: publisher.welcome.peerId }));
    const rejected = await waitForMessage(second.ws, (payload) => payload.type === 'error');
    assert.equal(rejected.code, 'viewer_limit');
    assert.equal(rejected.fatal, false);

    // Freeing the slot must let the second viewer in.
    first.ws.send(JSON.stringify({ type: 'unwatch', v: 1, publisherId: publisher.welcome.peerId }));
    await waitForMessage(publisher.ws, (payload) => payload.type === 'bye');

    second.ws.send(JSON.stringify({ type: 'watch', v: 1, publisherId: publisher.welcome.peerId }));
    const admitted = await waitForMessage(publisher.ws, (payload) => payload.type === 'watch-request');
    assert.equal(admitted.from, second.welcome.peerId);

    second.ws.close();
    first.ws.close();
    publisher.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('an allow list keeps other clients from reaching the publisher', async () => {
  const port = 9882;
  const server = createServer(port);

  try {
    const publisher = await joinRoom(port, 'room-allow', 'pub', 'Publisher');
    const invited = await joinRoom(port, 'room-allow', 'invited-uid', 'Invited');
    const stranger = await joinRoom(port, 'room-allow', 'stranger-uid', 'Stranger');

    publisher.ws.send(JSON.stringify({
      type: 'announce',
      v: 1,
      mode: 'p2p',
      privacy: 'contacts',
      hasAudio: false,
      video: { width: 1280, height: 720, fps: 30, bitrateKbps: 2000 },
      allowedUids: ['invited-uid'],
    }));
    await waitForMessage(invited.ws, (payload) => payload.type === 'share-started');

    stranger.ws.send(JSON.stringify({ type: 'watch', v: 1, publisherId: publisher.welcome.peerId }));
    const rejected = await waitForMessage(stranger.ws, (payload) => payload.type === 'error');
    assert.equal(rejected.code, 'not_allowed');

    invited.ws.send(JSON.stringify({ type: 'watch', v: 1, publisherId: publisher.welcome.peerId }));
    const request = await waitForMessage(publisher.ws, (payload) => payload.type === 'watch-request');
    assert.equal(request.clientUid, 'invited-uid');

    stranger.ws.close();
    invited.ws.close();
    publisher.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('watching a peer that is not sharing is rejected', async () => {
  const port = 9883;
  const server = createServer(port);

  try {
    const idle = await joinRoom(port, 'room-idle', 'idle', 'Idle');
    const viewer = await joinRoom(port, 'room-idle', 'view', 'Viewer');

    viewer.ws.send(JSON.stringify({ type: 'watch', v: 1, publisherId: idle.welcome.peerId }));
    const rejected = await waitForMessage(viewer.ws, (payload) => payload.type === 'error');
    assert.equal(rejected.code, 'not_sharing');

    viewer.ws.close();
    idle.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});

test('MSS_AUTH_TOKEN gates hello, via header-free query string or payload', async () => {
  const port = 9884;
  const child = spawn(process.execPath, [path.join(__dirname, 'index.js')], {
    env: { ...process.env, PORT: String(port), MSS_AUTH_TOKEN: 'secret-token' },
    stdio: ['ignore', 'ignore', 'ignore'],
  });

  try {
    await waitForPort(port);

    const wrong = await connect(port);
    wrong.send(JSON.stringify({
      type: 'hello',
      v: 1,
      roomId: 'room-auth',
      clientUid: 'a',
      tsClientId: 1,
      nickname: 'A',
      token: 'nope',
    }));
    const denied = await waitForMessage(wrong, (payload) => payload.type === 'error');
    assert.equal(denied.code, 'unauthorized');
    assert.equal(denied.fatal, true);
    wrong.close();

    const inPayload = await connect(port);
    inPayload.send(JSON.stringify({
      type: 'hello',
      v: 1,
      roomId: 'room-auth',
      clientUid: 'b',
      tsClientId: 2,
      nickname: 'B',
      token: 'secret-token',
    }));
    const welcome = await waitForMessage(inPayload, (payload) => payload.type === 'welcome');
    assert.equal(welcome.roomId, 'room-auth');
    inPayload.close();

    const inQuery = await connect(port, '?token=secret-token');
    inQuery.send(JSON.stringify({
      type: 'hello',
      v: 1,
      roomId: 'room-auth',
      clientUid: 'c',
      tsClientId: 3,
      nickname: 'C',
    }));
    const queryWelcome = await waitForMessage(inQuery, (payload) => payload.type === 'welcome');
    assert.equal(queryWelcome.roomId, 'room-auth');
    inQuery.close();
  } finally {
    child.kill();
  }
});
