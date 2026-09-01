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

function normalizeSignalUrl(raw) {
  const candidate = String(raw || '').trim();
  if (!candidate) return '';
  if (candidate.startsWith('ws://') || candidate.startsWith('wss://')) return candidate;
  if (candidate.startsWith('http://')) return `ws${candidate.slice(4)}`;
  if (candidate.startsWith('https://')) return `wss${candidate.slice(5)}`;
  return candidate;
}

let socket = null;
let peerId = null;
let peerConnections = new Map();
let currentPublisher = null;
let selectedPublisherId = null;
let localStream = null;
let isConnected = false;
let knownPublishers = new Map();
const pendingAnswers = new Map();

function getPeerConnectionFor(targetPeerId) {
  if (!targetPeerId) return null;
  if (!peerConnections.has(targetPeerId)) {
    peerConnections.set(targetPeerId, createPeerConnection(targetPeerId));
  }
  return peerConnections.get(targetPeerId);
}

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
    if (targetPeerId) {
      setSelectedPublisher(targetPeerId);
    }
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
    localStream = stream;
    send('announce', {
      mode: 'p2p',
      hasAudio: true,
      video: { width: 1920, height: 1080, fps: 30, bitrateKbps: 6000 },
    });
    log('Local screen share announced to the room. Waiting for watcher requests.');
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
  selectedPublisherId = publisherId;
  setSelectedPublisher(publisherId);
  send('watch', { publisherId });
}

function setSelectedPublisher(peerId) {
  selectedPublisherId = peerId || null;
  currentPublisher = selectedPublisherId;
  Array.from(publisherList.querySelectorAll('button')).forEach((button) => {
    const isSelected = button.dataset.peerId === selectedPublisherId;
    button.classList.toggle('is-selected', isSelected);
    button.textContent = isSelected ? 'Watching' : 'Watch';
  });
}

function syncPublisherList(peers = []) {
  peers.forEach((peer) => {
    if (!peer || !peer.peerId) return;
    knownPublishers.set(peer.peerId, {
      peerId: peer.peerId,
      nickname: peer.nickname || peer.peerId,
    });
  });

  const entries = [...knownPublishers.values()];
  publisherList.innerHTML = '';

  if (!entries.length) {
    const item = document.createElement('li');
    item.textContent = 'No publishers';
    publisherList.appendChild(item);
    if (!selectedPublisherId) {
      currentPublisher = null;
    }
    return;
  }

  entries.forEach((peer) => {
    const item = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = peer.nickname || peer.peerId;
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.peerId = peer.peerId;
    button.className = 'secondary';
    button.textContent = selectedPublisherId === peer.peerId ? 'Watching' : 'Watch';
    button.classList.toggle('is-selected', selectedPublisherId === peer.peerId);
    button.onclick = () => {
      setSelectedPublisher(peer.peerId);
      watchPublisher(peer.peerId);
    };

    item.appendChild(label);
    item.appendChild(button);
    publisherList.appendChild(item);
  });

  if (!selectedPublisherId) {
    setSelectedPublisher(entries[0].peerId);
  }
}

function renderPublishers(peers) {
  syncPublisherList(peers || []);
}

function connect() {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.close();
    return;
  }

  const url = normalizeSignalUrl(serverInput.value.trim());
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
        knownPublishers.clear();
        renderPublishers([
          ...(msg.peers || []),
          ...(msg.shares || []).map((share) => ({ peerId: share.publisherId, nickname: share.nickname })),
        ]);
        updateConnectionLabel(`Connected (${msg.roomId})`);
        break;
      }
      case 'peer-joined': {
        if (msg.peer) {
          syncPublisherList([...knownPublishers.values(), { peerId: msg.peer.peerId, nickname: msg.peer.nickname || msg.peer.peerId }]);
        }
        log(`Peer joined ${msg.peer?.nickname || msg.peer?.peerId || 'unknown'}`);
        break;
      }
      case 'peer-left': {
        if (msg.peerId) {
          knownPublishers.delete(msg.peerId);
          if (selectedPublisherId === msg.peerId) {
            setSelectedPublisher(null);
            setRemoteVideo(null);
          }
        }
        renderPublishers([...knownPublishers.values()]);
        log(`Peer left ${msg.peerId}`);
        break;
      }
      case 'share-started': {
        const share = msg.share;
        if (share) {
          knownPublishers.set(share.publisherId, { peerId: share.publisherId, nickname: share.nickname || share.publisherId });
          if (!selectedPublisherId) {
            setSelectedPublisher(share.publisherId);
          }
          renderPublishers([...knownPublishers.values()]);
        }
        log(`Share started by ${share?.publisherId || 'unknown'}`);
        break;
      }
      case 'share-stopped': {
        const publisherId = msg.publisherId;
        if (publisherId) {
          knownPublishers.delete(publisherId);
          if (selectedPublisherId === publisherId) {
            setSelectedPublisher(null);
            setRemoteVideo(null);
          }
          renderPublishers([...knownPublishers.values()]);
        }
        log(`Share stopped by ${publisherId || 'unknown'}`);
        break;
      }
      case 'watch-request': {
        if (!localStream) {
          log(`Ignored watch request from ${msg.from}: no local screen stream is active.`);
          break;
        }

        const target = msg.from;
        const pc = getPeerConnectionFor(target);
        if (!pc) {
          log(`Creating publisher-side peer for ${target}`);
        }
        localStream.getTracks().forEach((track) => {
          if (!pc.getSenders().some((sender) => sender.track === track)) {
            pc.addTrack(track, localStream);
          }
        });

        try {
          const offer = await pc.createOffer();
          await pc.setLocalDescription(offer);
          send('offer', { to: target, sdp: pc.localDescription.sdp, streamId: 'screen' });
          log(`Sent offer to watch requester ${target}`);
        } catch (error) {
          log(`Failed to send offer to ${target}: ${error.message}`);
        }
        break;
      }
      case 'offer': {
        const target = msg.from;
        const pc = getPeerConnectionFor(target);
        try {
          await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: msg.sdp }));
          const answer = await pc.createAnswer();
          await pc.setLocalDescription(answer);
          send('answer', { to: target, sdp: pc.localDescription.sdp });
        } catch (error) {
          log(`Failed to answer offer from ${target}: ${error.message}`);
        }
        break;
      }
      case 'answer': {
        const pc = peerConnections.get(msg.from);
        if (pc) {
          try {
            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.sdp }));
          } catch (error) {
            log(`Failed to apply answer from ${msg.from}: ${error.message}`);
          }
        }
        break;
      }
      case 'candidate': {
        const pc = peerConnections.get(msg.from);
        if (pc && msg.candidate) {
          try {
            await pc.addIceCandidate(new RTCIceCandidate({
              candidate: msg.candidate,
              sdpMid: msg.sdpMid || '',
              sdpMLineIndex: msg.sdpMLineIndex || 0,
            }));
          } catch (error) {
            log(`ICE candidate rejected for ${msg.from}: ${error.message}`);
          }
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
    peerConnections.forEach((pc) => pc.close());
    peerConnections.clear();
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
  peerConnections.forEach((pc) => pc.close());
  peerConnections.clear();
  currentPublisher = null;
  selectedPublisherId = null;
  send('unannounce');
  setRemoteVideo(null);
  renderPublishers([...knownPublishers.values()]);
});

log('Ready');
