---
"@honeybbq/teamspeak": patch
---

A packet from the server can no longer crash the process. Every step of the receive path — parsing, decryption, reassembly, decompression, the handshake — is bounded, and whatever it throws is caught: during the handshake the connection fails with a `CryptoInitError` carrying the cause (an Init1 RSA challenge above 100000 squarings, a malformed public key, license or proof), and once connected the packet is dropped. Init1 and the handshake commands are ignored once crypto init has completed, the public dummy key is no longer accepted after it (except for the acknowledgement of `clientek`, which TS3 and TS6 both send under it), QuickLZ input is bounds-checked and capped at 1 MiB, and a command reassembled from fragments is capped at 1 MiB too.
