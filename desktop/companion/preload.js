'use strict';

const { contextBridge, ipcRenderer } = require('electron');

/**
 * The renderer is the same page the browser mode serves, so it must keep working
 * without this bridge. It only uses `window.ts9Shell` to detect the shell and to
 * prefill the signal URL with the port the shell actually bound.
 */
contextBridge.exposeInMainWorld('ts9Shell', {
  isShell: true,
  info: () => ipcRenderer.invoke('shell:info'),
});
