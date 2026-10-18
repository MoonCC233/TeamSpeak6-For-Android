---
"@honeybbq/teamspeak": minor
---

`connect()` rejects with the new `InvalidAddressError` for an address that cannot be one — no host, a host with characters no host has, or a port outside 1–65535 — before anything starts, instead of a Node `RangeError` or `TypeError` after the attempt had begun. A host name that does not resolve fails the attempt with a `TeamspeakError` whose `cause` is the lookup's error (`ENOTFOUND`, `EAI_AGAIN`), since a lookup may fail for a while and then succeed. `defaultShouldRetry` does not retry an `InvalidAddressError`.
