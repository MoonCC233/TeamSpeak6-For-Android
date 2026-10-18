---
"@honeybbq/teamspeak": major
---

The client's lifecycle, commands and events, in line with teamspeak-go:

- `connect({ signal })` resolves once the handshake completes and rejects with the cause when it fails; `waitConnected()` is gone. Aborting the signal abandons the attempt. `disconnect({ signal })` bounds the goodbye, and a `connect()` it interrupts rejects with `NotConnectedError` instead of waiting forever. `Client` is `AsyncDisposable`.
- `exec(line, { signal, timeout })` and `execNoWait(line, { signal })` replace `execCommand`, `execCommandWithResponse` and `sendCommandNoWait`. `CommandExecutor.send` takes the same options.
- `on()` returns a function that removes the listener (also a `Disposable`), and `once()` is new. Listeners run one at a time in arrival order; a listener removed while its event was queued does not get it. Event middleware receives `{ name, payload }` rather than a payload whose kind it had to guess, and is set with the `eventMiddleware` and `commandMiddleware` options only (`useCommandMiddleware` and `useEventMiddleware` are gone).
- A kick, a ban or the server stopping ends the session at once, and `disconnected` receives a `RemovedError`; a server that stops answering is a `ConnectionTimeoutError`. `disconnected` is `undefined` only after `disconnect()`. Channel kicks, which arrive as a move, now reach `kicked`, which receives a `KickedEvent`. `reasonid` is `reason`, with names in the new `Reason` table.
- `defaultShouldRetry` replaces `isPermanentFailure`, and does not retry a kick or a ban. `defaultBackoffPolicy` is frozen, `backoffDelay(policy, attempt)` no longer takes a random source, and a jittered delay stays under `maxDelayMs`.
- The default logger is silent, and `TEAMSPEAK_TRACE_RAW` no longer writes traffic to stderr. `ClientStatus` is a string union. The unused `ChannelInfo` and `ClientState` types are gone, and an IPv6 address is no longer mistaken for one with a port.
