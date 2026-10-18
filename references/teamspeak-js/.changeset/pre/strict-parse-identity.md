---
"@honeybbq/teamspeak": major
---

`parseIdentity` validates strictly: the key must be canonical base64 of at most 32 bytes — checked by a round trip, since Node's decoder skips what it cannot read and loaded such a string as some other key — and the offset a decimal unsigned 64-bit integer. Anything else throws an `InvalidIdentityError`.
