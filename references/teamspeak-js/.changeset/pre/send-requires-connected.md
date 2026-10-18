---
"@honeybbq/teamspeak": major
---

Commands, `exec`, `execNoWait`, `sendVoice` and `sendWhisper` fail with a `NotConnectedError` unless the client is connected. They used to need only an open socket, so a command sent while `connect()` was still running — a `clientmove` with its channel password hash, say — went out under the public dummy key, and one sent once `disconnect()` had begun went out after the goodbye started. The handshake's own commands take an internal path.
