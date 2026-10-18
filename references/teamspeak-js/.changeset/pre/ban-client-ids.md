---
"@honeybbq/teamspeak": major
---

`banClient` resolves to a `BanID[]`: the id of every ban it made. The server bans a client on several counts at once — its address, its uid and, on TS6, its myTeamSpeak id — and over ServerQuery answers one `banid` row each, of which only the first was returned. Over the voice protocol, which reports no ids, the list is empty. `addBan`, which makes one ban, still resolves to its id, or `undefined` over the voice protocol.
