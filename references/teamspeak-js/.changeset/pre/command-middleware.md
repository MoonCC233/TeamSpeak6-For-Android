---
"@honeybbq/teamspeak": major
---

`CommandMiddleware` wraps the typed command layer instead of the wire: it is `(next: CommandExecutor["send"]) => CommandExecutor["send"]`, sees each request and its options, and returns the rows or throws. A middleware can now time, retry or answer a command itself; the old line middleware could only drop one, which left the command waiting out its timeout. Raw `exec`/`execNoWait` lines and the handshake's own commands do not pass through it.
