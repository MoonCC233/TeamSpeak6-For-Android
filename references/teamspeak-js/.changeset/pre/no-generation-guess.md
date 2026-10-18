---
"@honeybbq/teamspeak": patch
---

A packet is decrypted under the generation the receive window gives its id, and no other. The client used to try the generations either side too, so an old packet replayed once the 16-bit id had wrapped was taken again; neither TS3 nor TS6 ever needed the guess.
