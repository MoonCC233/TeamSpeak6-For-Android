---
"@honeybbq/teamspeak": major
---

A dot-less address is no longer sent to named.myteamspeak.com as a server nickname unless `resolveNicknames: true` is set; otherwise it is resolved like any host. Address resolution is bounded at eight seconds in all, the TSDNS probes of parent domains run at once rather than one three-second timeout after another, and the probes honour the connect signal.
