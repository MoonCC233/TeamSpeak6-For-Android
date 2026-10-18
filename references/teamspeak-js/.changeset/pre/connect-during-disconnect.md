---
"@honeybbq/teamspeak": patch
---

`connect()` called while a `disconnect()` is still waiting for the server to acknowledge its goodbye now waits for that disconnect to finish, then connects. It used to start the new attempt at once, which the finishing disconnect then tore down: the new `connect()` rejected with a `NotConnectedError`, no socket opened, and listeners heard `disconnected` twice. A `disconnect()` called during that wait wins, and the waiting `connect()` rejects with a `NotConnectedError`; a second `disconnect()` during a goodbye waits for the first.
