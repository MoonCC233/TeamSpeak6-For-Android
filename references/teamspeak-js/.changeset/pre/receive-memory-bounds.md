---
"@honeybbq/teamspeak": patch
---

The receive path bounds what a server can make the client hold. A datagram over 4096 bytes is dropped unread (TeamSpeak's are at most 500). The command packets waiting for a missing one hold at most 1 MiB across both queues; past that a packet is dropped unacknowledged, so the server resends it, while the packet they wait for is always taken. A QuickLZ block declaring more than its bytes could expand to — level 1 yields at most about 82 times its input — is refused before its output is allocated. A server sending a window of large datagrams on both queues made a client hold 120 MB.
