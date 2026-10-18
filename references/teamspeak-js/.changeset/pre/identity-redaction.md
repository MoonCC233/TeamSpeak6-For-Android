---
"@honeybbq/teamspeak": major
---

`Identity.toString()` returned the private key, so an identity interpolated into a log line leaked it. `toString()`, `toJSON()` and `util.inspect()` now describe the identity without its key, and `export()` is the one way to get the secret form. `identityFromString` is renamed `parseIdentity`. `generateIdentity()` is async and takes `{ signal }`, keeping the event loop running at high levels; `generateIdentitySync()` keeps the old behaviour. `upgradeToLevel()` takes `{ signal }` as an options object.
