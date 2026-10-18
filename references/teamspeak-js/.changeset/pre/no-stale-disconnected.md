---
"@honeybbq/teamspeak": patch
---

The end of one session is never heard during the next. `disconnect()` resolves once its `disconnected` has been delivered, and a `connect()` waits for an earlier session's `disconnected` — after a drop or a failed attempt — to be delivered before it starts. It used to be still queued, so a listener added after `await disconnect()` heard it, and an `events()` iterator started for the new session ended at once. `connected` is, as before, delivered after `connect()` resolves; that and the goodbye's bound — a second at most, less if the caller's signal aborts sooner — are now documented.
