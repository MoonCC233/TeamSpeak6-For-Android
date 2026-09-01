'use strict';

const { createServer } = require('./server.js');

const DEFAULT_PORT = Number(process.env.PORT || 4173);

/**
 * Boots the UI + signaling server for the desktop shell.
 *
 * The shell deliberately reuses `server.js` instead of loading `index.html`
 * from disk: that keeps one signaling implementation (`server/signaling`) and
 * one origin for the UI, so the shell and the browser mode cannot drift apart.
 *
 * A desktop app must not die because something else already holds the port, so
 * fall back to an ephemeral one and report back which port actually won.
 */
async function startShellServer(preferredPort = DEFAULT_PORT) {
  try {
    const started = await createServer(preferredPort);
    return { ...started, port: started.port ?? preferredPort, usedFallbackPort: false };
  } catch (error) {
    if (error?.code !== 'EADDRINUSE') throw error;
    const started = await createServer(0);
    return { ...started, usedFallbackPort: true, preferredPort };
  }
}

/**
 * Picks a capture source for `getDisplayMedia()` when the platform has no
 * system picker. Whole screens beat windows because a screen always exists,
 * while the window list depends on what the user happens to have open.
 */
function pickDisplaySource(sources = []) {
  if (!Array.isArray(sources) || sources.length === 0) return null;
  return sources.find((source) => String(source.id || '').startsWith('screen:')) || sources[0];
}

/**
 * Loopback audio capture is only wired up on Windows in Electron; asking for it
 * elsewhere fails the whole request instead of just dropping the audio track.
 */
function shellAudioConstraint(platform = process.platform) {
  return platform === 'win32' ? 'loopback' : false;
}

module.exports = {
  DEFAULT_PORT,
  pickDisplaySource,
  shellAudioConstraint,
  startShellServer,
};
