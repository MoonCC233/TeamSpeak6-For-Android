---
"@honeybbq/teamspeak": major
---

One name per concept, as the Go SDK has them: `Identity.publicKeyBase64()` is `publicKey()`; the `TokenType` table is `PrivilegeKeyType`; a `Channel`'s `uniqueIdentifier` is `uid`, of the new `ChannelUID` brand (`asChannelUID`), which keeps it apart from a client's `UID`.
