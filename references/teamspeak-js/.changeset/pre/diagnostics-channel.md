---
"@honeybbq/teamspeak": minor
---

Commands are traced on the `honeybbq:teamspeak:command` tracing channel and incoming commands published on `honeybbq:teamspeak:packet`, through `node:diagnostics_channel`, in place of the removed `TEAMSPEAK_TRACE_RAW`. The messages carry the command's name, never its parameters.
