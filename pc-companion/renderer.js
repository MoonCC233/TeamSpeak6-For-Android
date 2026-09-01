const serverInput = document.getElementById('serverInput');
const roomInput = document.getElementById('roomInput');
const uidInput = document.getElementById('uidInput');
const nameInput = document.getElementById('nameInput');
const connectBtn = document.getElementById('connectBtn');
const publishBtn = document.getElementById('publishBtn');
const watchBtn = document.getElementById('watchBtn');
const stopBtn = document.getElementById('stopBtn');
const publisherList = document.getElementById('publisherList');
const logOutput = document.getElementById('logOutput');
const connectionState = document.getElementById('connectionState');
const remoteVideo = document.getElementById('remoteVideo');
const emptyState = document.getElementById('emptyState');

let socket = null;
let peerId = null;
let peerConnections = new Map();
let currentPublisher = null;
let localStream = null;
let isConnected = false;
const pendingAnswers = new Map();

const log = (text) => {
  const stamp = new Date().toLocaleTimeString();
  logOutput.textContent = `[${stamp}] ${text}\n` + logOutput.textContent;
};

function updateConnectionLabel(label) {
  connectionState.textContent = label;
}

function setRemoteVideo(stream) {
  if (!stream) {
    emptyState.style.display = 'grid';
    remoteVideo.srcObject = null;
    return;
  }
  emptyState.style.display = 'none';
  remoteVideo.srcObject = stream;
}

function send(type, payload = {}) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify({ type, v: 1, ...payload }));
}

function createPeerConnection(targetPeerId) {
  const pc = new RTCPeerConnection({
    iceServers: [{ urls: ['stun:stun.l.google.com:19302'] }],
  });

  pc.onicecandidate = (event) => {
    if (event.candidate) {
      send('candidate', {
        to: targetPeerId,
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid || '',
        sdpMLineIndex: event.candidate.sdpMLineIndex || 0,
      });
    }
  };

  pc.ontrack = (event) => {
    const stream = event.streams[0] || new MediaStream();
    if (!event.streams[0]) {
      stream.addTrack(event.track);
    }
    setRemoteVideo(stream);
  };

  pc.onconnectionstatechange = () => {
    log(`Peer connection with ${targetPeerId} -> ${pc.connectionState}`);
  };

  peerConnections.set(targetPeerId, pc);
  return pc;
}

async function acquireDisplayStream() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
    throw new Error('This browser does not support getDisplayMedia().');
  }

  localStream = await navigator.mediaDevices.getDisplayMedia({
    video: { frameRate: 30, width: { ideal: 1920 }, height: { ideal: 1080 } },
    audio: true,
  });

  localStream.getVideoTracks().forEach((track) => {
    track.addEventListener('ended', () => {
      log('Screen capture ended');
      send('unannounce');
      localStream = null;
    });
  });

  return localStream;
}

async function publishShare() {
  try {
    const stream = await acquireDisplayStream();
    const pc = new RTCPeerConnection({
      iceServers: [{ urls: ['stun:stun.l.google.com:19302'] }],
    });

    pc.onicecandidate = (event) => {
      if (event.candidate && currentPublisher) {
        send('candidate', {
          to: currentPublisher,
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid || '',
          sdpMLineIndex: event.candidate.sdpMLineIndex || 0,
        });
      }
    };

    stream.getTracks().forEach((track) => pc.addTrack(track, stream));
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    pendingAnswers.set('publisher-local', pc);
    send('announce', {
      mode: 'p2p',
      hasAudio: true,
      video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 6000 },
    });
    send('offer', {
      to: currentPublisher,
      sdp: offer.sdp,
      streamId: 'screen',
    });
  } catch (error) {
    log(`Publish failed: ${error.message}`);
  }
}

async function watchPublisher(publisherId) {
  if (!publisherId) {
    log('Select a publisher from the list first.');
    return;
  }

  currentPublisher = publisherId;
  send('watch', { publisherId });
}

