---
"@honeybbq/teamspeak": patch
---

A connection attempt could hang until its timeout when the server dropped a packet of the handshake, which it does under load: only the first packet was ever sent again. Every handshake step is now resent until the server answers the last one.
