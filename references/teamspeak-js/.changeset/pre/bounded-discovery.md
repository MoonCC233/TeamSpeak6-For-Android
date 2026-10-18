---
"@honeybbq/teamspeak": patch
---

Address resolution reads at most 8 KiB from a TSDNS server or the nickname service. Each answers with one short line; one that kept sending was held in memory until the resolution budget ran out.
