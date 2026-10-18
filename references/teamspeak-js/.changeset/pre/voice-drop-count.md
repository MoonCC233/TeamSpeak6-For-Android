---
"@honeybbq/teamspeak": patch
---

The "voice dropped" warning's `dropped` field counts the frames dropped since the previous warning, as the Go SDK's does, rather than since the client was made.
