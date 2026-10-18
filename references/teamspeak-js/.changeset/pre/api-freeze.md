---
"@honeybbq/teamspeak": major
---

The last API changes before 1.0, for anyone on a beta:

- `CommandOptions.timeout` is `timeoutMs`, like every other duration. Write `{ timeoutMs: 5_000 }`; `timeout` is no longer read.
- `getClientInfo` is `getClientProperties`. It still answers the raw property map.
- `addServerGroupPermission(client, sgid, { permission, value, negated, skip })` is `addServerGroupPermission(client, sgid, permission, value, { negated, skip })`, as its siblings. `ServerGroupPermission` is gone; the options are `ServerGroupPermissionOptions`.
- `getClientIDs` answers `ClientMatch[]` (`id`, `nickname`) instead of `ClientID[]`: read `match.id`. `getClientNameByDBID` answers a `ClientDatabaseName` (`databaseID`, `name`) instead of a string: read `.name`.
- `updateMessageFlag(client, id, read)` is `updateMessageFlag(client, id, { read })`, a `MessageFlags`.
- `connected` delivers a `ConnectedEvent` (`reconnected`), and `disconnected` a `DisconnectedEvent` (`error`, `willReconnect`) instead of the bare error: write `client.on("disconnected", ({ error }) => …)`.
- `TextMessageEvent.invokerGroups` is `undefined` for a sender the client has not seen, where it was `[]`. Check it before use. `ClientMovedEvent` has a `reasonMessage`.
- `resolveNicknames: true` is `nicknameLookup: true`.
- `sendWhisper(data, [clid], codec)` is `sendWhisper(data, { clients: [clid] }, codec)`. The client's whisper has no channel targets, so `WhisperTargets` has only `clients`. `sendVoice` takes a reserved `VoiceOptions` after the codec.
- `packetChannel` is `incomingChannel`, `honeybbq:teamspeak:packet` is `honeybbq:teamspeak:incoming`, and `PacketTrace` is `IncomingTrace`: it reports every command the server sends, not packets.
