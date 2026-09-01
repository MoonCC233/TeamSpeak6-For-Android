'use strict';

const path = require('path');
const { app, BrowserWindow, desktopCapturer, ipcMain, session, shell } = require('electron');
const { pickDisplaySource, shellAudioConstraint, startShellServer } = require('./shell.js');

let uiOrigin = null;
let httpServer = null;

const createWindow = () => {
  const win = new BrowserWindow({
    width: 1200,
    height: 820,
    backgroundColor: '#1b1d21',
    title: 'TeamSpeak9',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  // External links belong in the user's browser, not in a chrome-less window
  // that still has our preload attached.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  // Load over HTTP rather than file:// so the shell and the browser mode share
  // one origin. The page's CSP is `default-src 'self'` plus `connect-src ws:`,
  // which only lines up with the served origin.
  win.loadURL(uiOrigin);
  return win;
};

/**
 * Electron has no built-in screen picker on every platform, so
 * `getDisplayMedia()` rejects unless the app supplies a source. Without this the
 * renderer's "Start sharing" button fails in the shell while working in a
 * browser.
 */
function installDisplayMediaHandler() {
  session.defaultSession.setDisplayMediaRequestHandler(
    (_request, callback) => {
      desktopCapturer
        .getSources({ types: ['screen', 'window'] })
        .then((sources) => {
          const source = pickDisplaySource(sources);
          if (!source) {
            callback({});
            return;
          }
          callback({ video: source, audio: shellAudioConstraint() });
        })
        .catch(() => callback({}));
    },
    { useSystemPicker: true },
  );

  // Screen capture is the only permission this app needs, so everything else
  // stays denied rather than inheriting Chromium's prompts.
  session.defaultSession.setPermissionRequestHandler((_contents, permission, callback) => {
    callback(permission === 'display-capture' || permission === 'media');
  });
}

app.whenReady().then(async () => {
  const started = await startShellServer();
  httpServer = started.server;
  uiOrigin = `http://127.0.0.1:${started.port}`;

  if (started.usedFallbackPort) {
    console.warn(`Port ${started.preferredPort} was busy; serving on ${started.port} instead.`);
  }

  installDisplayMediaHandler();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (httpServer) httpServer.close();
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  if (httpServer) httpServer.close();
});

ipcMain.handle('shell:info', () => ({
  version: app.getVersion(),
  platform: process.platform,
  signalUrl: uiOrigin ? uiOrigin.replace(/^http/, 'ws') : '',
}));
