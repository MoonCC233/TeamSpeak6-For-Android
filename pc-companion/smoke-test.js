const WebSocket = require('ws');
const { createServer } = require('./server.js');

async function waitForMessage(ws, predicate, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.removeListener('message', onMessage);
      reject(new Error('Timed out waiting for message'));
    }, timeoutMs);

    const onMessage = (raw) => {
      try {
        const payload = JSON.parse(String(raw));
        if (predicate(payload)) {
          clearTimeout(timer);
          ws.removeListener('message', onMessage);
          resolve(payload);
        }
      } catch (_) {
        // ignore malformed payloads until the expected one arrives
      }
    };

    ws.on('message', onMessage);
  });
}

function connect(serverUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(serverUrl);
    const timer = setTimeout(() => {
      ws.removeAllListeners();
      reject(new Error(`timed out connecting to ${serverUrl}`));
    }, 5000);

    ws.once('open', () => {
      clearTimeout(timer);
      resolve(ws);
    });
    ws.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

async function joinRoom(ws, roomId, clientUid, nickname) {
  ws.send(JSON.stringify({ type: 'hello', v: 1, roomId, clientUid, tsClientId: 1, nickname }));
  return waitForMessage(ws, (payload) => payload.type === 'welcome');
}

async function assertNoMessage(ws, timeoutMs, description) {
  return new Promise((resolve, reject) => {
    const onMessage = (raw) => {
      try {
        const payload = JSON.parse(String(raw));
        reject(new Error(`${description} unexpectedly received ${payload.type}`));
      } catch {
        reject(new Error(`${description} received malformed data`));
      }
    };

    const timer = setTimeout(() => {
      ws.removeListener('message', onMessage);
      resolve();
    }, timeoutMs);

    ws.on('message', onMessage);
  });
}

let publisher;
let viewer;
let outsider;

async function main() {
  const { server } = await createServer(4174);

  try {
    publisher = await connect('ws://127.0.0.1:4174');
    viewer = await connect('ws://127.0.0.1:4174');

    const publisherWelcome = await joinRoom(publisher, 'smoke-room', 'phone-a', 'PhoneA');
    const viewerWelcome = await joinRoom(viewer, 'smoke-room', 'pc-b', 'PCB');

    if (!publisherWelcome.peerId || !viewerWelcome.peerId) {
      throw new Error('welcome payload missing peer ids');
    }

    publisher.send(JSON.stringify({
      type: 'announce',
      v: 1,
      mode: 'p2p',
      hasAudio: true,
      video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 4000 },
    }));

    const shareStarted = await waitForMessage(viewer, (payload) => payload.type === 'share-started');
    if (shareStarted.share.publisherId !== publisherWelcome.peerId) {
      throw new Error('share-started used the wrong publisher');
    }

    viewer.send(JSON.stringify({
      type: 'watch',
      v: 1,
      publisherId: publisherWelcome.peerId,
    }));

    const watchRequest = await waitForMessage(publisher, (payload) => payload.type === 'watch-request');
    if (watchRequest.from !== viewerWelcome.peerId) {
      throw new Error('watch-request came from the wrong viewer');
    }

    publisher.send(JSON.stringify({
      type: 'offer',
      v: 1,
      to: viewerWelcome.peerId,
      sdp: 'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=sendrecv\r\n',
    }));

    const offerReceived = await waitForMessage(viewer, (payload) => payload.type === 'offer');
    if (offerReceived.from !== publisherWelcome.peerId) {
      throw new Error('offer came from the wrong publisher');
    }

    viewer.send(JSON.stringify({
      type: 'answer',
      v: 1,
      to: publisherWelcome.peerId,
      sdp: 'v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=recvonly\r\n',
    }));

    const answerReceived = await waitForMessage(publisher, (payload) => payload.type === 'answer');
    if (answerReceived.from !== viewerWelcome.peerId) {
      throw new Error('answer came from the wrong viewer');
    }

    publisher.send(JSON.stringify({
      type: 'candidate',
      v: 1,
      to: viewerWelcome.peerId,
      candidate: 'candidate:1 1 UDP 2130706431 192.168.1.10 52000 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0,
    }));

    const candidateReceived = await waitForMessage(viewer, (payload) => payload.type === 'candidate');
    if (candidateReceived.from !== publisherWelcome.peerId) {
      throw new Error('candidate came from the wrong publisher');
    }

    outsider = await connect('ws://127.0.0.1:4174');
    await joinRoom(outsider, 'isolated-room', 'pc-c', 'PCOutsider');

    publisher.send(JSON.stringify({
      type: 'unannounce',
      v: 1,
    }));
    publisher.send(JSON.stringify({
      type: 'announce',
      v: 1,
      mode: 'p2p',
      hasAudio: false,
      video: { width: 1280, height: 720, fps: 24, bitrateKbps: 2000 },
    }));

    const outsiderLeak = await assertNoMessage(outsider, 500, 'cross-room share leakage');
    if (outsiderLeak) {
      throw new Error('outsider should not receive room-local share notifications');
    }

    console.log('companion smoke test passed');
  } finally {
    outsider?.close();
    publisher?.close();
    viewer?.close();
    server.close();
  }
}

main().catch((error) => {
  console.error('companion smoke test failed:', error.message);
  if (publisher) publisher.close();
  if (viewer) viewer.close();
  process.exit(1);
});
