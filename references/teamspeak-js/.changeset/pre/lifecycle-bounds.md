---
"@honeybbq/teamspeak": patch
---

- `connect()` is cancellable all the way down: the address lookup and the socket being opened stop when the signal aborts or `disconnect()` interrupts, and a socket that finishes connecting afterwards is closed rather than started, so no handshake can complete, and no `connected` be emitted, after `disconnect()`.
- `sendVoice`, `sendWhisper` and `execNoWait` throw (or reject with) a `NotConnectedError` when there is no connection, rather than dropping the data silently.
- A command's `timeout` and `signal` now bound its wait for the flood throttle too, and `execNoWait`'s signal is honoured there; the throttle no longer leaves an abort listener behind.
- Voice deliveries are dropped while more than 256 deliveries are pending, with a warning at most every ten seconds; `events("voiceData")` buffers at most 256 frames. `events()` stops listening and discards its buffer as soon as its signal aborts.
- The reconnect loop's backoff keeps the process alive, and a `shouldRetry` that throws ends the loop with an error log instead of an unhandled rejection.
