const test = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { createServer } = require('./index.js');

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

function connect(port) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
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

test('hello returns welcome and notifies peers on join', async () => {
  const port = 9876;
  const server = createServer(port);

  try {
    const alpha = await joinRoom(port, 'room-test', 'alpha', 'Alpha');
    const beta = await joinRoom(port, 'room-test', 'beta', 'Beta');

    assert.equal(alpha.welcome.type, 'welcome');
    assert.equal(beta.welcome.type, 'welcome');
    assert.equal(alpha.welcome.roomId, 'room-test');
    assert.equal(beta.welcome.roomId, 'room-test');

    const peerJoined = await waitForMessage(alpha.ws, (payload) => payload.type === 'peer-joined');
    assert.equal(peerJoined.peer.nickname, 'Beta');

    beta.ws.close();
    alpha.ws.close();
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

test('leave closes the peer and emits peer-left to remaining room members', async () => {
  const port = 9878;
  const server = createServer(port);

  try {
    const alpha = await joinRoom(port, 'room-bye', 'alpha', 'Alpha');
    const beta = await joinRoom(port, 'room-bye', 'beta', 'Beta');

    alpha.ws.send(JSON.stringify({ type: 'leave', v: 1 }));

    const peerLeft = await waitForMessage(beta.ws, (payload) => payload.type === 'peer-left' && payload.peerId === alpha.welcome.peerId);
    assert.equal(peerLeft.peerId, alpha.welcome.peerId);

    alpha.ws.close();
    beta.ws.close();
    await new Promise((resolve) => setTimeout(resolve, 100));
  } finally {
    server.close();
  }
});
