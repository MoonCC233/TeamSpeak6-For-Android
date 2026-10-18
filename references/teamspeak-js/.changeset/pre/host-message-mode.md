---
"@honeybbq/teamspeak": minor
---

`HostMessageMode` from `@honeybbq/teamspeak` names the host message modes: `None`, `Log`, `Modal` and `ModalQuit`. Compare `capabilities.hostMessageMode` with them: `capabilities.hostMessageMode === HostMessageMode.ModalQuit`. The field stays a `number`, since a server may send a value the table does not name.
