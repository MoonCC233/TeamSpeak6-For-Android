---
"@honeybbq/teamspeak": major
---

`encodeRequest` and `parseServerCapabilities` are no longer exported. Only the clients encode a `CommandRequest` — a `CommandExecutor` or command middleware is handed the request itself, and `client.send()` encodes it — and only the client reads `initserver`, whose result is `client.serverCapabilities`. `unknownServerCapabilities()` stays public.
