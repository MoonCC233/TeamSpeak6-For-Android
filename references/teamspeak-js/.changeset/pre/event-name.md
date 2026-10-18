---
"@honeybbq/teamspeak": minor
---

`EventName` from `@honeybbq/teamspeak/client` names the client's events as constants: `client.on(EventName.TextMessage, (message) => { ... })`. The string names keep working and mean the same, so existing code needs no change.
