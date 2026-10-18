---
"@honeybbq/teamspeak": patch
---

A server's license chain may have at most 8 blocks, and a block that declares more bytes than the license holds is rejected; either fails the handshake with a CryptoInitError. TS3 sends two blocks and TS6 three, but every block costs a point multiplication, so a chain of a thousand held the event loop for seconds.
