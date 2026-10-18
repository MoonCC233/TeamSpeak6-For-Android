---
"@honeybbq/teamspeak": patch
---

Every list and find answers an empty list for the server's "database empty result set" (1281) instead of throwing it: `listChannels`, `listClients`, `getClientIDs`, `listFiles`, `listChannelGroups`, `listPermissions`, `listServerGroups`, `listBindings` and `getServerGroupsByClient` now do too. `findClients` and `findChannels` answer `[]` when they find nothing, which the server reports as 512 and 768.
