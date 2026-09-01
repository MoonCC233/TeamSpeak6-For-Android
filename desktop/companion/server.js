'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');
const signaling = require('../../server/signaling/index.js');

const root = __dirname;
const port = Number(process.env.PORT || 4173);

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

/**
 * Serves the desktop UI and hosts the signaling protocol on the same port so a
 * LAN test needs one process.
 *
 * The protocol itself comes from `server/signaling`: a second implementation
 * here would drift from the phone's expectations, which is exactly the class of
 * bug that broke interop before.
 */
function createSignalServer(httpServer) {
  return signaling.attachSignaling(new WebSocketServer({ server: httpServer }));
}

function createServer(targetPort = port) {
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

  const wss = createSignalServer(server);
  // Callers (including the smoke test) only hold the HTTP server, so tie the
  // websocket server's lifetime to it or the process never exits.
  server.on('close', () => wss.close());

  return new Promise((resolve, reject) => {
    // A bound port is a startup failure the caller has to see (the Electron
    // shell retries on an ephemeral port), so surface it instead of hanging on
    // a promise that never settles.
    //
    // `ws` re-emits the HTTP server's errors on itself, and an unhandled one
    // there crashes the process before this rejection can be observed, so both
    // emitters need a listener.
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      wss.close();
      server.close();
      reject(error);
    };

    server.once('error', fail);
    wss.once('error', fail);

    server.listen(targetPort, () => {
      settled = true;
      server.removeListener('error', fail);
      wss.removeListener('error', fail);
      resolve({ server, wss, port: server.address().port });
    });
  });
}

if (require.main === module) {
  createServer(port).then(({ server }) => {
    console.log(`PC companion UI + MSS signaling server ready at http://127.0.0.1:${port}`);
    console.log(`WebSocket endpoint: ws://127.0.0.1:${port}`);
    server.on('close', () => {
      console.log('PC companion server stopped');
    });
  });
}

module.exports = {
  createServer,
  createSignalServer,
  deriveRoomId: signaling.deriveRoomId,
  handleMessage: signaling.handleMessage,
  peersBySocket: signaling.peersBySocket,
  removePeer: signaling.removePeer,
  roomFor: signaling.roomFor,
  rooms: signaling.rooms,
  summarizePeer: signaling.summarizePeer,
  summarizeShare: signaling.summarizeShare,
};

