---
"@honeybbq/teamspeak": patch
---

`listChannels` now returns the fields its type declares. It used to send a bare `channellist`, so the topic was always empty and the permanent, default and password flags, the codec, the client limit and the icon were always zero or false. It now asks for all of them. Each channel also gains five fields: `isSemiPermanent`, `maxFamilyClients`, `totalClients`, `totalFamilyClients` and `neededSubscribePower`. The description and unique identifier are still not part of `channellist`; `getChannelInfo` has them.
