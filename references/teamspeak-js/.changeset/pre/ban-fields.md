---
"@honeybbq/teamspeak": major
---

A ban's fields are a `BanFields` — `ip`, `name`, `uid`, `reason`, `durationMs` — which does not extend `CommandOptions`. `addBan` takes it (as `BanOptions`) and its `signal` and `timeout` as the separate last argument; a `signal` set on the ban used to type-check and was never read. `BanClientOptions` is `BanFields`' duration and reason with `CommandOptions`. The commands that send content — `createChannel`, `editChannel`, `updateClient`, `editClient`, `editClientDatabase`, `addServerGroupPermission` — now say that the content and the trailing options are separate arguments.
