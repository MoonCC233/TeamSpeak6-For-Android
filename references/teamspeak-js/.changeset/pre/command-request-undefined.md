---
"@honeybbq/teamspeak": patch
---

`CommandRequest`'s optional `params`, `rows` and `passwords` accept `undefined`, so a command middleware can build a request with them unset under `exactOptionalPropertyTypes`.
