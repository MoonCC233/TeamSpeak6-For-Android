---
"@honeybbq/teamspeak": major
---

`CryptoInitError` is renamed `HandshakeError`, to match the Go SDK's `ErrHandshake`: `connect()` rejects with it when the handshake fails on what the server sent.