function renderPublishers(peers) {
  publisherList.innerHTML = '';
  if (!peers || peers.length === 0) {
    const item = document.createElement('li');
    item.textContent = 'No publishers';
    publisherList.appendChild(item);
    return;
  }

  peers.forEach((peer) => {
    const item = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = peer.nickname || peer.peerId;
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.peerId = peer.peerId;
    button.textContent = 'Watch';
    button.className = 'secondary';
    button.onclick = () => watchPublisher(peer.peerId);
    item.appendChild(label);
    item.appendChild(button);
    publisherList.appendChild(item);
  });
}

function connect() {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.close();
    return;
  }

  const url = serverInput.value.trim();
  const roomId = roomInput.value.trim();
  const clientUid = uidInput.value.trim();
  const nickname = nameInput.value.trim();

  socket = new WebSocket(url);
  updateConnectionLabel('Connecting…');

  socket.addEventListener('open', () => {
    isConnected = true;
    updateConnectionLabel('Connected');
    send('hello', {
      roomId,
      clientUid: clientUid || `pc-${Date.now()}`,
      tsClientId: 9999,
      nickname: nickname || 'PC Companion',
    });
    log(`Connected to ${url}`);
  });

  socket.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    log(`Received ${msg.type}`);

    switch (msg.type) {
      case 'welcome': {
        peerId = msg.peerId;
        renderPublishers(msg.peers || []);
        if (msg.shares && msg.shares.length) {
          renderPublishers(msg.shares.map((share) => ({ peerId: share.publisherId, nickname: share.nickname })));
        }
        updateConnectionLabel(`Connected (${msg.roomId})`);
        break;
      }
      case 'peer-joined': {
        renderPublishers([...(Array.from(publisherList.children).length ? [] : [])]);
        log(`Peer joined ${msg.peer.nickname}`);
        break;
      }
      case 'peer-left': {
        log(`Peer left ${msg.peerId}`);
        break;
      }
      case 'share-started': {
        const share = msg.share;
        log(`Share started by ${share.publisherId}`);
        renderPublishers([{ peerId: share.publisherId, nickname: share.nickname }]);
        break;
      }
      case 'share-stopped': {
        log(`Share stopped by ${msg.publisherId}`);
        break;
      }
      case 'watch-request': {
        log(`Watch request from ${msg.from}`);
        break;
      }
      case 'offer': {
        const target = msg.from;
        const pc = createPeerConnection(target);
        pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: msg.sdp }));
        pc.createAnswer().then((answer) => pc.setLocalDescription(answer)).then(() => {
          send('answer', { to: target, sdp: pc.localDescription.sdp });
        });
        break;
      }
      case 'answer': {
        const pc = peerConnections.get(msg.from);
        if (pc) {
          pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.sdp }));
        }
        break;
      }
      case 'candidate': {
        const pc = peerConnections.get(msg.from);
        if (pc && msg.candidate) {
          pc.addIceCandidate(new RTCIceCandidate({
            candidate: msg.candidate,
            sdpMid: msg.sdpMid || '',
            sdpMLineIndex: msg.sdpMLineIndex || 0,
          }));
        }
        break;
      }
      case 'error': {
        log(`Server error: ${msg.code}: ${msg.message}`);
        break;
      }
      default:
        break;
    }
  });

  socket.addEventListener('close', () => {
    isConnected = false;
    updateConnectionLabel('Disconnected');
    log('Disconnected from signaling server');
  });

  socket.addEventListener('error', () => {
    log('Signal socket error');
  });
}

connectBtn.addEventListener('click', connect);
publishBtn.addEventListener('click', async () => {
  if (!isConnected) {
    log('Connect to the signaling server first.');
    return;
  }
  await publishShare();
});
watchBtn.addEventListener('click', () => {
  const selected = document.querySelector('#publisherList button')?.dataset?.peerId || currentPublisher;
  if (selected) watchPublisher(selected);
});
stopBtn.addEventListener('click', () => {
  if (localStream) {
    localStream.getTracks().forEach((track) => track.stop());
    localStream = null;
  }
  send('unannounce');
  setRemoteVideo(null);
});

log('Ready');
