---
"@honeybbq/teamspeak": minor
---

The `version` option of `Client` chooses the platform, version and signature that `clientinit` sends, and `@honeybbq/teamspeak/versions` exports the versions a server accepts, one constant each: `new Client(identity, addr, nick, { version: Windows_3_6_2_1695203293 })`. Without the option the client sends what it always did. `ClientVersion` is exported from `@honeybbq/teamspeak`.
