---
"@honeybbq/teamspeak": patch
---

Before crypto init completes, the client handles only `clientinitiv`, `initivexpand2` and `error`. Every other line — notifications, `initserver`, data rows, commands it does not know — is dropped with a debug log: a packet then needs nothing but the public dummy key, so anyone on the path could have forged it, a text message or poke from "Admin" among them. A pre-init `error` can still fail the attempt, but never takes the ban path, which only a connected session does.
