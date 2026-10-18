---
"@honeybbq/teamspeak": patch
---

The idle timeout is reset only by a packet that decrypts. Any datagram used to reset it, so anyone able to send to the client's port could keep a session the server had left looking connected forever.
