---
"@honeybbq/teamspeak": major
---

`getChannelInfo` used to report `totalClients`, `totalFamilyClients` and `neededSubscribePower` as 0, whatever the channel held. The server does not send them for a single channel, so they are now `undefined` there. Use `listChannels` for live counts.

`Binding.port` is now `number | undefined`: it is undefined when the server reports no port. `Binding.subsystem` is now filled in, with the subsystem you asked for or `voice`.
