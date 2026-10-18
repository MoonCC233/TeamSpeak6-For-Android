---
"@honeybbq/teamspeak": patch
---

Once crypto init has completed, a command packet (Command or CommandLow) marked unencrypted is dropped. Such a packet used to be accepted on the fake signature alone, which every plaintext ping, pong and voice packet carries, so anyone on the path could forge text messages, kicks and command answers. Voice, ping, pong and acknowledgements stay acceptable unencrypted: TS3 3.13 and TS6 6.0 both acknowledge commands with plaintext Acks, and an Ack carries only the id it acknowledges.
