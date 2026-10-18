---
"@honeybbq/teamspeak": major
---

`Identity` is immutable and opaque. `upgradeToLevel(level, { signal })` returns a new identity rather than changing this one, and a level above 160 (the width of SHA-1) is refused with a `RangeError`, as it is by `generateIdentity`. `offset` is read-only, the `privateKey` property is gone (`export()` is the one way to the secret), and the constructor is private. `identity.uid(algorithm)` is new. `parseIdentity` throws only `InvalidIdentityError`, with the underlying failure as its cause. `getUidFromPublicKey(publicKey, algorithm = "sha1")` is `uidFromPublicKey(publicKey, algorithm)`, which takes the algorithm explicitly and returns a `UID`; its type, `UIDAlgorithm`, is exported.
