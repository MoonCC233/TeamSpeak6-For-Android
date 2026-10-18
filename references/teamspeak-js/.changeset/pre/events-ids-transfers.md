---
"@honeybbq/teamspeak": major
---

- Events and the client's own accessors use the branded ids: `clientID()` is a `ClientID`, `channelID()` a `ChannelID`, `uniqueIdentifier()` a `UID`, and every event's ids, uids and groups are branded. `clientEnter` delivers the same `ClientEntry` record `listClients` returns. `VoiceData.clientId` is `clientID`, and `sendVoice`/`sendWhisper` take a `Codec` and `ClientID`s.
- `events(name, { signal })` iterates an event with `for await`.
- `socketFactory` returns a `DatagramSocket`, the few methods the client uses, rather than a `node:dgram` socket; `fileTransferSocketFactory` returns any `Duplex`.
- File transfers take `{ signal }`: `uploadFileData`, `downloadFileData` and `dialFileTransfer` take an options object as their last argument (the socket factory moves into it), dialing is bounded, and a stream error during the key handshake no longer escapes as an unhandled `error` event.
