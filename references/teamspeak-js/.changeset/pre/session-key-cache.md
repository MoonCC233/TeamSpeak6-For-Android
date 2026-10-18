---
"@honeybbq/teamspeak": patch
---

Garbage received before the handshake can no longer poison the session keys. A packet that failed the dummy key before crypto init was retried with session keys derived from the still-empty IV, and those public keys stayed cached once the real IV was set. Before crypto init only the dummy key is tried now, and setting the IV clears every cached key.
