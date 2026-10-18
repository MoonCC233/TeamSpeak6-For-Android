---
"@honeybbq/teamspeak": major
---

Parameters are encoded as the Go SDK encodes them. `renameFile` always sends `cpw` and sends `tcid` and `tcpw` together, only when the file moves to another channel. `viewLog` always sends `reverse` and `instance`, and `lines` only above 0. An id of 0 where it filters or targets — `createChannel`'s `parentID`, `listChannelGroupClients`' filters, `listComplaints`' `targetDatabaseID` — is omitted, as is `listBindings`' empty `subsystem`. A permission with id 0, which names none, is refused before anything is sent. A file transfer refused after its command succeeded reports the server's `extra_msg` as the ServerError's `extraMessage`.
