---
"@honeybbq/teamspeak": major
---

Event types are named after their event: `TextMessageEvent` (was `TextMessage`), `ClientEnterEvent` (was `ClientInfo`, still a `ClientEntry`), `ClientLeaveEvent` (was `ClientLeftViewEvent`), `VoiceDataEvent` (was `VoiceData`) and `NotificationEvent` (was `RawNotification`). The client an event is about is `clientID` in `ClientMovedEvent` and `ClientLeaveEvent` (was `id`). `TextMessageEvent.targetMode` and `VoiceDataEvent.codec` are `number`, since a server may send a value the `TextMessageTarget` and `Codec` tables do not name.
